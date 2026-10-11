# Study Notes

A normal-looking study notes app for Android. Typing the secret word (default `mango`) in **Search notes** opens a private, end-to-end encrypted messenger. Accounts use a username only, with no phone number or email.

## Features (v1.1)

- **Notes**: ordinary notes app, stored only on the phone.
- **Hidden messenger**: type the secret word in Search notes. Username-only accounts, end-to-end encrypted text (ECDH P-256 + AES-GCM), realtime delivery ticks, disguised "study reminder" notifications, screenshots blocked (FLAG_SECURE), auto-hide after 60 s idle or when you leave the app.
- **Photos & files (new in 1.1)**: paperclip button in a chat → Photo or File. Photos are shrunk to max 1600 px / JPEG 80 (location metadata is dropped). Bytes are encrypted on the phone with the same per-chat AES-GCM key as messages, and only ciphertext is uploaded to Cloud Storage at `chats/{chatId}/{messageId}`. File name, type and size are encrypted inside the message too. The receiver sees an inline photo (tap for full screen) or a file row (tap → Save via the system file picker, or Open with another app). Limit: 25 MB. Upload progress is shown in the chat.
- **Voice & video calls (new in 1.1)**: phone and camera buttons in the chat's top bar. 1:1 WebRTC calls (media goes phone-to-phone, encrypted by WebRTC's DTLS-SRTP). In-call controls: mute, speaker, camera on/off, switch camera, end. Incoming calls show a full-screen Accept/Decline screen plus a disguised reminder notification. Unanswered calls stop ringing after 45 s. The 60 s idle auto-hide is paused during a call, and leaving the app hides the chats but does not hang up. Microphone and camera permissions are only asked for when you first make or answer a call.

## Production architecture

```
Android app (APK)  ──HTTPS──▶  Firebase (Google cloud, always on)
  • Notes: stored only on the phone        • Anonymous Auth (one identity per install)
  • Chats: ECDH P-256 + AES-GCM, encrypted  • Cloud Firestore (asia-south1): usernames,
    on the phone before they're sent           public keys, encrypted messages, call signaling
  • Photos/files: encrypted on the phone   • Cloud Storage: encrypted attachment blobs only
  • Calls: WebRTC peer-to-peer ◀──────────▶ (STUN: Google; optional TURN relay)
```

There is no custom backend, no localhost, and no server on your computer. The app talks straight to Firebase over HTTPS, so it works on any Wi-Fi or mobile network. Firestore security rules (`firestore.rules`) are what protect the data on the server.

## Configuration (the Android version of env variables)

| File | Committed? | What it holds |
|---|---|---|
| `app/src/main/assets/firebase_config.json` | **No** (gitignored) | Firebase `apiKey`, `appId`, `projectId`, `messagingSenderId`, `storage_bucket` (optional, defaults to `<projectId>.firebasestorage.app`), and optional TURN keys `turn_urls` (comma-separated or JSON array), `turn_username`, `turn_credential`. These are public client identifiers that ship inside every Firebase app; Firestore rules are what keep the data safe. They stay out of the public repo so GitHub's secret scanner doesn't flag them. |
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

Collections: `usernames/{name}` → `{uid}`, `users/{uid}` → `{username, pub}`, `chats/{uidA_uidB}` → `{members, names, last, lastFrom, updated}`, `chats/{id}/messages/{m}` → `{from, cipher, ts, delivered}` plus `{type: "image"|"file", meta (encrypted name/mime/size), path}` for attachments (messages without `type` are text), `calls/{callId}` → `{caller, callee, callerName, calleeName, type: audio|video, status: ringing|accepted|ended|declined, offer, answer, createdMs, created}` with ICE candidate subcollections `callerCandidates` and `calleeCandidates`. No composite indexes are needed.

### Cloud Storage setup (needed for photos & files, v1.1)

1. Upgrade the project to the **Blaze** plan (Cloud Storage for new projects requires it; small usage stays in the free tier).
2. Build → Storage → **Get started** → create the default bucket `study-notes-app-861b9.firebasestorage.app`.
3. Storage → Rules → paste `storage.rules` → Publish.
4. Firestore → Rules → paste the updated `firestore.rules` (adds `calls` and attachment fields) → Publish.

Neither rules file is deployed automatically. Without step 4, calls fail with "Access was refused". Without steps 2-3, sending a photo shows "File sharing isn't set up on the server yet."

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
- No foreground service is used, so on Android 11+ the microphone/camera can go silent if you switch to another app during a call. Stay in the app while talking.
- Voice calls can't be switched to video mid-call; hang up and start a video call.
- The APK includes ARM phone builds of WebRTC only (arm64-v8a, armeabi-v7a), not x86 emulators.
- Uninstalling the app or clearing its data creates a new identity and a new key. The old username and messages can't be recovered by design.
- The source code is public, but the Firebase config and signing key are not.
