package com.lagradost.clouddream.sync

import com.lagradost.clouddream.CloudDreamLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * The narrow view of the CloudDream session that sync needs.
 *
 * Exists so [CloudDreamBookmarkSyncService] can be tested without Firebase. Production
 * uses [CloudDreamAuthSession], which reads [com.lagradost.clouddream.auth.CloudDreamAuth].
 */
interface CloudDreamSession {

    /** True when Firebase is configured, so any cloud operation is possible at all. */
    val isAvailable: Boolean

    /** The signed-in account's uid, or null when signed out. */
    val currentUid: String?
}

/** [CloudDreamSession] backed by the real auth service. */
object CloudDreamAuthSession : CloudDreamSession {
    override val isAvailable: Boolean
        get() = com.lagradost.clouddream.auth.CloudDreamAuth.isAvailable
    override val currentUid: String?
        get() = com.lagradost.clouddream.auth.CloudDreamAuth.currentUser?.uid
}

/**
 * Phase 2D.2: the coordinator that makes bookmark sync reachable from the app.
 *
 * It owns three things and nothing else:
 *
 *  - **Availability.** Firebase unconfigured or nobody signed in is a no-op, decided here
 *    before the bridge is ever called, so a signed-out sync cannot touch local storage.
 *  - **Exclusion.** Only one pass runs at a time. A second request while one is in flight
 *    is dropped with [CloudDreamSkipReason.ALREADY_SYNCING] rather than queued, because
 *    two passes over the same records would fight each other under last-writer-wins.
 *  - **Deduplication of lifecycle triggers.** Sign-in and app start funnel through
 *    [onSessionUserChanged], which remembers the last uid it acted on. Firebase's auth
 *    listener fires immediately on registration *and* again on every sign-in, so without
 *    this a single app launch could fire two passes — or a Compose recomposition could
 *    fire one repeatedly.
 *
 * ## What it deliberately is not
 *
 * There is no WorkManager job, no timer, no retry loop and no `StateFlow` collector that
 * starts work on its own. Every pass originates from an explicit call: a lifecycle event
 * or the settings row. This service is UI-independent — it takes a [BookmarkSyncRunner] and
 * a [CloudDreamSession], and knows nothing about Compose.
 *
 * Sync state is in-memory only and is cleared on sign-out.
 */
class CloudDreamBookmarkSyncService(
    private val session: CloudDreamSession,
    private val runner: BookmarkSyncRunner,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow<CloudDreamBookmarkSyncState>(CloudDreamBookmarkSyncState.Idle)

    /** The coarse state for the settings row. Safe to collect from any thread. */
    val state: StateFlow<CloudDreamBookmarkSyncState> = _state.asStateFlow()

    /** Held for the duration of a pass, so two passes can never overlap. */
    private val inFlight = Mutex()

    /**
     * The uid whose sign-in has already triggered a pass, so the same account cannot
     * trigger one twice. Cleared on sign-out so the next sign-in syncs again.
     */
    private var handledUid: String? = null

    /**
     * Runs one foreground pass, or explains why it did not run.
     *
     * Never throws and never blocks indefinitely on the caller's behalf: the whole call is
     * a single suspend pass, and every failure mode is a returned value.
     */
    suspend fun syncNow(): CloudDreamSyncResult<Unit> {
        if (!session.isAvailable) {
            return CloudDreamSyncResult.Skipped(CloudDreamSkipReason.NOT_CONFIGURED)
        }
        val uid = session.currentUid
            ?: return CloudDreamSyncResult.Skipped(CloudDreamSkipReason.SIGNED_OUT)

        if (!inFlight.tryLock()) {
            return CloudDreamSyncResult.Skipped(CloudDreamSkipReason.ALREADY_SYNCING)
        }

        _state.value = CloudDreamBookmarkSyncState.Syncing
        return try {
            val result = runner.syncBookmarksOnce()
            _state.value = CloudDreamBookmarkSyncState.from(result)
            if (result is CloudDreamSyncResult.Failure) {
                CloudDreamLog.w("Bookmark sync failed: ${result.error}")
            }
            result
        } catch (t: Throwable) {
            // The bridge is contracted not to throw; this is the last line of defence so
            // a lifecycle-triggered pass can never take the app down with it.
            CloudDreamLog.e("Bookmark sync threw for $uid", t)
            _state.value = CloudDreamBookmarkSyncState.Failure(CloudDreamSyncError.UNKNOWN)
            CloudDreamSyncResult.Failure(CloudDreamSyncError.UNKNOWN, t)
        } finally {
            inFlight.unlock()
        }
    }

    /**
     * The one lifecycle entry point, for both sign-in and app start.
     *
     * - `uid == null` → signed out: **no sync is started**, nothing is uploaded, and the
     *   in-memory state is reset. Local CloudStream bookmarks are not touched at all.
     * - a uid already handled → ignored, which is what stops a recomposition or a second
     *   listener callback from re-running the pass.
     * - a new uid → one pass, launched on [scope] so the caller (an auth callback on the
     *   main thread) is never blocked by it.
     */
    fun onSessionUserChanged(uid: String?) {
        if (uid == null) {
            onSignedOut()
            return
        }
        if (handledUid == uid) {
            CloudDreamLog.d("Bookmark sync already handled for this session; not repeating")
            return
        }
        handledUid = uid
        scope.launch {
            // A failure here is logged and reflected in `state`; it must never surface as
            // an error on the authentication UI, which has already succeeded by this point.
            syncNow()
        }
    }

    /**
     * App start. If an account is already signed in, this is the trigger.
     *
     * Safe to call alongside [onSessionUserChanged]: both funnel through the same
     * `handledUid` guard, so an already-signed-in launch syncs exactly once.
     */
    fun onStartup() {
        if (!session.isAvailable) {
            CloudDreamLog.d("Bookmark sync startup skipped: Firebase unavailable")
            return
        }
        onSessionUserChanged(session.currentUid)
    }

    /**
     * Sign-out. Uploads nothing, and clears the in-memory state so the next account starts
     * from [CloudDreamBookmarkSyncState.Idle] and syncs on its own sign-in.
     */
    fun onSignedOut() {
        handledUid = null
        _state.value = CloudDreamBookmarkSyncState.Idle
        CloudDreamLog.d("Bookmark sync state reset on sign-out")
    }
}
