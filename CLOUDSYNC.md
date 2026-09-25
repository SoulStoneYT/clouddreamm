# CLOUDSYNC.md — CloudDream Cloud Synchronization

Status: **Phase 2A, 2B and 2C complete — Firebase foundation, a working account UI
(sign in, create account, sign out), and the cloud-sync foundation: Firestore is
wired up and the protobuf conflict is resolved. Nothing reads or writes cloud
data yet.**

This document describes only what is implemented today. The account UI exists
and works, but it only establishes an identity. The sync **foundation** exists —
Firestore is a dependency, the schema and the media identity are defined, and
the sync manager can read and write records — but **nothing in the app calls it
yet**. There is no automatic sync, no playback integration, no conflict
resolution and no device management. CloudStream's own storage is still the only
thing the app uses.

---

## 1. What is implemented today

- Firebase BoM and `firebase-auth` (BoM-managed) added to the `app` module.
  `firebase-firestore` was added in Phase 2C; see section 7.
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
- **Phase 2B:** a new top-level **Cloud** section in the settings hub, backed by
  `CloudDreamCloudScreen`, that is the complete account entry point: a status
  row plus a signed-out credential form (email, password, show/hide toggle,
  **Sign In**, **Create Account**) and a signed-in state with **Sign Out**. The
  session is observed through `CloudDreamAuth.addUserStateListener`, so the UI
  follows the session automatically instead of polling.
- **Phase 2C:** `firebase-firestore` is now a dependency (see section 7 for the
  protobuf conflict that had to be solved first), and a new
  `com.lagradost.clouddream.sync` package provides the sync **foundation**:
  `CloudDreamMediaKey` (the stable cross-device media identity), the
  progress/bookmark/history record models with conflict metadata, the device
  identity (`CloudDreamDeviceId`), the `CloudDreamSyncManager` interface, and
  `FirestoreCloudDreamSyncManager` as its only implementation. The schema is
  documented in section 7.

That is all. **No screen, no player and no storage layer calls the sync
manager.** There is no sync engine, no automatic trigger and no conflict
resolution, and there are no changes to CloudStream storage, bookmarks, player,
or extensions. CloudStream stays fully usable with no account, when Firebase is
unconfigured, and when Firestore is unreachable.

### The Cloud settings section

Settings → **Cloud** has three mutually exclusive states.

**Firebase not configured** — a single read-only row reading "Cloud features are
not available in this build". Nothing is clickable and nothing throws.

**Signed out** — a status row ("Not signed in") above a form containing an email
field, a password field with a visibility toggle, a **Sign In** button and a
**Create Account** button.

**Signed in** — a status row showing the account's email (or its uid if it has
none) and a **Sign Out** row.

While a Firebase Auth call is in flight, a progress bar appears, both buttons and
both fields are disabled, and the primary button's label changes to
"Signing in…", "Creating account…" or "Signing out…" so it is clear which
operation is running. Obvious input mistakes (blank email, an email with no
`@` or no dotted domain, blank password, a password under Firebase's 6-character
minimum when creating an account) are rejected locally so they never cost a
network round trip; Firebase remains the authority for everything else.

Failures are shown inline under the form, and sign-out failures are shown as a
toast, both mapped to localized strings by `CloudDreamAuthError.toMessageRes()`.
The `message` field on `CloudDreamAuthError` is deliberately *not* shown to the
user; it is a developer/log-facing description.

**Credential handling.** The password is held in a plain `remember`, never
`rememberSaveable`, so it is never written into a saved-instance-state bundle. It
is cleared as soon as an operation succeeds and again when the form leaves
composition. CloudDream stores no credential of its own, logs nothing containing
a credential, and there is no local token cache.

This section is deliberately separate from CloudStream's own "Accounts and
Security" section, which is about third-party sync providers (MyAnimeList,
Kitsu, AniList, Simkl, …) and uses a different account model. A CloudDream
account is a different kind of identity, so it gets its own hub entry.

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
| `app/src/main/java/com/lagradost/clouddream/ui/CloudDreamCloudScreen.kt` | Phase 2B: the Cloud settings screen — unavailable / signed-out / signed-in states |
| `app/src/main/java/com/lagradost/clouddream/ui/CloudDreamSignInForm.kt` | The credential form: email, password, visibility toggle, buttons, progress, inline error |
| `app/src/main/java/com/lagradost/clouddream/auth/CloudDreamAuthErrorMessages.kt` | `CloudDreamAuthError` → localized string resource |
| `app/src/main/java/com/lagradost/clouddream/ui/CloudDreamCloudSettingsFragment.kt` | Glue binding the navigation destination to `CloudDreamCloudScreen` |
| `app/src/main/res/navigation/mobile_navigation.xml` | `navigation_settings_cloud` destination + its global action |
| `app/src/main/java/com/lagradost/cloudstream3/ui/settings/SettingsFragmentScreen.kt` | The "Cloud" hub tile (the only settings file CloudDream edits) |
| `app/src/main/java/com/lagradost/clouddream/sync/CloudDreamMediaKey.kt` | Phase 2C: stable cross-device media identity; produces the Firestore document id |
| `app/src/main/java/com/lagradost/clouddream/sync/CloudDreamRecords.kt` | Progress / bookmark / history records + shared conflict metadata |
| `app/src/main/java/com/lagradost/clouddream/sync/CloudDreamSyncState.kt` | Availability, skip reasons, and the `Success`/`Skipped`/`Failure` result type |
| `app/src/main/java/com/lagradost/clouddream/sync/CloudDreamDeviceId.kt` | Phase 2C: stable per-installation device id, no hardware identifiers |
| `app/src/main/java/com/lagradost/clouddream/sync/CloudDreamSyncManager.kt` | The backend-agnostic sync interface (no Firestore type appears in it) |
| `app/src/main/java/com/lagradost/clouddream/sync/FirestoreCloudDreamSyncManager.kt` | The only file that knows Firestore exists |
| `app/src/main/java/com/lagradost/clouddream/sync/CloudDreamFirestoreSchema.kt` | Collection and field names, in one reviewable place |
| `app/src/test/java/com/lagradost/clouddream/sync/CloudDreamMediaKeyTest.kt` | Locks down determinism and non-collision of the media identity |

### Note on which settings file to edit

CloudStream is mid-migration from XML/Preference settings to Compose. The **live**
hub is the Compose `SettingsFragment2` → `SettingsFragmentScreen`; the older
`SettingsFragment` + `res/layout/main_settings.xml` pair is dead legacy code that is
no longer referenced by `mobile_navigation.xml`. CloudDream therefore only touches
the Compose hub. If you are adding a settings row, add it to a `*Screen` object.

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

- On device, open Settings and confirm a **Cloud** tile exists at the bottom of
  the hub, after **Extensions**. Tapping it opens a "CloudDream account" group.
  Without configuration it must read "Cloud features are not available in this
  build" and must not crash.
- With configuration present, that screen must show the form when signed out,
  accept an email and password, show a progress state during the call, and land
  on the "Signed in as" state with the correct address afterwards. **Sign Out**
  must return it to the form.
- Signing in with a wrong password must show a localized message and must not
  leave the button stuck in its loading state.

### Verifying the sync foundation (Phase 2C)

```powershell
# Compile and package both debug variants.
.\gradlew.bat :app:assembleStableDebug :app:assemblePrereleaseDebug

# The protobuf/protolite duplicate-class regression check. This is the task that
# failed before the resolution in section 7 was applied.
.\gradlew.bat :app:checkStableDebugDuplicateClasses :app:checkPrereleaseDebugDuplicateClasses

# The media identity contract.
.\gradlew.bat :app:testStableDebugUnitTest --tests "com.lagradost.clouddream.sync.CloudDreamMediaKeyTest"

# Confirm the resolved graph.
.\gradlew.bat :app:dependencies --configuration stableDebugRuntimeClasspath
```

The last command must show `firebase-firestore -> 26.6.0` **and**
`protobuf-javalite:4.35.0 -> 3.25.5` on the NewPipeExtractor line. If
`protobuf-javalite` ever resolves back to 4.x, the duplicate-class failure has
returned.

There is deliberately no on-device check for sync in this phase, because nothing
in the app calls the sync manager yet.

### 5.1 Firestore verification record

Firestore was verified end-to-end on a real device during Phase 2C, using a
throwaway probe that has since been deleted. The probe confirmed that the
signed-in account is visible to CloudDream, a document can be written under
`users/{uid}/` and read back, and the Phase 2C record mapping round-trips
through the real `FirestoreCloudDreamSyncManager`. It deleted everything it
created, so no test data remains.

Result on a OnePlus CPH2491, **API 36** — every step passed, twice, with no
exceptions:

```
OK   AUTH   -  (signed in, uid length 28)
OK   WRITE  users/<uid>/_clouddream_probe/probe
OK   READ   users/<uid>/_clouddream_probe/probe  (marker matched)
OK   MGR_WRITE  users/<uid>/bookmarks/<documentId>
OK   MGR_READ   users/<uid>/bookmarks/<documentId>  (record mapped back)
OK   MGR_DELETE users/<uid>/bookmarks/<documentId>  (confirmed absent=true)
OK   DELETE users/<uid>/_clouddream_probe/probe     (confirmed absent=true)
```

The device id was also observed to survive a process restart, since a second run
against the same install did not regenerate it.

**This was verified on API 36, not on `minSdk 23`.** The Firestore SDK supports
API 23, but an API 23 device or emulator should still be exercised before the
sync stages are built on top of this.

---

## 6. Security notes

- No API keys, tokens, passwords, or service-account files are stored in this
  repository.
- Configuration lives only in `local.properties` / environment variables
  (build-time injection) or in the Firebase Console.
- The Firestore security rules are published in the Firebase Console and are
  **not** kept in this repository. They permit a client to read and write only
  under `users/{its own auth uid}`, which is exactly the subtree the sync manager
  writes to.
- CloudDream never stores a password, an ID token or a refresh token locally.
  The session lives in the Firebase SDK's own storage; `CloudDreamUser` is a
  read-only in-memory snapshot. The sync layer stores no token either — the
  account is identified by the uid it gets from the Firebase Auth SDK.

---

## 7. Cloud sync architecture (Phase 2C)

### 7.1 Firestore schema

```
users/{uid}                          profile: createdAt, lastSeenAt, schemaVersion
users/{uid}/progress/{documentId}    one per playable item (an episode, or the title itself)
users/{uid}/bookmarks/{documentId}   one per title
users/{uid}/history/{documentId}     one per title (continue watching)
users/{uid}/devices/{deviceId}       reserved for a later stage; not written yet
```

`uid` is the Firebase Auth `FirebaseUser.uid`, so ownership matches the published
security rules exactly: a client may only touch `users/{its own uid}`. Nothing
CloudDream writes ever lands outside that subtree, and the manager refuses to run
at all without a signed-in uid.

The split between the three collections mirrors CloudStream's own storage, which
keys progress at episode scope but bookmarks and history at title scope:

| Cloud record | Local counterpart | Local key |
|---|---|---|
| `progress` | `PosDur` | `video_pos_dur/{episodeId}` |
| `bookmarks` | `BookmarkedData` | `result_watch_state_data/{id}` |
| `history` | `ResumeWatching` | `result_resume_watching_2/{parentId}` |

Every record document carries `updatedAt` and `deviceId` alongside its payload,
so a conflict-resolution engine can be added later **without** a migration or a
full re-upload. `schema` (currently `1`) is on every record for the same reason.
No conflict engine is implemented.

Two deliberate omissions, both mirroring local behaviour:

- **History stores no playback position.** Locally the home screen reads it from
  `video_pos_dur/{episodeId}` at display time; a second copy in `history` would
  create a second source of truth that can disagree.
- **Progress is not subject to the local 30-second floor.** `DataStoreHelper.setViewPos`
  silently drops anything under 30 seconds; the cloud record does not re-impose
  that, so a cloud record may hold a short item the local store would not.

### 7.2 Media identity decision

CloudStream keys everything locally against `SearchResponse.id: Int?`. **That
value cannot be used as a cloud identity**, and `CloudDreamMediaKey` exists
because of it:

- The id is `ResultViewModel2.getLoadResponseIdFromUrl`'s output —
  `uniqueUrl.replace(providerMainUrl, "").replace("/", "").hashCode()`. It is
  scoped to one provider, is a 32-bit signed `Int` (so it can be negative, and
  can collide), and changes if the provider's `mainUrl` or URL format changes.
  CloudStream's own `result_resume_watching` → `result_resume_watching_2`
  migration exists precisely because those ids moved once already.
- It is usually `null` on a search result, because the library only computes it
  in `LoadResponse.getId()` once a title has been opened.

So the cloud key is built from the underlying fields instead:

| Field | Why it is in the identity |
|---|---|
| `apiName` | The provider. CloudStream's own id hash folds in the provider's `mainUrl`, so the same title on two providers is genuinely two items. |
| `type` | `TvType`, stored by **name** and never by ordinal — the local enum's ordinals are not a stable wire format (matching `DataStoreHelper.serializeTv`). |
| `uniqueUrl` | Documented in the library as "the key used for storing the persistent data about an entry", introduced specifically to survive URL format changes. Strongest stable signal available. |
| `year` | CloudStream's own `checkAndWarnDuplicates` treats a matching name plus year as a duplicate, so year is a real disambiguator for remakes. Nullable — not every provider returns it. |
| `season`, `episode` | Present only for episode-scoped `progress` documents. |

`name` is deliberately **excluded**: it is display data, is not unique (which is
why CloudStream scans every bookmark looking for duplicates), and changes with
localisation and provider metadata updates. The local integer `id` is stored in
the document as a debugging aid only and is never part of the identity.

The document id is the first 128 bits of the SHA-256 digest of a **length-prefixed**
canonical encoding of those fields. Length prefixing is what makes it
unambiguous: joining naively, `apiName="A|B"` and `apiName="A", type="B"` would
produce the same string. A digest is used rather than a readable slug because
`uniqueUrl` contains `/` and `:` and can exceed the document-id length limit.
`CloudDreamMediaKeyTest` locks all of this down.

### 7.3 Device identity

`CloudDreamDeviceId` is a random UUID generated on first use and then stored in a
CloudDream-owned `SharedPreferences` file named `clouddream_device`. Nothing is
derived from the device.

The obvious Android candidates were rejected deliberately: `ANDROID_ID` is a
device identifier that changes on factory reset or a reinstall signed by a
different key; IMEI and `Build.SERIAL` need privileged or dangerous permissions;
a MAC address needs `ACCESS_WIFI_STATE`. All of them are hardware identifiers.
The one drawback of a random UUID — it is lost on reinstall — is correct
behaviour, because a reinstall is a new installation and treating it as a new
device stops two unrelated installs from inheriting each other's sync metadata.

The value is kept in its own file rather than in CloudStream's
`rebuild_preference` datastore or the default `SharedPreferences`, because
`BackupUtils` sweeps both of those up. Restoring a device id onto a second device
would make two devices indistinguishable in sync metadata; a private file is
outside that surface entirely, and it also keeps CloudDream out of CloudStream's
key namespace.

`FirestoreCloudDreamSyncManager.newMeta()` is the only place the device id is
resolved, so a caller cannot forget to stamp it.

### 7.4 Dependency decision

Added, both BoM-managed and declared without their own version, matching how
`firebase-auth` is declared:

```toml
firebase-firestore = { module = "com.google.firebase:firebase-firestore" }
```

```kotlin
implementation(platform(libs.firebase.bom))   // 34.19.0
implementation(libs.firebase.auth)           // -> 24.2.0
implementation(libs.firebase.firestore)      // -> 26.6.0
```

No other dependency was added, upgraded or removed. `firebase-auth` is
untouched, and no Firestore KTX module was added: the plain Java API is used
through the existing `Task.await()` helper, so there was no need.

### 7.5 Protobuf conflict — what it was and how it was resolved

`firebase-firestore` depends on `com.google.firebase:protolite-well-known-types`,
which bundles its own copy of the `com.google.protobuf.DescriptorProtos` schema
classes. `protobuf-javalite` gained the same classes in 4.27, and from 4.27
onward the two artifacts overlap. The build failed in
`check<Variant>DuplicateClasses` with ~100 duplicate `DescriptorProtos*` errors —
and only those; the runtime classes and the `com.google.rpc` / `com.google.type`
classes did not clash.

**Finding the requester.** It was not anything in the Firebase graph:

```powershell
.\gradlew.bat :app:dependencies --configuration stableDebugRuntimeClasspath
```

- `protolite-well-known-types:18.0.1` declares `protobuf-javalite:3.25.5`
- `grpc-protobuf-lite:1.62.2` declares `protobuf-javalite:3.25.1`
- `com.github.teamnewpipe:NewPipeExtractor:v0.26.3` declares `protobuf-javalite:4.35.0`

Only NewPipeExtractor wants 4.x, and Gradle's highest-version-wins rule dragged
the whole app to it. NewPipeExtractor is used for **trailers** and is
initialised at startup in `CommonActivity.onCreate`.

**Checking that 4.x is not actually required.** Rather than assume, the
`NewPipeExtractor-v0.26.3.jar` was inspected directly (its 436 class entries,
decompressed — scanning the raw archive is meaningless because the entries are
deflated). It references exactly **14** protobuf classes:

```
AbstractMessageLite, ByteString, CodedInputStream, ExtensionRegistryLite,
GeneratedMessageLite (+ Builder, DefaultInstanceBasedParser, MethodToInvoke),
Internal (+ ProtobufList), InvalidProtocolBufferException, MessageLite,
MessageLiteOrBuilder, Parser
```

All are core protobuf-lite runtime classes that are API-identical in 3.25.5. It
references **neither** `RuntimeVersion` (the 4.27+ gencode version gate, whose
absence means a 3.25.5 runtime will not be rejected) **nor**
`DescriptorProtos`. So the downgrade is safe.

**The fix.** Force `protobuf-javalite` to 3.25.5 — the exact version
`protolite-well-known-types` already declares, so the whole Firebase/gRPC graph
lines up rather than being bent:

```kotlin
configurations.configureEach {
    resolutionStrategy {
        force("com.google.protobuf:protobuf-javalite:${libs.versions.protobufJavalite.get()}")
    }
}
```

The resolved graph afterwards moves **one** artifact, and moves it to the version
the rest of the graph already agreed on:

```
com.github.teamnewpipe:NewPipeExtractor:v0.26.3
+--- com.google.protobuf:protobuf-javalite:4.35.0 -> 3.25.5
com.google.firebase:protolite-well-known-types:18.0.1
\--- com.google.protobuf:protobuf-javalite:3.25.5          (no redirect)
```

3.25.5 also carries the CVE-2024-7254 fix, so this is not a step back on
security. Nothing was excluded — the documented last resort of excluding
`protolite-well-known-types` is deliberately **not** used, because it strips the
`com.google.rpc` / `com.google.type` classes Firestore's generated protos need
and would turn a build failure into a runtime one.

`checkStableDebugDuplicateClasses` and `checkPrereleaseDebugDuplicateClasses`
both pass.

---

## 8. Planned later stages (not implemented)

Automatic and background sync, WorkManager, playback integration, conflict
resolution, offline queueing and paging, device registration and revocation,
extension repository and extension configuration syncing, a sync UI, and a web
client are all future stages. So are email verification, password reset and a
"resend verification email" flow. This file will be extended as each stage
lands.

Firestore has been verified end-to-end on a real device (see 5.1), but only on
**API 36**. The app's `minSdk` is 23 and the Firestore SDK supports it, so an
API 23 device is still worth exercising before the sync stages are built on top
of it.