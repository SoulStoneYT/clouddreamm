package com.lagradost.clouddream.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2D.2 — the foreground sync coordinator and its lifecycle triggers.
 *
 * Everything here runs on the JVM with no Firebase project and no credentials: the
 * session is a plain fake and the bridge is a one-method [BookmarkSyncRunner]. What is
 * being locked down is the *trigger policy* — how many passes start, when, and what
 * happens when a pass is already running. Getting that wrong is how a personal app ends
 * up hammering Firestore on every recomposition.
 */
class CloudDreamBookmarkSyncServiceTest {

    // ---------------------------------------------------------------- fakes

    private class FakeSession(
        override var isAvailable: Boolean = true,
        override var currentUid: String? = null,
    ) : CloudDreamSession

    /**
     * Counts passes, can be made to fail, and can be held open so an overlapping call can
     * be observed while the first is still running.
     */
    private class FakeRunner(
        var result: CloudDreamSyncResult<Unit> = CloudDreamSyncResult.Success(Unit),
    ) : BookmarkSyncRunner {

        var calls = 0
        var maxConcurrent = 0
        private var inFlight = 0

        /** When set, every pass waits on this before returning. */
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun syncBookmarksOnce(): CloudDreamSyncResult<Unit> {
            calls++
            inFlight++
            maxConcurrent = maxOf(maxConcurrent, inFlight)
            try {
                gate?.await()
                return result
            } finally {
                inFlight--
            }
        }
    }

    private fun service(
        session: CloudDreamSession,
        runner: BookmarkSyncRunner,
        scope: CoroutineScope,
    ) = CloudDreamBookmarkSyncService(session, runner, scope)

    // ------------------------------------------------------------ availability

    @Test
    fun `sync is a no-op when Firebase is unavailable`() = runTest {
        val runner = FakeRunner()
        val svc = service(FakeSession(isAvailable = false, currentUid = "uid-1"), runner, this)

        val result = svc.syncNow()

        assertEquals(CloudDreamSyncResult.Skipped(CloudDreamSkipReason.NOT_CONFIGURED), result)
        assertEquals("the bridge must not even be called", 0, runner.calls)
    }

    @Test
    fun `sync is a no-op when nobody is signed in`() = runTest {
        val runner = FakeRunner()
        val svc = service(FakeSession(currentUid = null), runner, this)

        val result = svc.syncNow()

        assertEquals(CloudDreamSyncResult.Skipped(CloudDreamSkipReason.SIGNED_OUT), result)
        assertEquals(0, runner.calls)
        assertEquals(CloudDreamBookmarkSyncState.Idle, svc.state.value)
    }

    // -------------------------------------------------------------- delegation

    @Test
    fun `a successful sync delegates once and reports success`() = runTest {
        val runner = FakeRunner()
        val svc = service(FakeSession(currentUid = "uid-1"), runner, this)

        val result = svc.syncNow()

        assertEquals(CloudDreamSyncResult.Success(Unit), result)
        assertEquals(1, runner.calls)
        assertEquals(CloudDreamBookmarkSyncState.Success, svc.state.value)
    }

    @Test
    fun `a bridge failure is surfaced without throwing`() = runTest {
        val runner = FakeRunner(
            result = CloudDreamSyncResult.Failure(CloudDreamSyncError.FIRESTORE_FAILURE)
        )
        val svc = service(FakeSession(currentUid = "uid-1"), runner, this)

        val result = svc.syncNow()

        assertTrue(result is CloudDreamSyncResult.Failure)
        assertEquals(
            CloudDreamBookmarkSyncState.Failure(CloudDreamSyncError.FIRESTORE_FAILURE),
            svc.state.value,
        )
    }

    @Test
    fun `a bridge that throws is contained and reported as a failure`() = runTest {
        val runner = object : BookmarkSyncRunner {
            override suspend fun syncBookmarksOnce(): CloudDreamSyncResult<Unit> =
                throw IllegalStateException("boom")
        }
        val svc = service(FakeSession(currentUid = "uid-1"), runner, this)

        val result = svc.syncNow()

        assertTrue(result is CloudDreamSyncResult.Failure)
        assertEquals(CloudDreamBookmarkSyncState.Failure(CloudDreamSyncError.UNKNOWN), svc.state.value)
    }

    @Test
    fun `an unreachable Firestore surfaces as a failure but signing out does not`() = runTest {
        val unavailable = service(
            FakeSession(currentUid = "uid-1"),
            FakeRunner(
                result = CloudDreamSyncResult.Skipped(CloudDreamSkipReason.FIRESTORE_UNAVAILABLE)
            ),
            this,
        )
        unavailable.syncNow()
        assertTrue(unavailable.state.value is CloudDreamBookmarkSyncState.Failure)

        // A signed-out "skip" is the normal state of the app, not an error to show.
        val signedOut = service(
            FakeSession(currentUid = "uid-1"),
            FakeRunner(result = CloudDreamSyncResult.Skipped(CloudDreamSkipReason.SIGNED_OUT)),
            this,
        )
        signedOut.syncNow()
        assertEquals(CloudDreamBookmarkSyncState.Idle, signedOut.state.value)
    }

    // ----------------------------------------------------------------- overlap

    @Test
    fun `overlapping syncs do not run concurrently`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val runner = FakeRunner().apply { this.gate = gate }
        val svc = service(FakeSession(currentUid = "uid-1"), runner, this)

        val first = async(Dispatchers.Default) { svc.syncNow() }
        // Give the first pass time to take the lock.
        while (svc.state.value != CloudDreamBookmarkSyncState.Syncing) { /* spin */ }

        val second = svc.syncNow()

        gate.complete(Unit)
        val firstResult = withTimeout(5_000) { first.await() }

        assertEquals(
            "the second call must be dropped, not queued",
            CloudDreamSyncResult.Skipped(CloudDreamSkipReason.ALREADY_SYNCING),
            second,
        )
        assertTrue(firstResult is CloudDreamSyncResult.Success)
        assertEquals(1, runner.calls)
        assertEquals("passes must never overlap", 1, runner.maxConcurrent)
    }

    @Test
    fun `a new sync can start once the previous one finished`() = runTest {
        val runner = FakeRunner()
        val svc = service(FakeSession(currentUid = "uid-1"), runner, this)

        svc.syncNow()
        svc.syncNow()

        assertEquals(2, runner.calls)
    }

    // ---------------------------------------------------------- sign-in trigger

    @Test
    fun `signing in triggers exactly one sync`() = runTest {
        val runner = FakeRunner()
        val svc = service(FakeSession(currentUid = "uid-1"), runner, this)

        svc.onSessionUserChanged("uid-1")
        testScheduler.advanceUntilIdle()

        assertEquals(1, runner.calls)
    }

    @Test
    fun `a repeated session callback for the same account does not sync again`() = runTest {
        val runner = FakeRunner()
        val svc = service(FakeSession(currentUid = "uid-1"), runner, this)

        // This is what Firebase does: fire on registration, then again on every change.
        svc.onSessionUserChanged("uid-1")
        svc.onSessionUserChanged("uid-1")
        svc.onSessionUserChanged("uid-1")
        testScheduler.advanceUntilIdle()

        assertEquals(1, runner.calls)
    }

    // ---------------------------------------------------------- startup trigger

    @Test
    fun `an already signed-in startup triggers exactly once`() = runTest {
        val runner = FakeRunner()
        val svc = service(FakeSession(currentUid = "uid-1"), runner, this)

        // Both the auth listener and the explicit startup call fire; they must collapse.
        svc.onSessionUserChanged("uid-1")
        svc.onStartup()
        svc.onStartup()
        testScheduler.advanceUntilIdle()

        assertEquals(1, runner.calls)
    }

    @Test
    fun `a signed-out startup triggers nothing`() = runTest {
        val runner = FakeRunner()
        val svc = service(FakeSession(currentUid = null), runner, this)

        svc.onStartup()
        testScheduler.advanceUntilIdle()

        assertEquals(0, runner.calls)
    }

    @Test
    fun `startup does nothing when Firebase is unavailable`() = runTest {
        val runner = FakeRunner()
        val svc = service(FakeSession(isAvailable = false, currentUid = "uid-1"), runner, this)

        svc.onStartup()
        testScheduler.advanceUntilIdle()

        assertEquals(0, runner.calls)
    }

    // ------------------------------------------------------------- sign-out

    @Test
    fun `signing out does not trigger a sync and does not upload`() = runTest {
        val runner = FakeRunner()
        val svc = service(FakeSession(currentUid = "uid-1"), runner, this)

        svc.onSignedOut()
        testScheduler.advanceUntilIdle()

        assertEquals("sign-out must never upload", 0, runner.calls)
    }

    @Test
    fun `signing out resets the in-memory state`() = runTest {
        val runner = FakeRunner()
        val svc = service(FakeSession(currentUid = "uid-1"), runner, this)

        svc.syncNow()
        assertEquals(CloudDreamBookmarkSyncState.Success, svc.state.value)

        svc.onSignedOut()

        assertEquals(CloudDreamBookmarkSyncState.Idle, svc.state.value)
    }

    @Test
    fun `signing in again after a sign-out syncs again`() = runTest {
        val runner = FakeRunner()
        val svc = service(FakeSession(currentUid = "uid-1"), runner, this)

        svc.onSessionUserChanged("uid-1")
        testScheduler.advanceUntilIdle()
        svc.onSignedOut()
        svc.onSessionUserChanged("uid-1")
        testScheduler.advanceUntilIdle()

        assertEquals(2, runner.calls)
    }

    @Test
    fun `switching to a different account syncs again`() = runTest {
        val runner = FakeRunner()
        val svc = service(FakeSession(currentUid = "uid-1"), runner, this)

        svc.onSessionUserChanged("uid-1")
        testScheduler.advanceUntilIdle()
        svc.onSessionUserChanged("uid-2")
        testScheduler.advanceUntilIdle()

        assertEquals(2, runner.calls)
    }

    @Test
    fun `a session change to null is treated as a sign-out`() = runTest {
        val runner = FakeRunner()
        val svc = service(FakeSession(currentUid = "uid-1"), runner, this)

        svc.syncNow()
        svc.onSessionUserChanged(null)
        testScheduler.advanceUntilIdle()

        assertEquals(1, runner.calls)
        assertEquals(CloudDreamBookmarkSyncState.Idle, svc.state.value)
    }
}
