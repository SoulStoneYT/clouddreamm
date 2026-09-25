package com.lagradost.clouddream

import android.util.Log
import com.lagradost.cloudstream3.BuildConfig

/**
 * Minimal logging helper for CloudDream development output.
 *
 * Messages are only emitted in debug builds so release builds stay quiet and
 * cannot accidentally leak CloudDream diagnostics. Tag is "CloudSync" to make
 * `adb logcat -s CloudSync` the single filter for synchronization debugging.
 *
 * Never log: passwords, auth tokens, emails, or raw Firebase credentials.
 */
object CloudDreamLog {
    private const val TAG = "CloudSync"

    fun d(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }

    fun w(message: String) {
        if (BuildConfig.DEBUG) Log.w(TAG, message)
    }

    fun e(message: String, throwable: Throwable? = null) {
        if (BuildConfig.DEBUG) Log.e(TAG, message, throwable)
    }
}