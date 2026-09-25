package com.lagradost.clouddream.sync

import kotlinx.coroutines.flow.StateFlow

/**
 * CloudDream's cloud-sync surface.
 *
 * The contract every implementation honours, and the one that keeps the rest of the app
 * honest:
 *
 * - **Nothing here throws.** Every call returns a [CloudDreamSyncResult]; a signed-out
 *   user, an unconfigured build and an unreachable Firestore all come back as
 *   [CloudDreamSyncResult.Skipped] or [CloudDreamSyncResult.Failure].
 * - **Nothing here is required for local behaviour.** Local storage, playback and the
 *   library never wait on, or read from, this interface. Callers are expected to be
 *   fire-and-forget.
 * - **No Firestore type appears in this file**, so callers cannot become coupled to the
 *   backend.
 *
 * No implementation is wired into the player, the library or any screen yet. That is
 * deliberate: this is the Phase 2C foundation only.
 */
interface CloudDreamSyncManager {

    /** Live availability and in-flight state. Safe to collect from any thread. */
    val state: StateFlow<CloudDreamSyncState>

    /** Ensures `users/{uid}` exists and is stamped with the last-seen time. */
    suspend fun ensureUserDocument(): CloudDreamSyncResult<Unit>

    suspend fun putProgress(record: CloudDreamProgressRecord): CloudDreamSyncResult<Unit>

    suspend fun putBookmark(record: CloudDreamBookmarkRecord): CloudDreamSyncResult<Unit>

    suspend fun putHistory(record: CloudDreamHistoryRecord): CloudDreamSyncResult<Unit>

    /** Progress for one playable item, or null when the cloud has no record for it. */
    suspend fun getProgress(key: CloudDreamMediaKey): CloudDreamSyncResult<CloudDreamProgressRecord?>

    suspend fun getBookmark(key: CloudDreamMediaKey): CloudDreamSyncResult<CloudDreamBookmarkRecord?>

    suspend fun getHistory(key: CloudDreamMediaKey): CloudDreamSyncResult<CloudDreamHistoryRecord?>

    /**
     * Everything under one collection for the signed-in account.
     *
     * A single unpaginated read, which is acceptable for a personal library and is
     * expected to be replaced with a paged or incremental pull before this is used
     * against a large account.
     */
    suspend fun listProgress(): CloudDreamSyncResult<List<CloudDreamProgressRecord>>

    suspend fun listBookmarks(): CloudDreamSyncResult<List<CloudDreamBookmarkRecord>>

    suspend fun listHistory(): CloudDreamSyncResult<List<CloudDreamHistoryRecord>>
}
