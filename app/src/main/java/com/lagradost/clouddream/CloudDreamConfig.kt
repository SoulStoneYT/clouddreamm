package com.lagradost.clouddream

import com.lagradost.cloudstream3.BuildConfig

/**
 * Build-time CloudDream Firebase configuration (Stage 1).
 *
 * Values are injected as BuildConfig fields from environment variables or
 * local.properties via the same pattern CloudStream already uses for
 * SIMKL/MAL/ANILIST keys (see app/build.gradle.kts). Nothing is hardcoded
 * here and no credentials are stored in the repository.
 *
 * Property / environment names:
 * - CLOUDDREAM_FIREBASE_API_KEY
 * - CLOUDDREAM_FIREBASE_APP_ID
 * - CLOUDDREAM_FIREBASE_PROJECT_ID
 * - CLOUDDREAM_FIREBASE_STORAGE_BUCKET   (optional)
 * - CLOUDDREAM_FIREBASE_MESSAGING_SENDER_ID (optional)
 *
 * Empty values mean "not configured": all CloudDream cloud features stay
 * disabled and CloudStream behaves exactly as before.
 */
object CloudDreamConfig {
    val apiKey: String = BuildConfig.CLOUDDREAM_FIREBASE_API_KEY.trim()
    val applicationId: String = BuildConfig.CLOUDDREAM_FIREBASE_APP_ID.trim()
    val projectId: String = BuildConfig.CLOUDDREAM_FIREBASE_PROJECT_ID.trim()
    val storageBucket: String = BuildConfig.CLOUDDREAM_FIREBASE_STORAGE_BUCKET.trim()
    val messagingSenderId: String = BuildConfig.CLOUDDREAM_FIREBASE_MESSAGING_SENDER_ID.trim()

    /**
     * FirebaseOptions requires an API key, an application ID and a project ID.
     * Storage bucket and messaging sender ID are optional for Stage 1.
     */
    val isConfigured: Boolean
        get() = apiKey.isNotEmpty() && applicationId.isNotEmpty() && projectId.isNotEmpty()
}