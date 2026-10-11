package com.studynotes.app

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateOf
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.ListenerRegistration
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule

enum class CallPhase { Outgoing, Incoming, Connecting, Connected, Ended }

data class CallState(
    val callId: String, val peerUid: String, val peerName: String, val video: Boolean, val incoming: Boolean,
    val phase: CallPhase, val muted: Boolean = false, val speaker: Boolean = false, val cameraOn: Boolean = true,
    val connectedAt: Long = 0, val endReason: String = ""
)

/**
 * 1:1 voice/video calls over WebRTC. Signaling lives in Firestore:
 * calls/{callId} {caller, callee, callerName, calleeName, type, status, offer, answer, createdMs, created}
 * calls/{callId}/callerCandidates/{id}, calls/{callId}/calleeCandidates/{id}.
 * Media is peer-to-peer and DTLS-SRTP encrypted by WebRTC itself.
 */
object CallManager {
    val state = mutableStateOf<CallState?>(null)
    val remoteVideo = mutableStateOf<VideoTrack?>(null)
    val localVideo = mutableStateOf<VideoTrack?>(null)
    /** Called (main thread) when a call starts ringing for us, so the activity can post a disguised notification. */
    var onRinging: (() -> Unit)? = null
    var onRingingStopped: (() -> Unit)? = null

    val active: Boolean get() = state.value?.let { it.phase != CallPhase.Ended } == true

    private val main = Handler(Looper.getMainLooper())
    private lateinit var appCtx: Context
    private var repo: ChatRepo? = null
    private var incomingReg: ListenerRegistration? = null
    private var docReg: ListenerRegistration? = null
    private var candReg: ListenerRegistration? = null
    private val seenIncoming = HashSet<String>()

    private var egl: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var pc: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var capturer: CameraVideoCapturer? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var remoteDescSet = false
    private val pendingRemote = mutableListOf<IceCandidate>()
    private var docReady = false
    private val pendingLocal = mutableListOf<IceCandidate>()
    private var timeout: Runnable? = null
    private var pendingOffer: String? = null
    private var savedAudioMode = AudioManager.MODE_NORMAL

    fun eglContext(): EglBase.Context? = egl?.eglBaseContext

    private fun ui(block: () -> Unit) { if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block) }
    private fun update(f: (CallState) -> CallState) = ui { state.value?.let { state.value = f(it) } }

    /** Starts listening for calls to me. Safe to call repeatedly. */
    fun attach(ctx: Context, r: ChatRepo) {
        appCtx = ctx.applicationContext
        repo = r
        val me = r.myUid.value ?: return
        val db = r.firestore ?: return
        incomingReg?.remove()
        incomingReg = db.collection("calls").whereEqualTo("callee", me).whereEqualTo("status", "ringing")
            .addSnapshotListener { snap, _ ->
                if (snap == null) return@addSnapshotListener
                for (ch in snap.documentChanges) {
                    if (ch.type != DocumentChange.Type.ADDED) continue
                    val d = ch.document
                    if (!seenIncoming.add(d.id)) continue
                    val created = d.getLong("createdMs") ?: 0L
                    if (System.currentTimeMillis() - created > 45_000) continue // stale ring
                    if (active) { d.reference.update("status", "declined"); continue } // busy
                    pendingOffer = (d.get("offer") as? Map<*, *>)?.get("sdp")?.toString() ?: continue
                    state.value = CallState(d.id, d.getString("caller") ?: "", d.getString("callerName") ?: "user",
                        d.getString("type") == "video", incoming = true, phase = CallPhase.Incoming, speaker = d.getString("type") == "video")
                    watchDoc(d.id)
                    armTimeout()
                    onRinging?.invoke()
                }
            }
    }

    fun detach() {
        if (active) hangup()
        incomingReg?.remove(); incomingReg = null
    }

    private fun ensureFactory() {
        if (factory != null) return
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(appCtx).createInitializationOptions())
        val e = EglBase.create(); egl = e
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(e.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(e.eglBaseContext))
            .setAudioDeviceModule(JavaAudioDeviceModule.builder(appCtx).createAudioDeviceModule())
            .createPeerConnectionFactory()
    }

    private fun iceServers(): List<PeerConnection.IceServer> = (repo?.iceConfig ?: emptyList()).map { (urls, user, cred) ->
        PeerConnection.IceServer.builder(urls).apply { if (user.isNotBlank()) setUsername(user); if (cred.isNotBlank()) setPassword(cred) }.createIceServer()
    }.ifEmpty { listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()) }

    private fun buildPeer(video: Boolean, candidateCollection: String) {
        ensureFactory()
        val f = factory!!
        val cfg = PeerConnection.RTCConfiguration(iceServers()).apply { sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN }
        pc = f.createPeerConnection(cfg, object : PeerConnection.Observer {
            override fun onIceCandidate(c: IceCandidate) = ui { if (docReady) sendCandidate(candidateCollection, c) else pendingLocal += c }
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) = ui {
                when (s) {
                    PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED ->
                        update { if (it.phase == CallPhase.Connected) it else it.copy(phase = CallPhase.Connected, connectedAt = System.currentTimeMillis()) }
                    PeerConnection.IceConnectionState.FAILED -> endLocal("Connection lost", writeStatus = true)
                    else -> {}
                }
            }
            override fun onTrack(t: RtpTransceiver) {
                val tr = t.receiver.track()
                if (tr is VideoTrack) ui { remoteVideo.value = tr }
            }
            override fun onAddTrack(r: RtpReceiver, s: Array<out MediaStream>) {}
            override fun onSignalingChange(s: PeerConnection.SignalingState) {}
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {}
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) {}
            override fun onAddStream(s: MediaStream) {}
            override fun onRemoveStream(s: MediaStream) {}
            override fun onDataChannel(d: DataChannel) {}
            override fun onRenegotiationNeeded() {}
        })
        audioSource = f.createAudioSource(MediaConstraints())
        audioTrack = f.createAudioTrack("a0", audioSource).also { pc?.addTrack(it, listOf("s0")) }
        if (video) startCamera(f)
        val am = appCtx.getSystemService(AudioManager::class.java)
        savedAudioMode = am.mode
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        setSpeaker(video)
    }

    private fun startCamera(f: PeerConnectionFactory) {
        try {
            val en = Camera2Enumerator(appCtx)
            val name = en.deviceNames.firstOrNull { en.isFrontFacing(it) } ?: en.deviceNames.firstOrNull() ?: return
            val cap = en.createCapturer(name, null) ?: return
            val helper = SurfaceTextureHelper.create("cam", egl!!.eglBaseContext)
            val src = f.createVideoSource(false)
            cap.initialize(helper, appCtx, src.capturerObserver)
            cap.startCapture(1280, 720, 30)
            capturer = cap; surfaceHelper = helper; videoSource = src
            videoTrack = f.createVideoTrack("v0", src).also { pc?.addTrack(it, listOf("s0")); localVideo.value = it }
        } catch (_: Exception) { }
    }

    private fun sendCandidate(col: String, c: IceCandidate) {
        val id = state.value?.callId ?: return
        repo?.firestore?.collection("calls")?.document(id)?.collection(col)
            ?.add(mapOf("candidate" to c.sdp, "sdpMid" to c.sdpMid, "sdpMLineIndex" to c.sdpMLineIndex))
    }

    private fun flushLocal(col: String) { docReady = true; pendingLocal.forEach { sendCandidate(col, it) }; pendingLocal.clear() }

    private fun listenCandidates(callId: String, col: String) {
        candReg?.remove()
        candReg = repo?.firestore?.collection("calls")?.document(callId)?.collection(col)?.addSnapshotListener { snap, _ ->
            snap ?: return@addSnapshotListener
            for (ch in snap.documentChanges) {
                if (ch.type != DocumentChange.Type.ADDED) continue
                val d = ch.document
                val c = IceCandidate(d.getString("sdpMid"), (d.getLong("sdpMLineIndex") ?: 0L).toInt(), d.getString("candidate") ?: continue)
                if (remoteDescSet) pc?.addIceCandidate(c) else pendingRemote += c
            }
        }
    }

    private fun setRemote(type: SessionDescription.Type, sdp: String, then: () -> Unit = {}) {
        pc?.setRemoteDescription(object : SimpleSdp() {
            override fun onSetSuccess() = ui {
                remoteDescSet = true
                pendingRemote.forEach { pc?.addIceCandidate(it) }; pendingRemote.clear()
                then()
            }
            override fun onSetFailure(e: String?) = ui { endLocal("Call failed", writeStatus = true) }
        }, SessionDescription(type, sdp))
    }

    private fun watchDoc(callId: String) {
        docReg?.remove()
        docReg = repo?.firestore?.collection("calls")?.document(callId)?.addSnapshotListener { d, _ ->
            d ?: return@addSnapshotListener
            val st = state.value ?: return@addSnapshotListener
            if (st.callId != callId) return@addSnapshotListener
            when (d.getString("status")) {
                "declined" -> endLocal(if (st.incoming) "Call ended" else "Declined", writeStatus = false)
                "ended" -> endLocal(if (st.phase == CallPhase.Incoming) "Missed call" else "Call ended", writeStatus = false)
                "accepted" -> {
                    if (!st.incoming && !remoteDescSet) {
                        val ans = (d.get("answer") as? Map<*, *>)?.get("sdp")?.toString() ?: return@addSnapshotListener
                        cancelTimeout()
                        update { it.copy(phase = CallPhase.Connecting) }
                        setRemote(SessionDescription.Type.ANSWER, ans)
                    }
                }
            }
        }
    }

    private fun armTimeout() {
        cancelTimeout()
        timeout = Runnable {
            val s = state.value ?: return@Runnable
            if (s.phase == CallPhase.Outgoing) endLocal("No answer", writeStatus = true)
            else if (s.phase == CallPhase.Incoming) endLocal("Missed call", writeStatus = false)
        }.also { main.postDelayed(it, 45_000) }
    }

    private fun cancelTimeout() { timeout?.let { main.removeCallbacks(it) }; timeout = null }

    /** Caller side. Permissions must already be granted. */
    fun startCall(chat: ChatItem, video: Boolean, onError: (String) -> Unit) {
        val r = repo ?: return onError("Not connected")
        val me = r.myUid.value ?: return onError("Not connected")
        val db = r.firestore ?: return onError("Not connected")
        if (active) return onError("You're already in a call")
        val ref = db.collection("calls").document()
        resetFlags()
        state.value = CallState(ref.id, chat.peerUid, chat.peerName, video, incoming = false, phase = CallPhase.Outgoing, speaker = video)
        try { buildPeer(video, "callerCandidates") } catch (e: Exception) { endLocal("Couldn't start the call", false); return onError("Couldn't start the call on this phone.") }
        val cons = MediaConstraints().apply {
            mandatory += MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true")
            mandatory += MediaConstraints.KeyValuePair("OfferToReceiveVideo", if (video) "true" else "false")
        }
        pc?.createOffer(object : SimpleSdp() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                pc?.setLocalDescription(object : SimpleSdp() {
                    override fun onSetSuccess() = ui {
                        if (state.value?.callId != ref.id) return@ui
                        ref.set(mapOf("caller" to me, "callee" to chat.peerUid, "callerName" to (r.myName.value ?: ""),
                            "calleeName" to chat.peerName, "type" to if (video) "video" else "audio", "status" to "ringing",
                            "offer" to mapOf("type" to "offer", "sdp" to sdp.description),
                            "createdMs" to System.currentTimeMillis(), "created" to FieldValue.serverTimestamp()))
                            .addOnSuccessListener {
                                if (state.value?.callId != ref.id) return@addOnSuccessListener
                                flushLocal("callerCandidates")
                                listenCandidates(ref.id, "calleeCandidates")
                                watchDoc(ref.id)
                            }
                            .addOnFailureListener { e -> endLocal("Call failed", false); onError(r.friendly(e)) }
                        armTimeout()
                    }
                    override fun onSetFailure(e: String?) = ui { endLocal("Call failed", false); onError("Couldn't start the call.") }
                }, sdp)
            }
            override fun onCreateFailure(e: String?) = ui { endLocal("Call failed", false); onError("Couldn't start the call.") }
        }, cons)
    }

    /** Callee side. Permissions must already be granted. */
    fun accept(onError: (String) -> Unit) {
        val s = state.value ?: return
        val offer = pendingOffer ?: return onError("This call is no longer available")
        val ref = repo?.firestore?.collection("calls")?.document(s.callId) ?: return
        cancelTimeout()
        onRingingStopped?.invoke()
        resetFlags()
        update { it.copy(phase = CallPhase.Connecting) }
        try { buildPeer(s.video, "calleeCandidates") } catch (e: Exception) { endLocal("Call failed", true); return onError("Couldn't start the call on this phone.") }
        setRemote(SessionDescription.Type.OFFER, offer) {
            listenCandidates(s.callId, "callerCandidates")
            pc?.createAnswer(object : SimpleSdp() {
                override fun onCreateSuccess(sdp: SessionDescription) {
                    pc?.setLocalDescription(object : SimpleSdp() {
                        override fun onSetSuccess() = ui {
                            ref.update(mapOf("answer" to mapOf("type" to "answer", "sdp" to sdp.description), "status" to "accepted"))
                                .addOnSuccessListener { flushLocal("calleeCandidates") }
                                .addOnFailureListener { e -> endLocal("Call failed", false); onError(repo?.friendly(e) ?: "Call failed") }
                        }
                        override fun onSetFailure(e: String?) = ui { endLocal("Call failed", true) }
                    }, sdp)
                }
                override fun onCreateFailure(e: String?) = ui { endLocal("Call failed", true) }
            }, MediaConstraints())
        }
    }

    fun decline() {
        val s = state.value ?: return
        repo?.firestore?.collection("calls")?.document(s.callId)?.update("status", "declined")
        endLocal("Declined", writeStatus = false)
    }

    fun hangup() = endLocal("Call ended", writeStatus = true)

    fun toggleMute() { val m = !(state.value?.muted ?: false); audioTrack?.setEnabled(!m); update { it.copy(muted = m) } }

    fun toggleSpeaker() { val sp = !(state.value?.speaker ?: false); setSpeaker(sp); update { it.copy(speaker = sp) } }

    @Suppress("DEPRECATION")
    private fun setSpeaker(on: Boolean) {
        try { appCtx.getSystemService(AudioManager::class.java).isSpeakerphoneOn = on } catch (_: Exception) { }
    }

    fun toggleCamera() {
        val on = !(state.value?.cameraOn ?: true)
        videoTrack?.setEnabled(on)
        try { if (on) capturer?.startCapture(1280, 720, 30) else capturer?.stopCapture() } catch (_: Exception) { }
        update { it.copy(cameraOn = on) }
    }

    fun switchCamera() { try { capturer?.switchCamera(null) } catch (_: Exception) { } }

    /** Called from the call screen to clear an "ended" banner. */
    fun dismissEnded() { if (state.value?.phase == CallPhase.Ended) state.value = null }

    private fun resetFlags() { remoteDescSet = false; docReady = false; pendingRemote.clear(); pendingLocal.clear() }

    private fun endLocal(reason: String, writeStatus: Boolean) = ui {
        val s = state.value ?: return@ui
        if (s.phase == CallPhase.Ended) return@ui
        cancelTimeout()
        onRingingStopped?.invoke()
        if (writeStatus) repo?.firestore?.collection("calls")?.document(s.callId)?.update("status", "ended")
        docReg?.remove(); docReg = null
        candReg?.remove(); candReg = null
        cleanupMedia()
        pendingOffer = null
        state.value = s.copy(phase = CallPhase.Ended, endReason = reason)
        main.postDelayed({ if (state.value?.callId == s.callId && state.value?.phase == CallPhase.Ended) state.value = null }, 1500)
    }

    private fun cleanupMedia() {
        remoteVideo.value = null; localVideo.value = null
        try { capturer?.stopCapture() } catch (_: Exception) { }
        try { capturer?.dispose() } catch (_: Exception) { }
        capturer = null
        try { surfaceHelper?.dispose() } catch (_: Exception) { }
        surfaceHelper = null
        try { pc?.close() } catch (_: Exception) { }
        try { pc?.dispose() } catch (_: Exception) { }
        pc = null
        try { videoTrack?.dispose() } catch (_: Exception) { }
        try { videoSource?.dispose() } catch (_: Exception) { }
        try { audioTrack?.dispose() } catch (_: Exception) { }
        try { audioSource?.dispose() } catch (_: Exception) { }
        videoTrack = null; videoSource = null; audioTrack = null; audioSource = null
        resetFlags()
        if (::appCtx.isInitialized) try {
            val am = appCtx.getSystemService(AudioManager::class.java)
            setSpeaker(false); am.mode = savedAudioMode
        } catch (_: Exception) { }
    }
}

open class SimpleSdp : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(e: String?) {}
    override fun onSetFailure(e: String?) {}
}
