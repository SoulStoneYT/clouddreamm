package com.lagradost.clouddream.sync.local

import android.content.Context
import com.lagradost.clouddream.CloudDreamLog
import com.lagradost.clouddream.sync.CloudDreamBookmarkRecord
import com.lagradost.clouddream.sync.CloudDreamDeviceId
import com.lagradost.clouddream.sync.CloudDreamSyncError
import com.lagradost.clouddream.sync.CloudDreamSyncManager
import com.lagradost.clouddream.sync.CloudDreamSyncResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * CloudDream's bridge between CloudStream's local bookmark / title-watch-state store
 * and the backend-agnostic [CloudDreamSyncManager].
 *
 * This is the Phase 2D.1 bridge, in both directions:
 *
 * ```
 * CloudStream local bookmarks        Firestore
 *          │                             │
 *          ▼                             ▼
 *   LocalBookmarkStore ──► CloudDreamBookmarkAdapter ──► CloudDreamSyncManager
 *   (DataStoreHelper)      (this class)                   (FirestoreCloudDreamSyncManager)
 *          │                             │
 *          ▼                             ▼
 *  setBookmarkedData /              putBookmark / listBookmarks /
 *  setResultWatchState              deleteBookmark
 * ```
 *
 * The adapter never touches `rebuild_preference` or SharedPreferences directly. It goes
 * through [LocalBookmarkStore], whose production implementation
 * ([DataStoreBookmarkStore]) delegates to `DataStoreHelper`'s public APIs.
 *
 * ## Scope of 2D.1
 *
 * - Bookmark + **title-level** watch state only.
 * - No progress sync, no history sync, no WorkManager, no player hooks, no automatic
 *   sync on every local write, and no settings-UI changes.
 * - Syncing happens only through the explicit one-shot [syncBookmarksOnce], never in
 *   the background and never from `setBookmarkedData` / `setResultWatchState`.
 *
 * ## Threading
 *
 * Every public method is `suspend` and runs its blocking storage access on
 * `Dispatchers.IO`. Firestore access is handled by [CloudDreamSyncManager], which is
 * where the SDK is contained.
 */
class CloudDreamBookmarkAdapter(
    context: Context?,
    private val manager: CloudDreamSyncManager,
    private val local: LocalBookmarkStore = DataStoreBookmarkStore,
    private val deviceId: () -> String = { context?.let { CloudDreamDeviceId.get(it) } ?: "local" },
) {

    /**
     * Reads every locally-bookmarked title and turns it into a cloud record.
     *
     * This mirrors the library's own read path (`HomeViewModel.loadStoredData`):
     * enumerate `result_watch_state/{id}`, pair each id with its `BookmarkedData`, and
     * drop rows that have a watch state but no metadata — the library skips those too.
     * `WatchType.NONE` rows cannot occur, because `setResultWatchState(NONE)` deletes
     * both keys, but they are skipped defensively regardless.
     *
     * Ordering is by `latestUpdatedTime` descending, matching the local library sort.
     */
    suspend fun snapshotBookmarks(): CloudDreamSyncResult<List<CloudDreamBookmarkRecord>> =
        withContext(Dispatchers.IO) {
            val device = deviceId()
            val records = local.watchStateIds().mapNotNull { localId ->
                BookmarkMapper.toCloud(
                    data = local.getBookmarkData(localId),
                    watchType = local.getWatchState(localId),
                    deviceId = device,
                )
            }.sortedByDescending { it.meta.updatedAt }
            CloudDreamSyncResult.Success(records)
        }

    /**
     * Materialises one cloud bookmark into local storage: both the metadata
     * (`result_watch_state_data/{id}`) and the title watch-state ordinal
     * (`result_watch_state/{id}`), written under the record's `localId`.
     *
     * A record whose cloud `watchType` is `NONE` or not a valid [WatchType][com.lagradost.cloudstream3.ui.WatchType]
     * name, or that carries no `localId`, is treated as invalid and skipped — neither
     * setter is called, so a bad record can never be half-written.
     *
     * @return the local id the record was written to, or a [CloudDreamSyncResult.Failure]
     *   describing why it was skipped.
     */
    suspend fun putBookmarkLocally(record: CloudDreamBookmarkRecord): CloudDreamSyncResult<Int?> =
        withContext(Dispatchers.IO) {
            val mapped = BookmarkMapper.toLocal(record)
            if (mapped == null) {
                CloudDreamLog.w(
                    "cloud bookmark skipped, invalid watchType '${record.watchType}': ${record.key.canonical}"
                )
                return@withContext CloudDreamSyncResult.Failure(
                    CloudDreamSyncError.UNKNOWN,
                    IllegalArgumentException("invalid watchType: ${record.watchType}"),
                )
            }
            val localId = mapped.localId
            if (localId == null) {
                CloudDreamLog.w(
                    "cloud bookmark skipped, no localId: ${record.key.canonical}"
                )
                return@withContext CloudDreamSyncResult.Failure(
                    CloudDreamSyncError.UNKNOWN,
                    IllegalArgumentException("cloud bookmark carries no localId"),
                )
            }
            local.setBookmarkData(localId, mapped.bookmarked)
            local.setWatchState(localId, mapped.watchType.internalId)
            CloudDreamSyncResult.Success(localId)
        }

    /**
     * Deletes a bookmark in both places, in the only order that works.
     *
     * 1. capture the existing `BookmarkedData` for [id] and derive its `CloudDreamMediaKey`,
     * 2. delete the cloud document under that key,
     * 3. delete the local record — `deleteBookmarkedData` clears both local keys.
     *
     * The metadata has to be read *before* the local delete, because it is the only
     * thing from which the cloud key can be derived; after step 3 the key is gone.
     *
     * A `null` id, or a record missing the `TvType` needed to form a key, still clears
     * the local side; the latter reports success because such a row was never uploaded,
     * so there is nothing on the cloud to remove.
     *
     * This is callable from the unbookmark path, but it is deliberately **not** wired
     * into the UI yet — see CLOUDSYNC.md §9 for the deletion limitations.
     */
    suspend fun deleteBookmarkLocally(id: Int?): CloudDreamSyncResult<Unit> =
        withContext(Dispatchers.IO) {
            if (id == null) return@withContext CloudDreamSyncResult.Success(Unit)
            val data = local.getBookmarkData(id)
            if (data == null) return@withContext CloudDreamSyncResult.Success(Unit)
            val key = BookmarkMapper.keyFor(data)
            val cloud = if (key != null) {
                manager.deleteBookmark(key)
            } else {
                // No TvType means the key cannot be formed, which also means this row was
                // never uploaded by snapshotBookmarks — so there is nothing on the cloud to
                // remove and the local delete is complete on its own.
                CloudDreamLog.w("no cloud key derivable for local id $id; deleting locally only")
                CloudDreamSyncResult.Success(Unit)
            }
            local.deleteBookmarkData(id)
            cloud
        }

    /**
     * The Phase 2D.1 explicit one-shot sync.
     *
     * - **No-op when signed out or unconfigured.** The first `listBookmarks` call
     *   returns [CloudDreamSyncResult.Skipped] (or fails), which is propagated
     *   unchanged and no local read or write happens afterwards.
     * - **Last-writer-wins per record**, matched on `CloudDreamMediaKey.documentId`:
     *   a cloud record that is absent locally or strictly newer is applied locally; a
     *   local record that is absent from the cloud or strictly newer is uploaded.
     *   Equal timestamps are left untouched on both sides.
     * - Deletions are **not** auto-propagated. A local unbookmark only reaches the
     *   cloud when the caller invokes [deleteBookmarkLocally] explicitly.
     */
    suspend fun syncBookmarksOnce(): CloudDreamSyncResult<Unit> =
        withContext(Dispatchers.IO) {
            val cloud = when (val result = manager.listBookmarks()) {
                is CloudDreamSyncResult.Success -> result.value
                is CloudDreamSyncResult.Skipped -> {
                    CloudDreamLog.d("bookmark sync skipped: ${result.reason}")
                    return@withContext result
                }
                is CloudDreamSyncResult.Failure -> {
                    CloudDreamLog.w("bookmark sync cannot list cloud bookmarks: ${result.error}")
                    return@withContext result
                }
            }

            val local = when (val result = snapshotBookmarks()) {
                is CloudDreamSyncResult.Success -> result.value
                is CloudDreamSyncResult.Skipped -> return@withContext result
                is CloudDreamSyncResult.Failure -> return@withContext result
            }

            val localByDocument = local.associateBy { it.key.documentId }
            val cloudByDocument = cloud.associateBy { it.key.documentId }

            for (record in cloud) {
                val here = localByDocument[record.key.documentId]
                val cloudIsNewer = here == null || record.meta.updatedAt > here.meta.updatedAt
                if (!cloudIsNewer) continue
                val applied = putBookmarkLocally(record)
                if (applied is CloudDreamSyncResult.Failure) {
                    CloudDreamLog.w(
                        "could not apply cloud bookmark ${record.key.canonical}: ${applied.error}"
                    )
                }
            }

            for (record in local) {
                val remote = cloudByDocument[record.key.documentId]
                val localIsNewer = remote == null || record.meta.updatedAt > remote.meta.updatedAt
                if (!localIsNewer) continue
                val uploaded = manager.putBookmark(record)
                if (uploaded is CloudDreamSyncResult.Failure) {
                    CloudDreamLog.w(
                        "could not upload local bookmark ${record.key.canonical}: ${uploaded.error}"
                    )
                } else if (uploaded is CloudDreamSyncResult.Skipped) {
                    CloudDreamLog.w(
                        "upload skipped for ${record.key.canonical}: ${uploaded.reason}"
                    )
                }
            }

            CloudDreamSyncResult.Success(Unit)
        }
}
