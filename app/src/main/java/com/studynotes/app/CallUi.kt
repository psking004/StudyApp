package com.studynotes.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

fun callPermissions(video: Boolean): Array<String> =
    if (video) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA) else arrayOf(Manifest.permission.RECORD_AUDIO)

fun hasPermissions(ctx: Context, perms: Array<String>) = perms.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }

/**
 * Full-screen call screen. v1.4: MainActivity only composes this while the chat section is unlocked. Back relocks
 * the app (hides the chats and this screen) without hanging up or declining; the call comes back after unlocking.
 */
@Composable
fun CallOverlay(onHide: () -> Unit) {
    val s = CallManager.state.value ?: return
    val ctx = LocalContext.current
    var err by remember { mutableStateOf<String?>(null) }
    val permLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.all { it }) CallManager.accept { err = it }
        else err = if (s.video) "Allow microphone and camera to answer video calls." else "Allow the microphone to answer calls."
    }
    DisposableEffect(Unit) { CallManager.callUiVisible = true; onDispose { CallManager.callUiVisible = false } }
    BackHandler { onHide() } // hide with the chat section; the call itself keeps going (or keeps ringing)
    Box(Modifier.fillMaxSize().background(Color(0xFF0B141A))) {
        val remote = CallManager.remoteVideo.value
        val local = CallManager.localVideo.value
        if (s.video && remote != null && s.phase == CallPhase.Connected) VideoView(remote, mirror = false, Modifier.fillMaxSize(), overlay = false)
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (!(s.video && remote != null && s.phase == CallPhase.Connected)) {
                Box(Modifier.size(96.dp).clip(CircleShape).background(Color(0xFF2A3942)), contentAlignment = Alignment.Center) {
                    Text(s.peerName.take(1).uppercase(), color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(16.dp))
            }
            Text("@${s.peerName}", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(statusText(s), color = Color(0xFFB0BEC5), fontSize = 15.sp)
            err?.let { Spacer(Modifier.height(10.dp)); Text(it, color = Color(0xFFFFB4AB), fontSize = 14.sp, modifier = Modifier.padding(horizontal = 24.dp)) }
        }
        if (s.video && local != null && s.cameraOn && s.phase != CallPhase.Ended)
            Box(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp).size(110.dp, 160.dp).clip(RoundedCornerShape(12.dp))) {
                VideoView(local, mirror = true, Modifier.fillMaxSize(), overlay = true)
            }
        Row(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 40.dp, start = 16.dp, end = 16.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            when (s.phase) {
                CallPhase.Incoming -> {
                    RoundBtn(Icons.Default.CallEnd, "Decline", Color(0xFFE53935)) { CallManager.decline() }
                    RoundBtn(if (s.video) Icons.Default.Videocam else Icons.Default.Call, "Accept", Color(0xFF25D366)) {
                        val perms = callPermissions(s.video)
                        if (hasPermissions(ctx, perms)) CallManager.accept { err = it } else permLauncher.launch(perms)
                    }
                }
                CallPhase.Ended -> {}
                else -> {
                    RoundBtn(if (s.muted) Icons.Default.MicOff else Icons.Default.Mic, if (s.muted) "Unmute" else "Mute",
                        if (s.muted) Color.White else Color(0xFF2A3942), tint = if (s.muted) Color.Black else Color.White) { CallManager.toggleMute() }
                    RoundBtn(if (s.speaker) Icons.Default.VolumeUp else Icons.Default.VolumeDown, "Speaker",
                        if (s.speaker) Color.White else Color(0xFF2A3942), tint = if (s.speaker) Color.Black else Color.White) { CallManager.toggleSpeaker() }
                    if (s.video) {
                        RoundBtn(if (s.cameraOn) Icons.Default.Videocam else Icons.Default.VideocamOff, "Camera",
                            if (s.cameraOn) Color(0xFF2A3942) else Color.White, tint = if (s.cameraOn) Color.White else Color.Black) { CallManager.toggleCamera() }
                        RoundBtn(Icons.Default.Cameraswitch, "Switch", Color(0xFF2A3942)) { CallManager.switchCamera() }
                    }
                    RoundBtn(Icons.Default.CallEnd, "End", Color(0xFFE53935)) { CallManager.hangup() }
                }
            }
        }
    }
}

@Composable
private fun statusText(s: CallState): String {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(s.phase) { while (s.phase == CallPhase.Connected) { now = System.currentTimeMillis(); delay(1000) } }
    return when (s.phase) {
        CallPhase.Outgoing -> if (s.video) "Video calling..." else "Calling..."
        CallPhase.Incoming -> if (s.video) "Incoming video call" else "Incoming voice call"
        CallPhase.Connecting -> "Connecting..."
        CallPhase.Connected -> { val sec = ((now - s.connectedAt) / 1000).coerceAtLeast(0); "%d:%02d".format(sec / 60, sec % 60) }
        CallPhase.Ended -> s.endReason
    }
}

@Composable
private fun RoundBtn(icon: ImageVector, label: String, bg: Color, tint: Color = Color.White, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconButton(onClick = onClick, modifier = Modifier.size(60.dp), shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = bg)) { Icon(icon, label, tint = tint) }
        Spacer(Modifier.height(6.dp))
        Text(label, color = Color.White, fontSize = 12.sp)
    }
}

@Composable
private fun VideoView(track: VideoTrack, mirror: Boolean, modifier: Modifier, overlay: Boolean) {
    val egl = CallManager.eglContext() ?: return
    var renderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    AndroidView(modifier = modifier, factory = { c ->
        SurfaceViewRenderer(c).apply {
            init(egl, null)
            setMirror(mirror)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            setEnableHardwareScaler(true)
            setSecure(true) // keep video out of screenshots/recents like the rest of the hidden UI
            if (overlay) setZOrderMediaOverlay(true)
            renderer = this
        }
    })
    DisposableEffect(Unit) { onDispose { try { renderer?.release() } catch (_: Exception) { } } }
    DisposableEffect(track, renderer) {
        val r = renderer
        if (r != null) try { track.addSink(r) } catch (_: Exception) { }
        onDispose { if (r != null) try { track.removeSink(r) } catch (_: Exception) { } }
    }
}
