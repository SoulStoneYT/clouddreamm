package com.lagradost.clouddream

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

/**
 * CloudDream entry point (Stage 1: Firebase foundation only).
 *
 * Initializes the default [FirebaseApp] programmatically from [CloudDreamConfig]
 * when, and only when, configuration is present. The Google Services Gradle
 * plugin and google-services.json are intentionally not used so that:
 * - builds succeed on machines without Firebase configuration,
 * - no Firebase configuration file needs to be committed,
 * - CloudStream keeps working fully offline / without an account.
 *
 * All failures are swallowed after logging: a broken or missing Firebase
 * setup must never prevent CloudStream from launching or playing media.
 */
object CloudDream {

    /** True once a FirebaseApp is available for CloudDream to use. */
    @Volatile
    var isFirebaseInitialized: Boolean = false
        private set

    /**
     * Idempotent, crash-safe initialization. Safe to call multiple times and
     * safe to call when Firebase was never configured.
     */
    fun init(context: Context) {
        try {
            if (isFirebaseInitialized) return

            if (!CloudDreamConfig.isConfigured) {
                CloudDreamLog.d("Firebase configuration absent; cloud features disabled")
                return
            }

            // Never create a duplicate FirebaseApp (e.g. re-entrant init calls).
            if (FirebaseApp.getApps(context).isNotEmpty()) {
                isFirebaseInitialized = true
                CloudDreamLog.d("Firebase already initialized; reusing existing instance")
                return
            }

            val options = FirebaseOptions.Builder()
                .setApiKey(CloudDreamConfig.apiKey)
                .setApplicationId(CloudDreamConfig.applicationId)
                .setProjectId(CloudDreamConfig.projectId)
                .apply {
                    if (CloudDreamConfig.storageBucket.isNotEmpty()) {
                        setStorageBucket(CloudDreamConfig.storageBucket)
                    }
                    if (CloudDreamConfig.messagingSenderId.isNotEmpty()) {
                        setGcmSenderId(CloudDreamConfig.messagingSenderId)
                    }
                }
                .build()

            FirebaseApp.initializeApp(context, options)
            isFirebaseInitialized = FirebaseApp.getApps(context).isNotEmpty()
            CloudDreamLog.d(
                if (isFirebaseInitialized) "Firebase initialized"
                else "Firebase initialization did not produce an instance"
            )
        } catch (t: Throwable) {
            // Firebase (or missing Google Play Services) must never crash CloudStream.
            isFirebaseInitialized = false
            CloudDreamLog.e("Firebase initialization failed; continuing without cloud", t)
        }
    }
}