# CLOUDSYNC.md — CloudDream Cloud Synchronization

Status: **Phase 2A — Firebase foundation, configuration, and the authentication
service layer. No account UI yet.**

This document describes only what is implemented today. The sign-in and
registration **screens** do not exist yet, and Firestore synchronization,
bookmarks/progress/history sync, and device management are **not implemented
yet** and are intentionally not documented here as if they existed.

---

## 1. What is implemented today

- Firebase BoM and `firebase-auth` (BoM-managed) added to the `app` module.
  `firebase-firestore` is intentionally **not** added at this stage; see section 7.
- A CloudDream configuration component that reads Firebase settings at build
  time from environment variables or `local.properties`.
- Programmatic `FirebaseApp` initialization from `CloudDreamConfig`, done
  through `CloudDream.init(context)` in `CloudStreamApp.onCreate()`.
- A small logging helper (`CloudDreamLog`, logcat tag `CloudSync`) that only
  logs in debug builds.
- **Phase 2A:** a coroutine-friendly authentication service
  (`CloudDreamAuth`) that exposes the current user, sign-in with
  email/password, account creation, sign-out, a session listener, and a small
  error mapping. It is opt-in and never called during startup.

That is all. There are no Firestore reads/writes, no sync engine, no account
UI, and no changes to CloudStream storage, bookmarks, player, or extensions.
CloudStream stays fully usable with no account and when Firebase is
unconfigured.

### Using the authentication service

```kotlin
when (val result = CloudDreamAuth.signInWithEmailAndPassword(email, password)) {
    is CloudDreamAuthResult.Success -> { /* result.user is signed in */ }
    is CloudDreamAuthResult.Failure -> { /* map result.error to a message */ }
}
```

`CloudDreamAuth.isAvailable` is false when Firebase is not configured, and every
call then returns `Failure(NOT_CONFIGURED)` instead of throwing. Passwords are
passed straight to Firebase and are never stored, cached or logged by
CloudDream. Email/password sign-in must be enabled in the Firebase console
(Authentication → Sign-in method); until then Firebase answers
`OPERATION_NOT_ALLOWED`.

---

## 2. Why google-services.json and the Google Services Gradle plugin are NOT used

The official setup normally requires:

1. the `com.google.gms.google-services` Gradle plugin, and
2. an `app/google-services.json` file downloaded from the Firebase Console.

CloudDream intentionally uses **programmatic initialization with
`FirebaseOptions`** instead, because:

- The build command `.\gradlew.bat assembleDebug` must keep working on any
  machine (CI, fresh clone, other contributors) **without** Firebase being
  configured. The Google Services plugin hard-fails the build when
  `google-services.json` is missing.
- No Firebase configuration file or credential needs to be committed to the
  repository, keeping secrets out of Git entirely.
- This project uses AGP 9.1.1 / Kotlin 2.4.0; avoiding the plugin removes an
  extra compatibility surface.

Firebase is therefore **optional**: if configuration is absent, `CloudDream.init`
logs a debug message and does nothing. CloudStream works exactly as before —
no account, no network dependency, no crash.

---

## 3. Required configuration (Stage 1)

Values can come from **environment variables** (higher precedence) or from
**`local.properties`** (ignored by Git) using the same names:

| Property / environment variable | Required | Purpose |
|---|---|---|
| `CLOUDDREAM_FIREBASE_API_KEY` | Yes | Firebase Web/API key from Firebase Console |
| `CLOUDDREAM_FIREBASE_APP_ID` | Yes | Firebase Android app ID (`mobilesdkAppId`) |
| `CLOUDDREAM_FIREBASE_PROJECT_ID` | Yes | Firebase project ID |
| `CLOUDDREAM_FIREBASE_STORAGE_BUCKET` | No | Storage bucket (`bucketName`) — not used yet |
| `CLOUDDREAM_FIREBASE_MESSAGING_SENDER_ID` | No | Cloud Messaging sender ID — not used yet |

The first three must all be present for `CloudDreamConfig.isConfigured` to be
true (and for Firebase to initialize). Empty/missing values simply disable all
cloud features.

### Where to obtain the values

1. Create (or open) a project in the [Firebase Console](https://console.firebase.google.com/).
2. Register an Android app; download or view its configuration.
3. Copy the API key, app ID, and project ID into `local.properties`, e.g.:

```properties
sdk.dir=...
CLOUDDREAM_FIREBASE_API_KEY=AIza...
CLOUDDREAM_FIREBASE_APP_ID=1:1234567890:android:abcdef
CLOUDDREAM_FIREBASE_PROJECT_ID=my-project
```

**Never commit these values.** They are read into `BuildConfig` fields the
same way CloudStream already handles `SIMKL_CLIENT_ID`, `MAL_KEY`, and
`ANILIST_KEY` (see `app/build.gradle.kts`). `local.properties` is already
gitignored; no `.gitignore` changes were required for Stage 1.

### Note on application IDs

CloudStream builds multiple application IDs (`applicationIdSuffix` for
debug/prerelease). When you do register the Android app in the Firebase
Console, register **one Firebase Android app per applicationId** you intend to
use (e.g. `com.lagradost.cloudstream3` and `com.lagradost.cloudstream3.debug`)
and pass the matching values per build if you use different configs. This is
a Console-side setup task; Stage 1 does not automate it.

---

## 4. Files involved

| File | Role |
|---|---|
| `gradle/libs.versions.toml` | Firebase BoM + auth library declarations |
| `app/build.gradle.kts` | Dependencies and the five `BuildConfig` fields |
| `app/src/main/java/com/lagradost/clouddream/CloudDreamConfig.kt` | Exposes configuration; `isConfigured` |
| `app/src/main/java/com/lagradost/clouddream/CloudDream.kt` | Programmatic, crash-safe `FirebaseApp` init |
| `app/src/main/java/com/lagradost/clouddream/CloudDreamLog.kt` | Debug-only logging (tag `CloudSync`) |
| `app/src/main/java/com/lagradost/clouddream/auth/CloudDreamAuth.kt` | Auth service: current user, sign in, create account, sign out, session listener |
| `app/src/main/java/com/lagradost/clouddream/auth/CloudDreamAuthError.kt` | Lightweight mapping of Firebase Auth failures |
| `app/src/main/java/com/lagradost/clouddream/auth/CloudDreamUser.kt` | In-memory user snapshot (no tokens, nothing persisted) |
| `app/src/main/java/com/lagradost/cloudstream3/CloudStreamApp.kt` | One guarded `CloudDream.init(this)` call |

---

## 5. Verify the build

- Build with **no** CloudDream Firebase configuration present:

```powershell
.\gradlew.bat assembleDebug
```

The build must succeed. In logcat (debug builds) you should see a debug line
from `CloudSync` stating that Firebase configuration is absent.

- Build with configuration present in `local.properties`: the same command
  succeeds and debug logcat shows `CloudSync: Firebase initialized`.

---

## 6. Security notes

- No API keys, tokens, passwords, or service-account files are stored in this
  repository.
- Configuration lives only in `local.properties` / environment variables
  (build-time injection) or in the Firebase Console.
- Stage 1 introduces no Firestore data and therefore no security rules yet;
  Firestore security rules will be added together with the sync stages.
- CloudDream never stores a password, an ID token or a refresh token locally.
  The session lives in the Firebase SDK's own storage; `CloudDreamUser` is a
  read-only in-memory snapshot.

---

## 7. Planned later stages (not implemented)

The sign-in and registration **UI**, Firestore data model and security rules,
the sync engine, conflict resolution, offline queueing, and device
registration are all future stages. This file will be extended as each stage
lands.

### Adding `firebase-firestore` later — known dependency conflict

`firebase-firestore` is not in Stage 1 because it cannot currently be added
alongside a modern `protobuf-javalite`. `firebase-firestore` depends on
`com.google.firebase:protolite-well-known-types`, which bundles its own copy of
the `com.google.protobuf.DescriptorProtos` schema classes. `protobuf-javalite`
gained the same classes in 4.27, so from 4.27 onward the two artifacts overlap
and the build fails in `check<Variant>DuplicateClasses` with ~100 duplicate
`DescriptorProtos*` errors.

Before adding it:

1. Identify which dependency requests `protobuf-javalite:4.x` — it is not
   anything in the Firebase graph (`protolite-well-known-types` and
   `grpc-protobuf-lite` both declare 3.25.x):

   ```powershell
   .\gradlew.bat :app:dependencies --configuration prereleaseDebugRuntimeClasspath
   ```

2. Apply one of the known remedies:
   - **Preferred:** force `com.google.protobuf:protobuf-javalite` to `3.25.5`,
     the version Firebase itself uses. It is the CVE-2024-7254 fix and predates
     `DescriptorProtos`, so the duplicate disappears. Only safe once step 1
     confirms the `4.x` requester does not need 4.x APIs.
   - **Last resort:** exclude `com.google.firebase:protolite-well-known-types`
     from `firebase-firestore`. This passes the build but removes the
     `com.google.api`/`com.google.rpc`/`com.google.type` classes Firestore's
     generated protos need, which surfaces as a runtime failure rather than a
     build failure.

Firestore is also unverified on `minSdk 23`; confirm it before committing to
the sync stages.
