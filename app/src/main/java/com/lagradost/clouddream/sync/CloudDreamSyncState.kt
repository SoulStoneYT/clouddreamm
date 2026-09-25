package com.lagradost.clouddream.sync

/**
 * Whether cloud sync can run at all right now.
 *
 * Every one of these states is a safe no-op rather than an error: a signed-out or
 * unconfigured build must behave exactly like a build with no cloud code at all.
 */
enum class CloudDreamSyncAvailability {
    /** Firebase was never configured for this build. */
    NOT_CONFIGURED,

    /** Firebase is configured but nobody is signed in. */
    SIGNED_OUT,

    /** Configured and signed in; sync may run. */
    READY,
}

/** Immutable snapshot of the sync layer's state, safe to read from any thread. */
data class CloudDreamSyncState(
    val availability: CloudDreamSyncAvailability,
    val isSyncing: Boolean = false,
) {
    val isReady: Boolean
        get() = availability == CloudDreamSyncAvailability.READY
}

/** Why a call did nothing. Distinct from a failure: nothing went wrong. */
enum class CloudDreamSkipReason {
    /** Firebase is not configured for this build. */
    NOT_CONFIGURED,

    /** Nobody is signed in, so there is no account to sync to. */
    SIGNED_OUT,

    /** Firestore is not usable (for example the API was not enabled in the console). */
    FIRESTORE_UNAVAILABLE,

    /** A call was already in flight and this one was dropped. */
    ALREADY_SYNCING,
}

/** A non-Firebase-specific failure, so callers never see a Firestore type. */
enum class CloudDreamSyncError {
    /** Firestore rejected or could not complete the operation. */
    FIRESTORE_FAILURE,

    /** The call was cancelled, usually because the screen went away. */
    CANCELLED,

    /** Something unexpected went wrong. */
    UNKNOWN,
}

/**
 * Outcome of a sync call.
 *
 * [Skipped] is a first-class outcome on purpose: "no account" and "not configured" are
 * the normal state for most CloudStream installs, not failures, and must never surface
 * as an error to the user.
 */
sealed interface CloudDreamSyncResult<out T> {
    data class Success<T>(val value: T) : CloudDreamSyncResult<T>

    data class Skipped(val reason: CloudDreamSkipReason) : CloudDreamSyncResult<Nothing>

    data class Failure(
        val error: CloudDreamSyncError,
        val cause: Throwable? = null,
    ) : CloudDreamSyncResult<Nothing>
}
