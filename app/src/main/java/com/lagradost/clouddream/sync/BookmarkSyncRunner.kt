package com.lagradost.clouddream.sync

/**
 * The single operation the bookmark sync service needs from the bridge.
 *
 * [com.lagradost.clouddream.sync.local.CloudDreamBookmarkAdapter] is the only
 * implementation. This one-method seam exists so the service can be unit-tested on the
 * JVM without a `Context`, a `DataStoreHelper` singleton or a Firebase project — the same
 * reasoning that produced `LocalBookmarkStore` on the local side.
 *
 * Nothing here throws: the outcome is always a [CloudDreamSyncResult].
 */
fun interface BookmarkSyncRunner {

    /**
     * Runs one complete foreground sync pass.
     *
     * Returns [CloudDreamSyncResult.Skipped] when there is nothing to sync against (no
     * account, Firebase unconfigured, Firestore unavailable, or a pass is already
     * running), and [CloudDreamSyncResult.Failure] when the pass could not complete.
     */
    suspend fun syncBookmarksOnce(): CloudDreamSyncResult<Unit>
}
