package com.lagradost.clouddream.sync

import android.content.Context
import com.lagradost.clouddream.CloudDreamLog
import com.lagradost.clouddream.auth.CloudDreamAuth
import com.lagradost.clouddream.sync.local.CloudDreamBookmarkAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Phase 2D.2: the app's single entry point into bookmark sync.
 *
 * Called once from `CloudStreamApp.onCreate`, immediately after `CloudDream.init`. It
 * builds the bridge and the service, then subscribes to the Firebase session so that
 *
 *  - an app start with an account already signed in performs **one** foreground pass, and
 *  - a later sign-in performs **one** foreground pass, and
 *  - signing out performs **none**, and resets the in-memory state.
 *
 * ## Why this is a singleton installed at startup rather than a Compose effect
 *
 * Tying sync to composition would mean a recomposition could start a pass, and a
 * navigation change could start another. The auth listener is registered once, here, for
 * the life of the process, and the service's own `handledUid` guard collapses the
 * duplicate callbacks Firebase emits into a single pass.
 *
 * ## Cost at startup
 *
 * Installing this does no network work. `addUserStateListener` fires once with the
 * current user, and the resulting pass is launched onto a worker dispatcher, so
 * `Application.onCreate` is never blocked. On a build with no Firebase configuration
 * [install] returns after a debug log and allocates nothing.
 */
object CloudDreamSync {

    @Volatile
    private var service: CloudDreamBookmarkSyncService? = null

    /**
     * The live service, or null when sync is unavailable (no Firebase configuration) or
     * [install] has not run yet.
     *
     * Exposed so the settings screen can offer "Sync Now"; it holds no Firebase type.
     */
    fun serviceOrNull(): CloudDreamBookmarkSyncService? = service

    /**
     * Installs the sync service and its session listener. Idempotent, never throws, and a
     * no-op when Firebase is not configured.
     */
    fun install(context: Context) {
        if (service != null) return
        if (!CloudDreamAuth.isAvailable) {
            CloudDreamLog.d("Bookmark sync not installed: Firebase unavailable")
            return
        }
        try {
            val appContext = context.applicationContext
            val created = CloudDreamBookmarkSyncService(
                session = CloudDreamAuthSession,
                runner = CloudDreamBookmarkAdapter.create(
                    context = appContext,
                    manager = FirestoreCloudDreamSyncManager(appContext),
                ),
                // A process-lifetime scope for fire-and-forget passes. SupervisorJob so one
                // failed pass can never cancel a later one, and never the app.
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            )
            service = created

            // Firebase invokes this immediately with the current user, which covers the
            // "already signed in at startup" case; onStartup() is the belt-and-braces path
            // for the case where the listener is somehow late. Both are deduplicated.
            CloudDreamAuth.addUserStateListener { user ->
                created.onSessionUserChanged(user?.uid)
            }
            created.onStartup()
        } catch (t: Throwable) {
            // Sync is an enhancement. A failure here must not stop the app from starting.
            CloudDreamLog.e("Bookmark sync could not be installed; continuing without it", t)
            service = null
        }
    }
}
