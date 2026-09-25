package com.lagradost.clouddream.auth

import com.google.firebase.auth.FirebaseUser

/**
 * Read-only snapshot of a CloudDream account.
 *
 * Deliberately a plain data holder: it never carries a password, an ID token or
 * a refresh token, and it is never persisted to disk. Session state lives only in
 * memory here; Firebase itself keeps the authoritative session.
 */
data class CloudDreamUser(
    val uid: String,
    val email: String?,
    val isEmailVerified: Boolean,
    val displayName: String?,
) {
    internal companion object {
        fun fromOrNull(user: FirebaseUser?): CloudDreamUser? = user?.let {
            CloudDreamUser(
                uid = it.uid,
                email = it.email,
                isEmailVerified = it.isEmailVerified,
                displayName = it.displayName,
            )
        }
    }
}
