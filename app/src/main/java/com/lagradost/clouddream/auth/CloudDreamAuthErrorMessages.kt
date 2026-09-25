package com.lagradost.clouddream.auth

import androidx.annotation.StringRes
import com.lagradost.cloudstream3.R

/**
 * Maps a [CloudDreamAuthError] to a localized, user-facing string.
 *
 * [CloudDreamAuthError.message] is deliberately a short developer/log-facing description,
 * so it must never be shown to the user verbatim (see its KDoc). Every enum value is
 * mapped here so a new upstream error code can never leave the UI without a message.
 */
@StringRes
fun CloudDreamAuthError.toMessageRes(): Int = when (this) {
    CloudDreamAuthError.NOT_CONFIGURED -> R.string.clouddream_account_unavailable
    CloudDreamAuthError.UNAVAILABLE -> R.string.clouddream_error_service_unavailable
    CloudDreamAuthError.INVALID_INPUT -> R.string.clouddream_error_invalid_input
    CloudDreamAuthError.INVALID_EMAIL -> R.string.clouddream_error_email_invalid
    CloudDreamAuthError.INVALID_PASSWORD -> R.string.clouddream_error_invalid_password
    CloudDreamAuthError.INVALID_CREDENTIALS -> R.string.clouddream_error_invalid_credentials
    CloudDreamAuthError.EMAIL_ALREADY_IN_USE -> R.string.clouddream_error_email_in_use
    CloudDreamAuthError.WEAK_PASSWORD -> R.string.clouddream_error_weak_password
    CloudDreamAuthError.OPERATION_NOT_ALLOWED -> R.string.clouddream_error_operation_not_allowed
    CloudDreamAuthError.USER_DISABLED -> R.string.clouddream_error_user_disabled
    CloudDreamAuthError.EMAIL_NOT_VERIFIED -> R.string.clouddream_error_email_not_verified
    CloudDreamAuthError.USER_NOT_FOUND -> R.string.clouddream_error_user_not_found
    CloudDreamAuthError.TOO_MANY_REQUESTS -> R.string.clouddream_error_too_many_requests
    CloudDreamAuthError.NETWORK -> R.string.clouddream_error_network
    CloudDreamAuthError.TIMEOUT -> R.string.clouddream_error_timeout
    CloudDreamAuthError.CREDENTIAL_TOO_OLD -> R.string.clouddream_error_sign_in_again
    CloudDreamAuthError.QUOTA_EXCEEDED -> R.string.clouddream_error_quota_exceeded
    CloudDreamAuthError.UNKNOWN -> R.string.clouddream_error_unknown
}
