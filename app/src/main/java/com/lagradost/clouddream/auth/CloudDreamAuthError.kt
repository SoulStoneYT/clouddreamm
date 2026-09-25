package com.lagradost.clouddream.auth

import com.google.firebase.auth.FirebaseAuthException

/**
 * Small, UI-agnostic mapping of the Firebase Auth failures CloudDream can hit.
 *
 * [message] is a short developer/log-facing description only. Account screens are
 * expected to map these to their own localized strings later, so nothing here
 * should be shown to the user verbatim.
 */
enum class CloudDreamAuthError(val message: String) {
    /** Firebase was never configured, so authentication cannot run at all. */
    NOT_CONFIGURED("Cloud features are not available in this build"),

    /** CloudDream is not usable right now; retrying later may succeed. */
    UNAVAILABLE("Cloud service is temporarily unavailable"),

    /** Caller supplied an empty email or password; no network call was made. */
    INVALID_INPUT("Email and password are required"),

    INVALID_EMAIL("The email address is not valid"),
    INVALID_PASSWORD("The password is not valid"),
    INVALID_CREDENTIALS("Incorrect email or password"),
    EMAIL_ALREADY_IN_USE("An account already exists for that email"),
    WEAK_PASSWORD("The password is too weak"),
    OPERATION_NOT_ALLOWED("Email and password sign-in is disabled for this project"),
    USER_DISABLED("This account has been disabled"),
    EMAIL_NOT_VERIFIED("This email address has not been verified yet"),
    USER_NOT_FOUND("No account was found for that email"),
    TOO_MANY_REQUESTS("Too many attempts, please try again later"),
    NETWORK("No network connection"),
    TIMEOUT("The request timed out"),
    CREDENTIAL_TOO_OLD("Please sign in again"),
    QUOTA_EXCEEDED("The authentication quota has been exceeded"),
    UNKNOWN("Authentication failed");

    internal companion object {
        fun from(throwable: Throwable?): CloudDreamAuthError = when {
            throwable == null -> UNKNOWN
            throwable is FirebaseAuthException -> fromErrorCode(throwable.errorCode)
            else -> UNKNOWN
        }

        private fun fromErrorCode(errorCode: String): CloudDreamAuthError = when (errorCode) {
            FirebaseAuthErrorCode.INVALID_EMAIL -> INVALID_EMAIL
            FirebaseAuthErrorCode.INVALID_PASSWORD -> INVALID_PASSWORD
            FirebaseAuthErrorCode.INVALID_LOGIN_CREDENTIALS -> INVALID_CREDENTIALS
            FirebaseAuthErrorCode.INVALID_CREDENTIAL -> INVALID_CREDENTIALS
            FirebaseAuthErrorCode.EMAIL_ALREADY_IN_USE -> EMAIL_ALREADY_IN_USE
            FirebaseAuthErrorCode.WEAK_PASSWORD -> WEAK_PASSWORD
            FirebaseAuthErrorCode.OPERATION_NOT_ALLOWED -> OPERATION_NOT_ALLOWED
            FirebaseAuthErrorCode.USER_DISABLED -> USER_DISABLED
            FirebaseAuthErrorCode.EMAIL_NOT_VERIFIED -> EMAIL_NOT_VERIFIED
            FirebaseAuthErrorCode.USER_NOT_FOUND -> USER_NOT_FOUND
            FirebaseAuthErrorCode.TOO_MANY_REQUESTS -> TOO_MANY_REQUESTS
            FirebaseAuthErrorCode.NETWORK_REQUEST_FAILED -> NETWORK
            FirebaseAuthErrorCode.TIMEOUT -> TIMEOUT
            FirebaseAuthErrorCode.CREDENTIAL_TOO_OLD_LOGIN_AGAIN -> CREDENTIAL_TOO_OLD
            FirebaseAuthErrorCode.QUOTA_EXCEEDED -> QUOTA_EXCEEDED
            else -> UNKNOWN
        }
    }
}

/**
 * Raw `FirebaseAuthException.getErrorCode()` values.
 *
 * The SDK does not expose these as public constants, so they are kept here as
 * literals. An unrecognized code maps to [CloudDreamAuthError.UNKNOWN], so a new
 * upstream code can never crash the caller.
 */
private object FirebaseAuthErrorCode {
    const val INVALID_EMAIL = "ERROR_INVALID_EMAIL"
    const val INVALID_PASSWORD = "ERROR_INVALID_PASSWORD"
    const val INVALID_LOGIN_CREDENTIALS = "ERROR_INVALID_LOGIN_CREDENTIALS"
    const val INVALID_CREDENTIAL = "ERROR_INVALID_CREDENTIAL"
    const val EMAIL_ALREADY_IN_USE = "ERROR_EMAIL_ALREADY_IN_USE"
    const val WEAK_PASSWORD = "ERROR_WEAK_PASSWORD"
    const val OPERATION_NOT_ALLOWED = "ERROR_OPERATION_NOT_ALLOWED"
    const val USER_DISABLED = "ERROR_USER_DISABLED"
    const val EMAIL_NOT_VERIFIED = "ERROR_EMAIL_NOT_VERIFIED"
    const val USER_NOT_FOUND = "ERROR_USER_NOT_FOUND"
    const val TOO_MANY_REQUESTS = "ERROR_TOO_MANY_REQUESTS"
    const val NETWORK_REQUEST_FAILED = "ERROR_NETWORK_REQUEST_FAILED"
    const val TIMEOUT = "ERROR_TIMEOUT"
    const val CREDENTIAL_TOO_OLD_LOGIN_AGAIN = "ERROR_CREDENTIAL_TOO_OLD_LOGIN_AGAIN"
    const val QUOTA_EXCEEDED = "ERROR_QUOTA_EXCEEDED"
}
