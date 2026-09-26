package com.lagradost.clouddream.sync

/**
 * The coarse sync state a UI can render.
 *
 * Deliberately smaller and blunter than [CloudDreamSyncResult]: the settings row only
 * needs to say "idle / working / done / not done", and must not leak which Firestore call
 * failed or why. The precise [CloudDreamSyncResult] is still returned to programmatic
 * callers by `CloudDreamBookmarkSyncService.syncNow()`.
 *
 * Nothing here is persisted. The state lives in memory for the life of the process and is
 * reset on sign-out, so a relaunch starts from [Idle].
 */
sealed interface CloudDreamBookmarkSyncState {

    /** No sync has run yet, or the last one was reset by signing out. */
    data object Idle : CloudDreamBookmarkSyncState

    /** A pass is in flight. A second request while this is set is dropped. */
    data object Syncing : CloudDreamBookmarkSyncState

    /** The last pass completed. */
    data object Success : CloudDreamBookmarkSyncState

    /**
     * The last pass did not complete.
     *
     * [error] is kept for logging and for deciding whether a retry is worthwhile; it is
     * never shown to the user, who gets a single generic message instead.
     */
    data class Failure(val error: CloudDreamSyncError) : CloudDreamBookmarkSyncState

    companion object {
        /**
         * Folds a finished [CloudDreamSyncResult] into the state a UI shows.
         *
         * `Skipped` is **not** a failure. Signed out, not configured, or already syncing
         * are the normal state of a personal app, and none of them is something the user
         * did wrong or can act on — so they leave the state untouched rather than
         * showing an error. `FIRESTORE_UNAVAILABLE` is the one skip that does mean the
         * service is genuinely unreachable, so it is surfaced.
         */
        fun from(result: CloudDreamSyncResult<*>): CloudDreamBookmarkSyncState = when (result) {
            is CloudDreamSyncResult.Success -> Success
            is CloudDreamSyncResult.Failure -> Failure(result.error)
            is CloudDreamSyncResult.Skipped -> when (result.reason) {
                CloudDreamSkipReason.FIRESTORE_UNAVAILABLE -> Failure(CloudDreamSyncError.FIRESTORE_FAILURE)
                else -> Idle
            }
        }
    }
}
