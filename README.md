# Study Notes

A normal-looking study notes app for Android. Typing the secret word (default `mango`) in **Search notes** opens a private, end-to-end encrypted messenger. Accounts use a username only, with no phone number or email.

## Production architecture

```
Android app (APK)  ──HTTPS──▶  Firebase (Google cloud, always on)
  • Notes: stored only on the phone        • Anonymous Auth (one identity per install)
  • Chats: ECDH P-256 + AES-GCM, encrypted  • Cloud Firestore (asia-south1): usernames,
    on the phone before they're sent           public keys, encrypted messages
```

There is no custom backend, no localhost, and no server on your computer. The app talks straight to Firebase over HTTPS, so it works on any Wi-Fi or mobile network. Firestore security rules (`firestore.rules`) are what protect the data on the server.

## Configuration (the Android version of env variables)

| File | Committed? | What it holds |
|---|---|---|
| `app/src/main/assets/firebase_config.json` | **No** (gitignored) | Firebase `apiKey`, `appId`, `projectId`, `messagingSenderId`. These are public client identifiers that ship inside every Firebase app; Firestore rules are what keep the data safe. They stay out of the public repo so GitHub's secret scanner doesn't flag them. |
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

Collections: `usernames/{name}` → `{uid}`, `users/{uid}` → `{username, pub}`, `chats/{uidA_uidB}` → `{members, names, last, lastFrom, updated}`, `chats/{id}/messages/{m}` → `{from, iv, ct, ts, delivered}`. No composite indexes are needed.

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

- Chat alerts are local, so they only appear while the app is still alive in the background. True push notifications would need FCM plus a Cloud Function, which requires the paid Blaze plan.
- Uninstalling the app or clearing its data creates a new identity and a new key. The old username and messages can't be recovered by design.
- The source code is public, but the Firebase config and signing key are not.
