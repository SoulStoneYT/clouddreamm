package com.lagradost.clouddream.sync

/**
 * The Firestore layout, in one place so the schema is reviewable without reading any
 * serialisation code.
 *
 * ```
 * users/{uid}                          profile: createdAt, lastSeenAt, schemaVersion
 * users/{uid}/progress/{documentId}    one per playable item (episode, or the title itself)
 * users/{uid}/bookmarks/{documentId}   one per title
 * users/{uid}/history/{documentId}     one per title (continue watching)
 * users/{uid}/devices/{deviceId}       reserved for a later device-management stage
 * ```
 *
 * `uid` is the Firebase Auth `FirebaseUser.uid`, so ownership matches the published
 * security rules: a client may only read and write under `users/{its own uid}`.
 *
 * `documentId` is [CloudDreamMediaKey.documentId], a 128-bit digest of a length-prefixed
 * canonical key. It is used instead of a readable slug because `uniqueUrl` contains `/`
 * and `:` and can exceed the document-id length limit, and because a digest cannot be
 * invalidated by a provider renaming a title. The human-readable key is stored inside the
 * document as well, for debugging and for future cross-provider matching.
 */
internal object CloudDreamFirestore {

    const val USERS = "users"
    const val PROGRESS = "progress"
    const val BOOKMARKS = "bookmarks"
    const val HISTORY = "history"

    /** Reserved for a later stage. Not written yet. */
    const val DEVICES = "devices"

    // Profile document.
    const val FIELD_CREATED_AT = "createdAt"
    const val FIELD_LAST_SEEN_AT = "lastSeenAt"
    const val FIELD_SCHEMA_VERSION = "schemaVersion"

    // Shared by every record document.
    const val FIELD_MEDIA_ID = "mediaId"
    const val FIELD_CANONICAL_KEY = "canonicalKey"
    const val FIELD_API_NAME = "apiName"
    const val FIELD_TYPE = "type"
    const val FIELD_UNIQUE_URL = "uniqueUrl"
    const val FIELD_YEAR = "year"
    const val FIELD_SEASON = "season"
    const val FIELD_EPISODE = "episode"
    const val FIELD_LOCAL_ID = "localId"
    const val FIELD_UPDATED_AT = "updatedAt"
    const val FIELD_DEVICE_ID = "deviceId"
    const val FIELD_SCHEMA = "schema"

    // progress
    const val FIELD_POSITION_MS = "positionMs"
    const val FIELD_DURATION_MS = "durationMs"
    const val FIELD_WATCHED = "watched"
    const val FIELD_PARENT_API_NAME = "parentApiName"
    const val FIELD_PARENT_UNIQUE_URL = "parentUniqueUrl"

    // bookmarks
    const val FIELD_NAME = "name"
    const val FIELD_WATCH_TYPE = "watchType"
    const val FIELD_POSTER_URL = "posterUrl"
    const val FIELD_PLOT = "plot"
    const val FIELD_TAGS = "tags"
    const val FIELD_BOOKMARKED_AT = "bookmarkedAt"

    // history
    const val FIELD_IS_FROM_DOWNLOAD = "isFromDownload"
}
