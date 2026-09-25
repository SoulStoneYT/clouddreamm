package com.lagradost.clouddream.auth

import com.google.android.gms.tasks.Task
import com.google.firebase.auth.AuthResult
import com.google.firebase.auth.FirebaseAuth
import com.lagradost.clouddream.CloudDream
import com.lagradost.clouddream.CloudDreamLog
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** Outcome of a CloudDream authentication call. */
sealed interface CloudDreamAuthResult {
    /**
     * @param user the signed-in user, or null for calls that do not produce one
     * (such as [CloudDreamAuth.signOut]).
     */
    data class Success(val user: CloudDreamUser?) : CloudDreamAuthResult

    data class Failure(
        val error: CloudDreamAuthError,
        val cause: Throwable? = null,
    ) : CloudDreamAuthResult
}

/**
 * CloudDream authentication foundation (Phase 2A).
 *
 * Wraps Firebase Authentication behind a small, coroutine-friendly API. It never
 * throws, never requires an account, and never blocks: every call returns a
 * [CloudDreamAuthResult] and CloudStream keeps working when the user is signed out.
 *
 * This is the foundation only. No sign-in or registration UI is wired up yet, and
 * nothing here is called during app startup, so authentication stays opt-in.
 *
 * Nothing is persisted locally: passwords are passed straight to Firebase and never
 * stored, logged or cached by CloudDream, and no credential is written to disk.
 */
object CloudDreamAuth {

    /** True when Firebase is configured and initialized, so authentication can run. */
    val isAvailable: Boolean
        get() = CloudDream.isFirebaseInitialized

    /** The currently signed-in user, or null when signed out or unavailable. */
    val currentUser: CloudDreamUser?
        get() = authOrNull()?.currentUser?.let { CloudDreamUser.fromOrNull(it) }

    val isSignedIn: Boolean
        get() = currentUser != null

    /**
     * Observes session changes. The returned function removes the listener and must
     * be called by the owner. No-op when authentication is unavailable.
     */
    fun addUserStateListener(listener: (CloudDreamUser?) -> Unit): () -> Unit {
        val auth = authOrNull()
            ?: return { CloudDreamLog.d("Auth listener ignored: Firebase unavailable") }
        val authStateListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            listener(CloudDreamUser.fromOrNull(firebaseAuth.currentUser))
        }
        auth.addAuthStateListener(authStateListener)
        return { auth.removeAuthStateListener(authStateListener) }
    }

    /** Signs in an existing account. */
    suspend fun signInWithEmailAndPassword(email: String, password: String): CloudDreamAuthResult {
        val auth = authOrNull() ?: return unavailable()
        if (email.isBlank() || password.isBlank()) {
            return CloudDreamAuthResult.Failure(CloudDreamAuthError.INVALID_INPUT)
        }
        return runAuthCall(auth, "Sign-in failed") {
            auth.signInWithEmailAndPassword(email.trim(), password)
        }
    }

    /** Creates a new account and signs it in. */
    suspend fun createAccountWithEmailAndPassword(
        email: String,
        password: String,
    ): CloudDreamAuthResult {
        val auth = authOrNull() ?: return unavailable()
        if (email.isBlank() || password.isBlank()) {
            return CloudDreamAuthResult.Failure(CloudDreamAuthError.INVALID_INPUT)
        }
        return runAuthCall(auth, "Account creation failed") {
            auth.createUserWithEmailAndPassword(email.trim(), password)
        }
    }

    /** Signs the current user out. Succeeds trivially when already signed out. */
    suspend fun signOut(): CloudDreamAuthResult {
        val auth = authOrNull() ?: return unavailable()
        return try {
            auth.signOut()
            CloudDreamLog.d("Signed out")
            CloudDreamAuthResult.Success(null)
        } catch (t: Throwable) {
            CloudDreamLog.e("Sign-out failed", t)
            CloudDreamAuthResult.Failure(CloudDreamAuthError.UNKNOWN, t)
        }
    }

    private fun unavailable(): CloudDreamAuthResult =
        CloudDreamAuthResult.Failure(CloudDreamAuthError.NOT_CONFIGURED)

    private suspend fun runAuthCall(
        auth: FirebaseAuth,
        logContext: String,
        call: (FirebaseAuth) -> Task<AuthResult>,
    ): CloudDreamAuthResult = try {
        val result = call(auth).await()
        CloudDreamAuthResult.Success(CloudDreamUser.fromOrNull(result.user))
    } catch (t: Throwable) {
        val error = CloudDreamAuthError.from(t)
        CloudDreamLog.e("$logContext: ${error.name}", t)
        CloudDreamAuthResult.Failure(error, t)
    }

    /**
     * Returns the default [FirebaseAuth] only when CloudDream actually initialized
     * Firebase, so an unconfigured build fails softly instead of throwing.
     */
    private fun authOrNull(): FirebaseAuth? {
        if (!CloudDream.isFirebaseInitialized) return null
        return try {
            FirebaseAuth.getInstance()
        } catch (t: Throwable) {
            CloudDreamLog.e("FirebaseAuth unavailable", t)
            null
        }
    }
}

/**
 * Awaits a Google Play services [Task] from a coroutine.
 *
 * Implemented here instead of adding kotlinx-coroutines-play-services, to keep the
 * CloudDream dependency surface unchanged.
 */
internal suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        if (!continuation.isActive) return@addOnCompleteListener
        val exception = task.exception
        if (exception != null) {
            continuation.resumeWithException(exception)
        } else {
            continuation.resume(task.result)
        }
    }
    addOnCanceledListener { continuation.cancel() }
}
