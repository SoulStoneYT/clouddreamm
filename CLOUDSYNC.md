# CLOUDSYNC.md â€” CloudDream Cloud Synchronization

Status: **Phases 2A, 2B, 2C, 2D.1 and 2D.2 complete â€” Firebase foundation, a
working account UI (sign in, create account, sign out), the cloud-sync foundation
(Firestore wired up, protobuf conflict resolved), a working bookmark + title
watch-state bridge, and that bridge now reachable from the app through
foreground sync triggers and a **Sync Now** setting.**

This document describes only what is implemented today. The account UI exists
and works, but it only establishes an identity. The sync **foundation** exists â€”
Firestore is a dependency, the schema and the media identity are defined, and
the sync manager can read, write and delete records.

Phase 2D.1 added the **local â‡„ cloud** path for bookmarks and title watch state:
`CloudDreamBookmarkAdapter` reads CloudStream's local library through
`DataStoreHelper`'s public APIs, converts it to cloud records, uploads it, pulls
the cloud back down and applies it.

Phase 2D.2 makes it reachable. `CloudDreamBookmarkSyncService` runs **one
foreground pass** when an account signs in, when the app starts with an account
already signed in, and when the user taps **Sync Now**. There is still **no
background sync**: no WorkManager job, no timer, no retry loop, and no hook on
the bookmark write path. There is still no playback integration, no progress or
history sync, no conflict-resolution engine and no device management.

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
- **Phase 2D.1:** bookmark + title watch-state sync. A new
  `com.lagradost.clouddream.sync.local` package holds the local bridge:
  `LocalBookmarkStore` (the narrow `DataStoreHelper` surface sync needs, plus its
  `DataStoreBookmarkStore` implementation), `BookmarkMapper` (the pure,
  Android-free local â‡„ cloud conversion) and `CloudDreamBookmarkAdapter` (the
  orchestrator, with `snapshotBookmarks`, `putBookmarkLocally`,
  `deleteBookmarkLocally` and the one-shot `syncBookmarksOnce`).
  `CloudDreamSyncManager` gained `deleteBookmark(key)` so an unbookmark can reach
  the cloud. Section 8 documents the architecture and section 9 the limitations.
- **Phase 2D.2:** foreground sync triggers and the **Sync Now** setting. Four new
  files in `com.lagradost.clouddream.sync`: `BookmarkSyncRunner` (the one-method
  seam the service needs from the bridge), `CloudDreamBookmarkSyncState`
  (`Idle` / `Syncing` / `Success` / `Failure`), `CloudDreamBookmarkSyncService`
  (availability checks, overlap protection, lifecycle dedupe) and
  `CloudDreamSync` (the app-level installer). `CloudStreamApp.onCreate` now calls
  `CloudDreamSync.install(this)`, and the signed-in Cloud settings state gained a
  **Sync Now** row. Phase 2D.1's four open issues â€” deletion destroying retry
  information, `localId` collisions overwriting unrelated bookmarks, malformed
  local records throwing out of the result contract, and a constant fallback
  device id â€” were all fixed. Section 9 documents this phase and section 10 the
  limitations.

That is all. **No background job, no player hook and no bookmark-write hook calls
the sync manager.** Bookmark and title watch state are the only synced entities:
**playback progress and continue-watching history are not implemented** and are
deliberately untouched. Sync only ever runs in the foreground, in response to an
explicit lifecycle event or a tap. There is no conflict-resolution engine, and
there are no changes to CloudStream's storage schema, its `BookmarkedData` model,
the player, or extensions. CloudStream stays fully usable with no account, when
Firebase is unconfigured, and when Firestore is unreachable.

### The Cloud settings section

Settings â†’ **Cloud** has three mutually exclusive states.

**Firebase not configured** â€” a single read-only row reading "Cloud features are
not available in this build". Nothing is clickable and nothing throws.

**Signed out** â€” a status row ("Not signed in") above a form containing an email
field, a password field with a visibility toggle, a **Sign In** button and a
**Create Account** button.

**Signed in** â€” a status row showing the account's email (or its uid if it has
none), a **Sync Now** row, and a **Sign Out** row.

The **Sync Now** row is Phase 2D.2. It calls the same
`CloudDreamBookmarkSyncService.syncNow()` the automatic triggers use, so a manual
sync and a lifecycle sync cannot diverge. Its subtitle reports the coarse state â€”
"Bookmarks and watch status" (idle), "Syncingâ€¦", "Up to date" or "Sync failed â€”
tap to try again" â€” and never a Firestore error, so no implementation detail
reaches the user. While a pass is running the row stays visible but drops its
`onClick`; it is not `enabled = false`, because a disabled `PreferenceItem`
animates out of the settings list instead of greying out. Tapping the row again
after a failure is the retry. If sync could not be installed at all (a build with
no Firebase configuration) the row is present but inert.

While a Firebase Auth call is in flight, a progress bar appears, both buttons and
both fields are disabled, and the primary button's label changes to
"Signing inâ€¦", "Creating accountâ€¦" or "Signing outâ€¦" so it is clear which
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
Kitsu, AniList, Simkl, â€¦) and uses a different account model. A CloudDream
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
(Authentication â†’ Sign-in method); until then Firebase answers
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
logs a debug message and does nothing. CloudStream works exactly as before â€”
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
| `CLOUDDREAM_FIREBASE_STORAGE_BUCKET` | No | Storage bucket (`bucketName`) â€” not used yet |
| `CLOUDDREAM_FIREBASE_MESSAGING_SENDER_ID` | No | Cloud Messaging sender ID â€” not used yet |

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
| `app/src/main/java/com/lagradost/clouddream/ui/CloudDreamCloudScreen.kt` | Phase 2B: the Cloud settings screen â€” unavailable / signed-out / signed-in states |
| `app/src/main/java/com/lagradost/clouddream/ui/CloudDreamSignInForm.kt` | The credential form: email, password, visibility toggle, buttons, progress, inline error |
| `app/src/main/java/com/lagradost/clouddream/auth/CloudDreamAuthErrorMessages.kt` | `CloudDreamAuthError` â†’ localized string resource |
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
| `app/src/main/java/com/lagradost/clouddream/sync/local/LocalBookmarkStore.kt` | Phase 2D.1: the narrow `DataStoreHelper` surface bookmark sync needs, plus `DataStoreBookmarkStore` |
| `app/src/main/java/com/lagradost/clouddream/sync/local/BookmarkMapper.kt` | Phase 2D.1: pure local â‡„ cloud conversion (no Android, no Firestore, no `DataStoreHelper`) |
| `app/src/main/java/com/lagradost/clouddream/sync/local/CloudDreamBookmarkAdapter.kt` | Phase 2D.1: the bridge â€” snapshot, apply, delete, and the one-shot `syncBookmarksOnce` |
| `app/src/test/java/com/lagradost/clouddream/sync/CloudDreamMediaKeyTest.kt` | Locks down determinism and non-collision of the media identity |
| `app/src/test/java/com/lagradost/clouddream/sync/local/CloudDreamBookmarkAdapterTest.kt` | Phase 2D.1: mapping, watch type, key generation, merge, no-op and deletion behaviour (no Firebase credentials needed) |
| `app/src/main/java/com/lagradost/clouddream/sync/BookmarkSyncRunner.kt` | Phase 2D.2: the one-method seam the service needs from the bridge |
| `app/src/main/java/com/lagradost/clouddream/sync/CloudDreamBookmarkSyncState.kt` | Phase 2D.2: `Idle` / `Syncing` / `Success` / `Failure`, and the resultâ†’state mapping |
| `app/src/main/java/com/lagradost/clouddream/sync/CloudDreamBookmarkSyncService.kt` | Phase 2D.2: the foreground coordinator â€” availability, overlap guard, lifecycle dedupe, sign-out reset |
| `app/src/main/java/com/lagradost/clouddream/sync/CloudDreamSync.kt` | Phase 2D.2: app-level installer; owns the session listener and the live service |
| `app/src/test/java/com/lagradost/clouddream/sync/CloudDreamBookmarkSyncServiceTest.kt` | Phase 2D.2: no-op, delegation, failure, overlap, sign-in, startup and sign-out behaviour |

### Note on which settings file to edit

CloudStream is mid-migration from XML/Preference settings to Compose. The **live**
hub is the Compose `SettingsFragment2` â†’ `SettingsFragmentScreen`; the older
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

### Verifying bookmark + watch-state sync (Phase 2D.1)

```powershell
# Everything, on the JVM. No device, no Firebase project, no credentials.
.\gradlew.bat :app:testStableDebug

# Both variants still package.
.\gradlew.bat :app:assembleStableDebug :app:assemblePrereleaseDebug

# The protobuf/protolite regression check, re-executed rather than trusted
# from cache (Gradle reports UP-TO-DATE for these when nothing changed).
.\gradlew.bat :app:checkStableDebugDuplicateClasses --rerun
.\gradlew.bat :app:checkPrereleaseDebugDuplicateClasses --rerun
```

`:app:testStableDebug` runs the whole unit-test source set, which is 51 tests:
13 in `CloudDreamMediaKeyTest`, 30 in `CloudDreamBookmarkAdapterTest` and 8
pre-existing `SubtitleLanguageTagTest` cases.

There is still **no on-device check for Phase 2D.1**, because nothing in the app
invokes `syncBookmarksOnce()` yet. Wiring it to a trigger is a later stage.

### 5.1 Firestore verification record

Firestore was verified end-to-end on a real device during Phase 2C, using a
throwaway probe that has since been deleted. The probe confirmed that the
signed-in account is visible to CloudDream, a document can be written under
`users/{uid}/` and read back, and the Phase 2C record mapping round-trips
through the real `FirestoreCloudDreamSyncManager`. It deleted everything it
created, so no test data remains.

Result on a OnePlus CPH2491, **API 36** â€” every step passed, twice, with no
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
  read-only in-memory snapshot. The sync layer stores no token either â€” the
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

- The id is `ResultViewModel2.getLoadResponseIdFromUrl`'s output â€”
  `uniqueUrl.replace(providerMainUrl, "").replace("/", "").hashCode()`. It is
  scoped to one provider, is a 32-bit signed `Int` (so it can be negative, and
  can collide), and changes if the provider's `mainUrl` or URL format changes.
  CloudStream's own `result_resume_watching` â†’ `result_resume_watching_2`
  migration exists precisely because those ids moved once already.
- It is usually `null` on a search result, because the library only computes it
  in `LoadResponse.getId()` once a title has been opened.

So the cloud key is built from the underlying fields instead:

| Field | Why it is in the identity |
|---|---|
| `apiName` | The provider. CloudStream's own id hash folds in the provider's `mainUrl`, so the same title on two providers is genuinely two items. |
| `type` | `TvType`, stored by **name** and never by ordinal â€” the local enum's ordinals are not a stable wire format (matching `DataStoreHelper.serializeTv`). |
| `uniqueUrl` | Documented in the library as "the key used for storing the persistent data about an entry", introduced specifically to survive URL format changes. Strongest stable signal available. |
| `year` | CloudStream's own `checkAndWarnDuplicates` treats a matching name plus year as a duplicate, so year is a real disambiguator for remakes. Nullable â€” not every provider returns it. |
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
The one drawback of a random UUID â€” it is lost on reinstall â€” is correct
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

### 7.5 Protobuf conflict â€” what it was and how it was resolved

`firebase-firestore` depends on `com.google.firebase:protolite-well-known-types`,
which bundles its own copy of the `com.google.protobuf.DescriptorProtos` schema
classes. `protobuf-javalite` gained the same classes in 4.27, and from 4.27
onward the two artifacts overlap. The build failed in
`check<Variant>DuplicateClasses` with ~100 duplicate `DescriptorProtos*` errors â€”
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
decompressed â€” scanning the raw archive is meaningless because the entries are
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

**The fix.** Force `protobuf-javalite` to 3.25.5 â€” the exact version
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
security. Nothing was excluded â€” the documented last resort of excluding
`protolite-well-known-types` is deliberately **not** used, because it strips the
`com.google.rpc` / `com.google.type` classes Firestore's generated protos need
and would turn a build failure into a runtime one.

`checkStableDebugDuplicateClasses` and `checkPrereleaseDebugDuplicateClasses`
both pass.

---

## 8. Bookmark + title watch-state sync (Phase 2D.1)

### 8.1 The bridge

```
CloudStream local library                  Firestore
       â”‚                                       â”‚
       â–¼                                       â–¼
 LocalBookmarkStore â”€â”€â–º CloudDreamBookmarkAdapter â”€â”€â–º CloudDreamSyncManager
 (DataStoreBookmarkStore)   (orchestrates)         (FirestoreCloudDreamSyncManager)
       â”‚                                       â”‚
       â–¼                                       â–¼
 setBookmarkedData / setResultWatchState   putBookmark / listBookmarks /
 deleteBookmarkedData                      deleteBookmark
```

Three files, each with one job:

- **`LocalBookmarkStore`** â€” the narrow slice of `DataStoreHelper` that sync needs:
  `watchStateIds()`, `getWatchState(id)`, `getBookmarkData(id)`,
  `setBookmarkData(id, data)`, `setWatchState(id, watchTypeId)`,
  `deleteBookmarkData(id)`. `DataStoreBookmarkStore` is the production
  implementation and delegates to `DataStoreHelper`'s public methods.

  The interface exists so CloudDream depends on a six-method surface rather than
  on a SharedPreferences singleton, and so the adapter can be tested on the JVM
  with an in-memory fake.

- **`BookmarkMapper`** â€” the pure conversion. It holds no reference to
  `DataStoreHelper`, to the sync manager, to Firestore or to any Android API,
  which is exactly what makes the mapping contract testable without a device.
  `internal`, so it is not part of CloudDream's published surface.

- **`CloudDreamBookmarkAdapter`** â€” the orchestrator. Four suspend functions,
  all doing their blocking storage work on `Dispatchers.IO`:
  `snapshotBookmarks()`, `putBookmarkLocally(record)`, `deleteBookmarkLocally(id)`
  and `syncBookmarksOnce()`. Its constructor takes a nullable `Context`, the
  manager, and â€” defaulted, for tests â€” the store and a device-id supplier.

**DataStoreHelper is the only storage boundary.** The adapter never touches
`rebuild_preference` or a `SharedPreferences` directly; it goes through
`DataStoreBookmarkStore`. No CloudStream model was modified.

### 8.2 Why bookmark and watch state are one record

CloudStream stores them as **two keys under one local integer id**:

| Local key | Value |
|---|---|
| `$acct/result_watch_state_data/{id}` | `BookmarkedData` JSON |
| `$acct/result_watch_state/{id}` | `WatchType.internalId` as an `Int` |

`setResultWatchState(id, NONE)` calls `deleteBookmarkedData(id)`, which removes
**both** keys. So a title with `WatchType.NONE` does not exist locally: "NONE"
*is* "unbookmarked". That makes bookmark + title watch state a single logical
entity, which is exactly what `CloudDreamBookmarkRecord` already models with
its `watchType` field. No new cloud record type was needed.

They can still diverge if a caller writes one without the other.
`snapshotBookmarks()` mirrors the library's own read path
(`HomeViewModel.loadStoredData`): enumerate `getAllWatchStateIds()`, pair each id
with `getBookmarkedData(id)`, and **skip ids that have no metadata** â€” the library
skips those when rendering the home screen, so syncing them would upload rows
the user cannot see.

### 8.3 Local â†’ cloud mapping

For each id in `getAllWatchStateIds()`:

| Cloud field | Local source |
|---|---|
| `key` | `CloudDreamMediaKey.forTitle(apiName, data.type!!.name, data.url, data.year)` |
| `name` | `data.name` |
| `watchType` | `WatchType.fromInternalId(...).name` â€” a **name**, never the raw int |
| `posterUrl` | `data.posterUrl` |
| `plot` | `data.plot` |
| `tags` | `data.tags` |
| `bookmarkedAt` | `data.bookmarkedTime` |
| `localId` | `data.id` (debugging aid only, never the identity) |
| `meta.updatedAt` | **`data.latestUpdatedTime`** |
| `meta.deviceId` | `CloudDreamDeviceId.get(context)` |

Skipped entirely: rows with no `BookmarkedData`, rows whose watch type is
`NONE`, and rows with no `TvType` (no `TvType` means no key can be formed). The
result is ordered by `latestUpdatedTime` descending, matching the local library
sort.

**The local `Int` id is never the cloud identity.** The document id is
`CloudDreamMediaKey.documentId`, a 128-bit SHA-256 prefix. `localId` is carried
only so a pull on the same device can find the local key to write under.

**`meta.updatedAt` is the local write clock, not the upload clock.**
`latestUpdatedTime` is when the user last changed the bookmark. If `updatedAt`
were stamped with `System.currentTimeMillis()` at upload time, a record that
changed in January but was first synced in June would look newer than a record
genuinely edited in May, and last-writer-wins would then resolve the wrong way.
The adapter therefore constructs `CloudDreamRecordMeta` directly rather than
going through `FirestoreCloudDreamSyncManager.newMeta()`.

### 8.4 Cloud â†’ local mapping

For a `CloudDreamBookmarkRecord`:

1. `watchType` is resolved by **enum name** â€” `WatchType.entries.find { it.name == ... }`.
   An unknown, blank or `NONE` value means the record is **invalid and skipped**;
   no state is invented and neither setter is called.
2. `key.type` is resolved to a `TvType` the same way (by name, never ordinal).
   An unresolvable value is likewise skipped.
3. `BookmarkedData` is reconstructed: `url â† key.uniqueUrl`, `apiName â†
   key.apiName`, `type â† TvType`, `year â† key.year`, `name â† record.name`,
   `posterUrl`/`plot`/`tags` copied, `bookmarkedTime â† record.bookmarkedAt`, and
   `latestUpdatedTime â† record.meta.updatedAt`.
4. Both keys are written through `DataStoreHelper`: `setBookmarkedData(localId, â€¦)`
   then `setResultWatchState(localId, watchType.internalId)`.

A record with **no `localId`** is skipped, because there is no local key to write
under. `putBookmarkLocally` returns a `Failure` for both cases and logs the
canonical key, so a bad document is visible in logcat rather than silently
dropped.

The write order matters: `setResultWatchState` with a non-`NONE` status does not
create metadata, and `setBookmarkedData` does not set the status, so both are
always written.

### 8.5 Watch type is stored by name

`WatchType` has six entries (`WATCHING`, `COMPLETED`, `ONHOLD`, `DROPPED`,
`PLANTOWATCH`, `NONE`) and locally persists as the raw `Int` `internalId`. The
cloud record stores `WatchType.name`.

The integer is not portable: the local enum's ordinals are not a wire format, and
`DataStoreHelper.serializeTv` already serializes `TvType` by name for the same
reason. So the conversion is always `Int â†’ fromInternalId â†’ name` on upload and
`name â†’ entries.find â†’ internalId` on download, both in `BookmarkMapper` and both
locked down by tests.

`NONE` is never uploaded and never applied. A `NONE` document from some other
writer is rejected rather than turned into a local unbookmark, because 2D.1 has no
tombstone semantics (see Â§9).

Note the local `WatchType` (6 values) is not the same enum as `SyncWatchType`
(7 values, with `REWATCHING`, used by the MAL/AniList/Kitsu/Simkl providers).
`CloudDreamBookmarkRecord.watchType` is a `WatchType` name; `REWATCHING` has no
counterpart and a document carrying it is rejected.

### 8.6 One-shot sync

`syncBookmarksOnce()` is the entire sync surface in 2D.1:

1. `manager.listBookmarks()`. A `Skipped` (signed out / not configured /
   Firestore unavailable) or a `Failure` is **propagated unchanged and nothing
   else runs** â€” not one local read or write happens on a signed-out sync.
2. `snapshotBookmarks()` for the local side.
3. **Download.** Records are matched by `key.documentId`. A cloud record that is
   absent locally, or strictly newer (`cloud.meta.updatedAt > local.meta.updatedAt`),
   is applied via `putBookmarkLocally`. Anything else is left alone.
4. **Upload.** A local record that is absent from the cloud, or strictly newer,
   is pushed with `putBookmark`.
5. Returns `Success(Unit)`. Per-record failures are logged and do not abort the
   run.

**Equal timestamps are left untouched on both sides** â€” no write, no churn.

This is plain per-record **last-writer-wins**. It is not a conflict-resolution
engine: there is no field-level merge, no vector clock, and no user-facing choice.
`updatedAt` comes from each device's own wall clock, so a device whose clock is
wrong can win a merge it should lose. That is a known limitation of the phase,
not a solved problem.

**It runs only when something calls it.** There is no WorkManager job, no
`bookmarksUpdatedEvent` listener, no sign-in hook and no settings action, and it
is never called from `setBookmarkedData` or `setResultWatchState`. The
`bookmarksUpdatedEvent` signal is in any case unreliable for this purpose â€”
`setBookmarkedData` and `deleteBookmarkedData` do not fire it, so a deletion made
through those paths would be missed entirely. An explicit trigger is the honest
option at this stage.

### 8.7 Cloud deletion

Phase 2C had no delete operation at all, so 2D.1 added one:

```kotlin
suspend fun deleteBookmark(key: CloudDreamMediaKey): CloudDreamSyncResult<Unit>
```

`FirestoreCloudDreamSyncManager` implements it as
`users/{uid}/bookmarks/{key.documentId}.delete()`, inside the same `withFirestore`
guard as every other operation, so it returns `Skipped` when signed out or
unconfigured and never throws.

`CloudDreamBookmarkAdapter.deleteBookmarkLocally(id)` is the only caller, and it
has to run in a strict order, because the key can only be derived from data that
the local delete destroys:

1. read the existing `BookmarkedData` for `id`,
2. derive its `CloudDreamMediaKey` from it,
3. `manager.deleteBookmark(key)`,
4. `local.deleteBookmarkData(id)` â€” which clears both local keys.

A `null` id, or an id with no `BookmarkedData`, is a no-op. A record that cannot
form a key (no `TvType`, blank `apiName`) was never uploaded, so there is nothing
on the cloud and the local delete completes on its own.

Phase 2D.2 changed this: the local delete now happens **only** when the cloud
delete actually resolved. See section 9.4.

---

## 9. Foreground sync triggers (Phase 2D.2)

### 9.1 The service

`CloudDreamBookmarkSyncService` is the only thing that starts a sync pass. It owns
three responsibilities and nothing else:

- **Availability.** Firebase unconfigured, or nobody signed in, is a no-op decided
  before the bridge is touched â€” so a signed-out sync cannot reach local storage at
  all. It returns `Skipped(NOT_CONFIGURED)` or `Skipped(SIGNED_OUT)`.
- **Exclusion.** Only one pass runs at a time. A second request while one is in
  flight is **dropped** with `Skipped(ALREADY_SYNCING)`, not queued: two passes
  over the same records would fight each other under last-writer-wins.
- **Deduplication of lifecycle triggers.** Sign-in and app start both funnel
  through `onSessionUserChanged(uid)`, which remembers the last uid it acted on.
  Firebase's auth listener fires immediately on registration *and* again on every
  sign-in, so without this guard a single launch could fire two passes.

It takes two interfaces rather than concrete types, which is what makes all 18 of
its tests run on the JVM with no Firebase project: `CloudDreamSession` (availability
and the current uid, with `CloudDreamAuthSession` as the production implementation)
and `BookmarkSyncRunner` (one `syncBookmarksOnce()`, implemented by
`CloudDreamBookmarkAdapter`).

`syncNow()` never throws. A bridge that throws anyway is caught, logged and turned
into `Failure(UNKNOWN)`, so a lifecycle-triggered pass can never take the app down.

### 9.2 The exact triggers

| Trigger | What happens |
|---|---|
| **App start, already signed in** | One pass. `CloudDreamSync.install` registers the auth listener, which Firebase invokes immediately with the current user; the explicit `onStartup()` call is the belt-and-braces path, and the uid guard collapses both into one pass. |
| **Successful sign-in / session change** | One pass for the new uid, launched on a background scope. It cannot block the authentication UI: `onSessionUserChanged` returns immediately and the pass runs on `SupervisorJob() + Dispatchers.IO`. A failure is logged and reflected in the sync state â€” it never surfaces as an error on the sign-in screen, which has already succeeded by that point. |
| **Repeated callbacks for the same uid** | Nothing. This is what stops a recomposition or a duplicate listener callback from re-running the pass. |
| **Different account** | One pass. The uid guard keys on the account, not on "has synced before". |
| **Sign-out** | **No sync at all.** Nothing is uploaded, the in-memory state is reset to `Idle`, and the handled-uid is cleared so the next sign-in syncs again. Local CloudStream bookmarks are not touched â€” signing out never writes to local storage. |
| **Sync Now** | One pass, via the same `syncNow()`. Inert while a pass is running. |
| **Bookmark edited locally** | **Nothing.** There is deliberately no hook on `setBookmarkedData` or `setResultWatchState`, and no `bookmarksUpdatedEvent` listener. |

`CloudDreamSync.install(context)` is called once from
`CloudStreamApp.onCreate`, immediately after `CloudDream.init`. It is idempotent,
returns early when Firebase is unconfigured, and is wrapped in a `try`/`catch` so a
broken setup can never stop the app from starting.

### 9.3 Why not a Compose effect

Tying the trigger to composition would mean a recomposition could start a pass and
a navigation change could start another. The auth listener is registered once, at
application scope, for the life of the process, and the service's own uid guard
collapses Firebase's duplicate callbacks. The settings row only *reads* the state
flow; it never starts work outside a tap.

### 9.4 What Phase 2D.2 changed in the bridge

Phase 2D.1 shipped four defects. All are fixed; the reasoning is preserved here
because the fixes are behavioural, not cosmetic.

**Deletion no longer destroys retry information.** Previously
`deleteBookmarkLocally` deleted the local row unconditionally, even when the cloud
delete had failed. That was doubly bad: the surviving cloud document would be
re-applied by the next sync, resurrecting the bookmark, and because the local
metadata is the only source of the cloud key, a retry would have had nothing left
to work from â€” orphaning the cloud document permanently. Now the local delete runs
**only** when the cloud delete returned `Success`; on `Skipped` or `Failure` the
local bookmark is left completely intact and that result is returned unchanged, so
calling the method again once the account is reachable completes the operation.
The cloud delete still happens first, because the key cannot be derived afterwards.

**`localId` collisions no longer overwrite unrelated bookmarks.** `localId` is
CloudStream's own 32-bit hash, which its documentation admits can collide. Before
writing, `putBookmarkLocally` reads the row already at that id and compares its
`CloudDreamMediaKey` against the incoming record's: nothing there â†’ write; same
key â†’ update; **different key â†’ skip**, leaving both records alone. A row that
cannot form a key at all was never uploadable, so it is not a sync target and is
logged rather than skipped.

**Malformed local records no longer throw.** `CloudDreamMediaKey` rejects a blank
`apiName` with `require`, so a corrupt `BookmarkedData` could throw an
`IllegalArgumentException` straight out of a function whose return type promises a
`CloudDreamSyncResult`. `BookmarkMapper.keyFor` now returns `null` for a blank
`apiName` before that can be reached, and `snapshotBookmarks` additionally wraps
each id in its own `try`/`catch`, so one bad row is skipped with a log line instead
of failing the pass or escaping the contract.

**The device id is always the real one.** The adapter's old constructor defaulted
the device id to the constant `"local"` when handed a null `Context`, which would
have written a shared, indistinguishable id into every record of every such
install. The constructor is now `internal` with no defaults, and production builds
the adapter through `CloudDreamBookmarkAdapter.create(context, manager)`, which
sources it from `CloudDreamDeviceId`. Only tests supply their own.

### 9.5 State

`CloudDreamBookmarkSyncState` is deliberately blunter than
`CloudDreamSyncResult`: `Idle`, `Syncing`, `Success`, `Failure(error)`. A `Skipped`
result is **not** a failure â€” signed out, not configured and already-syncing are the
normal state of a personal app and nothing the user did wrong or can act on â€” so it
leaves the state at `Idle`. The one exception is `FIRESTORE_UNAVAILABLE`, which
means the service is genuinely unreachable and is surfaced as a `Failure`. The
state is in-memory only, cleared on sign-out, and never persisted.

---

## 10. Phase 2D.2 limitations

**Real-device lifecycle verification is still pending.** Everything in section 9
is verified by 18 JVM tests against a fake session and a fake runner, plus a full
build of both debug variants. It has **not** been exercised on a device against a
real Firebase project. Specifically unverified: that `CloudDreamSync.install` runs
correctly from `Application.onCreate`; that Firebase's auth listener actually fires
at the expected moment relative to `onStartup()`; that the uid guard collapses the
real callback sequence to exactly one pass (a duplicate or missed pass here is
silent); and that a pass launched at startup does not compete with app startup for
time. A device run with a real account is the next thing to do.

**Deletion is still not propagated automatically.** The bridge's deletion behaviour
is now safe and retryable, but nothing calls it: `deleteBookmarkLocally` is still
not wired into the UI, and `syncBookmarksOnce` still never deletes. A user who
unbookmarks a title locally does **not** remove it from the cloud, and the next
sync will re-apply it. **Unbookmarking is still not durable across a sync.**

**There are no cloud tombstones.** A deleted record is removed rather than marked
deleted, so a sync cannot distinguish "deleted on device A" from "not present
because device A has not synced yet".

**Sync is foreground-only and only at three moments.** No WorkManager job, no
periodic sync, no sync on app resume, no sync after a bookmark edit. A title
bookmarked on device A while device B is open will not reach device B until B next
starts the app, signs in, or the user taps Sync Now.

**Failures are surfaced only in the settings row.** A failed automatic pass is
logged and reflected in the row's subtitle; there is no toast, notification or
retry prompt anywhere else in the app. A user who never opens Settings will not
learn that sync is failing.

**Everything in section 9.1's predecessor list still applies.** In particular:
`uniqueUrl` is not persisted, so `BookmarkedData.url` is used to build the key;
`localId` remains a non-durable best-effort local handle; one selected CloudStream
local account maps to one Firebase uid; `updatedAt` is each device's own wall
clock; and `listBookmarks()` is a single unpaginated read. See the Phase 2D.1
limitations above, which remain accurate.

**A new test-only dependency was added.** `kotlinx-coroutines-test` is now a
`testImplementation` of the `app` module, so the concurrency and trigger tests can
use a deterministic scheduler instead of real thread timing. It was already
version-pinned in `gradle/libs.versions.toml` (`kotlinxCoroutines = "1.11.0"`), so
no new version was introduced, and it is not on any runtime classpath.

---

### 10.1 Carried over unchanged from Phase 2D.1

These were true when 2D.1 landed and are still true. They are not repeated in full
in section 9.

**Cloud â†’ local delete has no key to aim at.** The only handle on a local record
is the provider-scoped `localId` int. Without it there is no way to target a
local deletion, and re-deriving the key is not possible because `uniqueUrl` is
not persisted (below). A record without `localId` is skipped, not applied.

**`uniqueUrl` is not persisted, so `BookmarkedData.url` is used instead.**
`BookmarkedData` extends `LibrarySearchResponse` â†’ `SearchResponse`, and
`uniqueUrl` is declared on the separate `LoadResponse` interface â€” so a locally
stored bookmark keeps `url` only. CloudDream therefore builds the key from
`data.url`, which matches `uniqueUrl` for the large majority of providers
(CloudStream itself persists `url`, and the local int id is derived from
`uniqueUrl`, so the two usually agree).

The exception is a provider that **overrides `uniqueUrl` to something other than
`url`** â€” Trakt is the known case. There, the key is built from `url`, which is
not the field the local id was derived from, so cross-device matching is weaker
than the design intends. Fixing it properly means persisting `uniqueUrl` into
`BookmarkedData`, which is a CloudStream storage-schema change and remains
**out of scope**.

**`localId` is not durable.** It is `getLoadResponseIdFromUrl`'s 32-bit
provider-scoped hash and it moves if a provider's `mainUrl` or URL format
changes â€” the exact failure the `result_resume_watching` â†’
`result_resume_watching_2` migration was written for. Phase 2D.2's collision
check (section 9.4) means a wrong `localId` can no longer destroy an unrelated
bookmark, but the id is still only a best-effort local handle and must never be
treated as an identity.

**One local account maps to one Firebase uid, and only the selected one.**
CloudStream namespaces local storage by `DataStoreHelper.currentAccount`, an
account **index**; Firestore namespaces by the Firebase Auth `uid`. These are
unrelated namespaces. Only the currently selected CloudStream local account is
synced to the currently signed-in Firebase account, with no merging. Switching
CloudStream accounts while signed in to Firebase will mix the two local
accounts' bookmarks into the same `users/{uid}/bookmarks` subtree. Resolving
this needs a stored mapping from local account to uid.

**The clock is trusted.** `updatedAt` is each device's own
`System.currentTimeMillis()`. Skewed clocks resolve last-writer-wins incorrectly,
and `deviceId` is recorded but not yet used to arbitrate.

**`listBookmarks()` is a single unpaginated read.** Fine for a personal library;
it needs paging or an incremental pull before a large account.

**Untyped provider data is preserved but not merged.** `syncData`, `quality`,
`posterHeaders` and `score` are local-only and are neither uploaded nor cleared
by a pull. `latestUpdatedTime` on a record pulled from the cloud is set from the
cloud `updatedAt`, so a pulled bookmark is correctly ordered locally.

**`FirestoreCloudDreamSyncManager.deleteBookmark` is still untested against a real
backend.** Its behaviour is covered only at the adapter layer with a fake. It
follows the same `withFirestore` guard as every other Firestore call, but the
`.delete()` round trip has never been observed.

## 11. Planned later stages (not implemented)

Wiring deletion into the unbookmark path, tombstones, background and periodic sync
(WorkManager), sync on app resume, playback progress sync, continue-watching history
sync, conflict resolution, offline queueing and paging, device registration and
revocation, multi-local-account mapping, extension repository and extension
configuration syncing, user-visible sync reporting outside the settings row, and a
web client are all future stages. So are email verification, password reset and a
"resend verification email" flow. This file will be extended as each stage
lands.

Firestore has been verified end-to-end on a real device (see 5.1), but only on
**API 36**. The app's `minSdk` is 23 and the Firestore SDK supports it, so an
API 23 device is still worth exercising before the sync stages are built on top
of it. The Phase 2D.2 lifecycle behaviour has not been verified on a device at
all, on any API level.
