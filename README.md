# Study Notes

**An Android notes app with a hidden, end-to-end encrypted messenger and WebRTC voice/video calls, built on Firebase.**

On the surface it is an ordinary study notes app. Typing a secret word in *Search notes* unlocks a private messenger: username-only accounts (no phone number or email), messages and attachments encrypted on the device with ECDH P-256 + AES-256-GCM, and peer-to-peer voice and video calls. There is no custom backend: the app talks directly to Firebase (Anonymous Auth + Cloud Firestore), and Firestore security rules protect the data. It runs on Firebase's free Spark plan.

**Current version: v1.7** (`versionCode` 8). v1.7 is in development; see the [Changelog](#changelog).

## Tech stack

- **Language / UI:** Kotlin, Jetpack Compose (Material 3), single-activity app
- **Backend:** Firebase Anonymous Authentication, Cloud Firestore (asia-south1), Firestore security rules (`firestore.rules`)
- **Encryption:** Java Cryptography Architecture — EC P-256 identity key per device (private key wrapped by an Android Keystore AES-GCM key), ECDH key agreement per chat, AES-256-GCM per message and attachment; PBKDF2-SHA256 passcode hash
- **Calls:** WebRTC (`stream-webrtc-android`), Firestore as the signaling channel, Google STUN with optional TURN relay
- **Android platform:** Coroutines, `ProcessLifecycleOwner`, MediaStore, foreground service (microphone), `FLAG_SECURE`
- **Build:** Gradle (Kotlin DSL), AGP 8.5, Kotlin 1.9, JDK 17, minSdk 26 / targetSdk 34

## Features

- **Disguised entry:** a working notes app (notes stay on the phone); the secret word opens the messenger.
- **End-to-end encrypted chat:** text is encrypted on the phone before upload; Firestore only ever sees ciphertext. Realtime delivery ticks.
- **Replies (v1.7):** swipe right or long-press to quote a message; the quote is encrypted too.
- **Encrypted photos, files and voice messages:** encrypted with the per-chat key, split into ~700 KB chunks and stored as Firestore Blobs (no Cloud Storage needed), 5 MB limit. Photos are resized and location metadata is stripped. Optional *Save to gallery* for photos.
- **1:1 voice and video calls:** WebRTC peer-to-peer media (DTLS-SRTP), mute, speaker, camera toggle/switch; call records are deleted from Firestore shortly after each call ends.
- **Privacy lock:** chats lock after 1 minute idle, when the app leaves the foreground, or when the screen turns off; screenshots and Recents thumbnails are blocked; passcode stored only as a salted PBKDF2 hash.
- **Disguised notifications:** every alert reads only "Study reminder", with nothing shown on the lock screen.
- **Delete for me / for everyone:** for messages and whole chats, including their encrypted attachment chunks.

## Production architecture

```
Android app (APK)  ──HTTPS──▶  Firebase (Google cloud, always on)
  • Notes: stored only on the phone        • Anonymous Auth (one identity per install)
  • Chats: ECDH P-256 + AES-GCM, encrypted  • Cloud Firestore (asia-south1): usernames,
    on the phone before they're sent           public keys, encrypted messages, call signaling
  • Photos/files: encrypted on the phone   • Firestore: encrypted attachment chunks (Blob)
  • Calls: WebRTC peer-to-peer ◀──────────▶ (STUN: Google; optional TURN relay)
```

There is no custom backend, no localhost, and no server on your computer. The app talks straight to Firebase over HTTPS, so it works on any Wi-Fi or mobile network. Firestore security rules (`firestore.rules`) are what protect the data on the server.

## Configuration (the Android version of env variables)

| File | Committed? | What it holds |
|---|---|---|
| `app/src/main/assets/firebase_config.json` | **No** (gitignored) | Firebase `apiKey`, `appId`, `projectId`, `messagingSenderId`, and optional TURN keys `turn_urls` (comma-separated or JSON array), `turn_username`, `turn_credential`. These are public client identifiers that ship inside every Firebase app; Firestore rules are what keep the data safe. They stay out of the public repo so GitHub's secret scanner doesn't flag them. |
| `app/src/main/assets/firebase_config.example.json` | Yes | Placeholder template |
| `keystore.properties` + `*.jks` | **No** (secret) | Release signing key and passwords. **Back these up.** If you lose them you can't ship updates that install over the old app. |
| `keystore.properties.example` | Yes | Placeholder template |

No server-side secrets exist, and none are in the APK.

## Firebase setup (one time, already done for project `study-notes-app-861b9`)

1. Create a Firebase project on the free Spark plan.
2. Authentication → Sign-in method → enable **Anonymous**.
3. Firestore Database → create it (production mode, asia-south1).
4. Firestore → Rules → paste `firestore.rules` → Publish.
5. Project settings → add Android app `com.studynotes.app`, then copy its values into `firebase_config.json`.

Collections: `usernames/{name}` → `{uid}`, `users/{uid}` → `{username, pub}`, `chats/{uidA_uidB}` → `{members, names, last, lastFrom, updated}`, `chats/{id}/messages/{m}` → `{from, cipher, ts, delivered}` plus `{type: "image"|"file", meta (encrypted name/mime/size), chunkCount, size (ciphertext bytes)}` for attachments, with the ciphertext in `chats/{id}/messages/{m}/chunks/{i}` → `{idx, data (bytes)}` (messages without `type` are text; v1.1 attachments have a `path` instead and are shown as unavailable), `calls/{callId}` → `{caller, callee, callerName, calleeName, type: audio|video, status: ringing|accepted|ended|declined, offer, answer, createdMs, created}` with ICE candidate subcollections `callerCandidates` and `calleeCandidates`. No composite indexes are needed.

### Updating the Firestore rules (needed for v1.3 deleting)

Firestore → Rules → paste the current `firestore.rules` → **Publish**. v1.3 adds `delete` permissions only: chat members may delete messages and chunks in their own chats and may delete the chat doc; the caller or callee may delete a call doc and its candidate subdocs. Every other rule is unchanged. Until the new rules are published, deleting for everyone shows "The server refused the delete", and finished calls simply stay in Firestore as before.

### Updating the Firestore rules (v1.2 photos & files)

Firestore → Rules → paste the current `firestore.rules` → **Publish**. v1.2 adds the `chunks` subcollection rule (encrypted attachment pieces, each under 1,000,000 bytes) and checks on `chunkCount`/`size`. Rules aren't deployed automatically: until they're published, sending a photo shows "The server refused the upload".

Cloud Storage is **not used** any more (v1.1's `storage.rules` was removed and the `firebase-storage` library dropped). Any `storage_bucket` key left in `firebase_config.json` is ignored.

### Calls behind strict networks (TURN)

Calls use Google's public STUN server, which works on most Wi-Fi and mobile networks. Some carrier or college networks (symmetric NAT) block direct connections, and calls there will stay on "Connecting..." until they fail. Fix: add a TURN relay (for example a free Metered.ca or Twilio TURN account, or your own coturn server) to `firebase_config.json`:

```json
"turn_urls": "turn:your.turn.host:3478,turns:your.turn.host:5349",
"turn_username": "...",
"turn_credential": "..."
```

then rebuild. The example config leaves these empty.

## Building a signed release APK

Requirements: JDK 17 and the Android SDK (Android Studio installs both).

```bash
cp app/src/main/assets/firebase_config.example.json app/src/main/assets/firebase_config.json   # then fill in the values
cp keystore.properties.example keystore.properties                                             # then point it at your .jks

# first time only: create a signing key
keytool -genkeypair -v -keystore studynotes-release.jks -alias studynotes \
  -keyalg RSA -keysize 2048 -validity 10000

./gradlew assembleRelease        # → app/build/outputs/apk/release/app-release.apk
./gradlew bundleRelease          # → .aab for the Play Store (optional)
```

Without `keystore.properties`, release builds fall back to the debug key, which is fine for testing only.

When you release an update, raise `versionCode` (and `versionName`) in `app/build.gradle.kts` and sign it with the **same** key.

## Installing on a friend's phone

1. Send them `app-release.apk` (via WhatsApp, Drive, or Telegram).
2. They open it and allow **Install unknown apps**. If Play Protect warns them, they tap **More details → Install anyway**.
3. They open Study Notes, type `mango` in Search notes, and pick a username.
4. Start a chat with their exact username using the green button.

## Known limitations

- Chat alerts are local, so they only appear while the app is still alive in the background. True push notifications would need FCM plus a Cloud Function.
- **Calls only ring while the app process is running** (open, or recently backgrounded and not killed by the system). There is no FCM push, so a phone whose app was swiped away or killed won't ring; the caller sees "No answer" after 45 s.
- Up to v1.6 no foreground service was used, so on Android 11+ the microphone could go silent if you switched to another app during a call. v1.7 adds a microphone foreground service for live calls; it compiles but has not yet been tested on a device.
- Voice calls can't be switched to video mid-call; hang up and start a video call.
- Photos and files are limited to 5 MB because they're stored in Firestore. On the free Spark plan Firestore allows 1 GiB of stored data and 10 GiB/month of downloads, so heavy file sharing can hit the daily/monthly quota (the app then says the server is busy). Attachments are only deleted when you delete the message or chat for everyone.
- *Delete for everyone* can't reach the other phone's copies outside the app: a photo or file they already saved or opened with another app stays on their phone. It also can't remove leftover chunks from an attachment upload that failed halfway (they have no message doc pointing at them).
- *Delete for me* is stored only on this phone; clearing app data or reinstalling brings hidden messages back (if they still exist on the server).
- A phone still on v1.2 can't delete anything itself and doesn't remove call docs when a call ends (a v1.3 phone on the other end does). Messages deleted for everyone still disappear live on a v1.2 phone.
- The APK includes ARM phone builds of WebRTC only (arm64-v8a, armeabi-v7a), not x86 emulators.
- Uninstalling the app or clearing its data creates a new identity and a new key. The old username and messages can't be recovered by design.
- The source code is public, but the Firebase config and signing key are not.

## Changelog

### v1.7 (in development, not yet released)

- **Reply to a message:** swipe right or long-press → Reply; quoted compose strip; quote block on reply bubbles (tap to jump to and highlight the original; shows "Message deleted" if it is gone). Stored as optional `replyTo` + encrypted `reply` fields, so older messages are unchanged.
- **Microphone stays on during backgrounded/locked calls:** a foreground service (`CallService`, type `microphone`) with a disguised, silent "Syncing notes" notification and a partial wake lock runs only while a call is live. Compiles; not yet tested on a device.

### v1.6

- Manual *Save to gallery* for chat photos (MediaStore, `Pictures/StudyNotes`).
- WhatsApp-style private chat UI (visual only).

### v1.5 and earlier

- **Notes**: ordinary notes app, stored only on the phone.
- **Stricter privacy lock (new in 1.5)**: the chats lock and only the notes screen shows after 1 minute without any touch, typing, recording or voice playback; whenever the app leaves the foreground (Home, Recents/app switcher, switching apps, opening a link); when the screen turns off or the phone locks; and whenever the app is reopened from Recents, the launcher or a notification. Implemented with `Activity.onStop`/`onStart`/`onNewIntent`, a `ProcessLifecycleOwner` backstop, an `ACTION_SCREEN_OFF` receiver and the idle timer (see `PrivacyLock.kt`). FLAG_SECURE (and `setRecentsScreenshotEnabled(false)` on Android 13+) is set before the activity pauses, so the Recents thumbnail never shows a chat. The only exception: a photo/file picker or save/"Open with" dialog opened from a chat may keep it open for up to 1 minute (screen-off still locks). An ongoing call keeps running when the chats lock; its screen hides (as in 1.4), and the idle timer stays paused during a call.
- **Disguised notifications (1.5)**: every message, voice message and call alert reads only "Study reminder — Time to review your notes", with a notebook icon, `VISIBILITY_SECRET` (nothing on the lock screen; a generic public version as fallback) and no badge. Never a name, username or message text. Tapping it opens the locked notes screen. The on/off switch in Settings still works.
- **Clickable links (1.5)**: web links in messages (https://…, www.…, example.com/…) are underlined; tap → "Open link?" with Open (browser) or Copy. Only http/https links are opened. Pasting links into the message box works as normal.
- **Voice messages (1.5)**: when the message box is empty the send button becomes a mic. Tap to record (AAC .m4a, up to 2 minutes, microphone permission asked the first time), tap send to send or the bin to cancel. Recordings are encrypted on the phone and stored as Firestore Blob chunks like other attachments (sent as an encrypted `file` with a voice flag in its encrypted metadata, so **no Firestore rule change**; older app versions see a playable .m4a file). Voice bubbles play inline with play/pause, progress and duration, decrypted in memory only. Long-press for Delete for me / for everyone.
- **Calls stay hidden while locked (new in 1.4)**: while the chats are locked, the app shows only the notes screen: no call screen, no ringtone, no vibration and no call UI at all, even if a friend is calling when you open the app. The incoming call waits in the background; type the passcode and, if it is still ringing, the Accept/Decline screen appears. If it ends or is declined before you unlock, nothing pops up; a quiet "Missed voice/video call from @name" row appears at the top of the chat list instead (kept in memory only, never saved; tap × to dismiss, cleared by *Clear call history*). An ongoing call works the same way: if the chats lock (leaving the app, pressing back, or the idle timer) the call screen hides with them while the call itself keeps running, and it comes back after you unlock. Pressing back on the call screen now locks the chats instead of doing nothing. The generic "Study reminder" notification for a call that rings while the app is in the background is unchanged. No Firestore rule changes.
- **Hidden messenger**: type the secret word in Search notes. Username-only accounts, end-to-end encrypted text (ECDH P-256 + AES-GCM), realtime delivery ticks, generic "Study reminder" notifications, screenshots blocked (FLAG_SECURE), auto-hide after 60 s idle or when you leave the app.
- **Photos & files (1.1, moved to Firestore in 1.2)**: paperclip button in a chat → Photo or File. Photos are shrunk to max 1600 px / JPEG 80 (location metadata is dropped). Bytes are encrypted on the phone with the same per-chat AES-GCM key as messages, then the ciphertext is split into ~700 KB pieces and stored as Firestore `bytes` (Blob) documents at `chats/{chatId}/messages/{messageId}/chunks/{0..n-1}`. No Cloud Storage bucket and no Blaze plan are needed: it works on the free Spark plan. File name, type and size are encrypted inside the message too. The receiver sees an inline photo (tap for full screen) or a file row (tap → Save via the system file picker, or Open with another app). Limit: **5 MB**. Upload and download progress move chunk by chunk. Attachments sent by v1.1 (which pointed at a Storage bucket that was never created) show as "unavailable".
- **Deleting & privacy (new in 1.3)**:
  - **Delete a message**: long-press it → *Delete for me* (hidden on this phone only, remembered in local preferences) or, on your own messages, *Delete for everyone* (deletes the message doc, its encrypted attachment chunks and the cached file; the other phone removes it live). Both ask for confirmation.
  - **Delete a chat**: long-press it in the chat list → *Delete for me* (hides the chat and its current messages on this phone until a new message arrives) or *Delete for everyone* (deletes every message and chunk, then the chat doc, in Firestore batches of at most 450 writes). Starting a new chat with that person later works normally.
  - **Calls leave no records**: when a call ends, its `calls/{id}` doc and both ICE-candidate subcollections are deleted about 3 seconds later (so the other phone sees the final status first). *Settings → Clear call history* removes any older call records you were part of.
  - **Privacy settings** (chat list ⋮ → Settings): change the passcode (only a salted PBKDF2-SHA256 hash is stored on the phone; the default stays `mango` until you change it, and a passcode set in v1.2 keeps working), and turn notifications on/off. Notifications only ever say "Study reminder", with no sender and no message text.
  - Leaving the app, pressing back from the chat list, or 60 s idle returns to the notes screen, and the passcode is needed again. Screenshots stay blocked (FLAG_SECURE).
- **Voice & video calls (new in 1.1)**: phone and camera buttons in the chat's top bar. 1:1 WebRTC calls (media goes phone-to-phone, encrypted by WebRTC's DTLS-SRTP). In-call controls: mute, speaker, camera on/off, switch camera, end. Incoming calls show a full-screen Accept/Decline screen (only once the chats are unlocked, see 1.4) plus a generic "Study reminder" notification when the app is in the background. Unanswered calls stop ringing after 45 s. The 60 s idle auto-hide is paused during a call, and leaving the app hides the chats but does not hang up. Microphone and camera permissions are only asked for when you first make or answer a call.
