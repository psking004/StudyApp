# Study Notes

A normal-looking study notes app for Android. Typing the secret word (default `mango`) in **Search notes** opens a private, end-to-end encrypted messenger. Accounts use a username only, with no phone number or email.

## Features (v1.2)

- **Notes**: ordinary notes app, stored only on the phone.
- **Hidden messenger**: type the secret word in Search notes. Username-only accounts, end-to-end encrypted text (ECDH P-256 + AES-GCM), realtime delivery ticks, disguised "study reminder" notifications, screenshots blocked (FLAG_SECURE), auto-hide after 60 s idle or when you leave the app.
- **Photos & files (1.1, moved to Firestore in 1.2)**: paperclip button in a chat → Photo or File. Photos are shrunk to max 1600 px / JPEG 80 (location metadata is dropped). Bytes are encrypted on the phone with the same per-chat AES-GCM key as messages, then the ciphertext is split into ~700 KB pieces and stored as Firestore `bytes` (Blob) documents at `chats/{chatId}/messages/{messageId}/chunks/{0..n-1}`. No Cloud Storage bucket and no Blaze plan are needed: it works on the free Spark plan. File name, type and size are encrypted inside the message too. The receiver sees an inline photo (tap for full screen) or a file row (tap → Save via the system file picker, or Open with another app). Limit: **5 MB**. Upload and download progress move chunk by chunk. Attachments sent by v1.1 (which pointed at a Storage bucket that was never created) show as "unavailable".
- **Voice & video calls (new in 1.1)**: phone and camera buttons in the chat's top bar. 1:1 WebRTC calls (media goes phone-to-phone, encrypted by WebRTC's DTLS-SRTP). In-call controls: mute, speaker, camera on/off, switch camera, end. Incoming calls show a full-screen Accept/Decline screen plus a disguised reminder notification. Unanswered calls stop ringing after 45 s. The 60 s idle auto-hide is paused during a call, and leaving the app hides the chats but does not hang up. Microphone and camera permissions are only asked for when you first make or answer a call.

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

### Updating the Firestore rules (needed for v1.2 photos & files)

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
- No foreground service is used, so on Android 11+ the microphone/camera can go silent if you switch to another app during a call. Stay in the app while talking.
- Voice calls can't be switched to video mid-call; hang up and start a video call.
- Photos and files are limited to 5 MB because they're stored in Firestore. On the free Spark plan Firestore allows 1 GiB of stored data and 10 GiB/month of downloads, so heavy file sharing can hit the daily/monthly quota (the app then says the server is busy). Attachments are never deleted automatically.
- The APK includes ARM phone builds of WebRTC only (arm64-v8a, armeabi-v7a), not x86 emulators.
- Uninstalling the app or clearing its data creates a new identity and a new key. The old username and messages can't be recovered by design.
- The source code is public, but the Firebase config and signing key are not.
