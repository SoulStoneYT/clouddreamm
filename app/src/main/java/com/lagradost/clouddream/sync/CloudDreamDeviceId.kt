package com.lagradost.clouddream.sync

import android.content.Context
import com.lagradost.clouddream.CloudDreamLog
import java.util.UUID

/**
 * A stable, privacy-preserving identifier for this CloudDream installation.
 *
 * ## How it is produced
 *
 * A random UUID generated on first use and then stored in a CloudDream-owned
 * `SharedPreferences` file. Nothing is derived from the device.
 *
 * ## Why it is built this way
 *
 * The requirements were: stable across normal restarts, no dangerous permissions, and no
 * hardware identifiers. That rules out the obvious Android candidates:
 *
 * - `ANDROID_ID` / `Settings.Secure.ANDROID_ID` is unique per app-signing-key *and* per
 *   user on the device, so it changes on a factory reset or a reinstall signed by a
 *   different key, and it is a device identifier.
 * - IMEI, serial and `Build.SERIAL` require privileged or dangerous permissions and are
 *   hardware identifiers.
 * - A MAC address needs `ACCESS_WIFI_STATE` and is hardware.
 *
 * A random UUID satisfies all of them trivially, and the one drawback — it is lost on a
 * reinstall — is correct behaviour here, because a reinstall is a new installation and
 * treating it as a new device avoids two unrelated installs inheriting one another's sync
 * metadata.
 *
 * ## Why it is stored outside CloudStream's preferences
 *
 * The value is kept in a dedicated file (`clouddream_device`), not in CloudStream's
 * `rebuild_preference` datastore or the default `SharedPreferences`. Both of those are
 * swept up by `BackupUtils` when the user shares or restores a backup, and restoring a
 * device id onto a second device would make two devices indistinguishable in sync
 * metadata. A private file is outside that surface entirely, and it also keeps
 * CloudDream out of CloudStream's key namespace.
 */
object CloudDreamDeviceId {

    private const val PREFS_NAME = "clouddream_device"
    private const val KEY_DEVICE_ID = "device_id"

    @Volatile
    private var cached: String? = null

    /**
     * The id for this installation, creating and persisting one on first call.
     *
     * Safe to call from any thread and safe to call before Firebase is configured: it
     * touches only local preferences and never the network.
     */
    fun get(context: Context): String {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val created = readOrCreate(context.applicationContext)
            cached = created
            return created
        }
    }

    /**
     * Forgets the cached id. Only useful for tests; the persisted value is intentionally
     * not cleared, because a stable id across restarts is the whole point.
     */
    internal fun clearCacheForTesting() {
        synchronized(this) { cached = null }
    }

    private fun readOrCreate(context: Context): String {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val existing = prefs.getString(KEY_DEVICE_ID, null)
            if (!existing.isNullOrBlank()) {
                existing
            } else {
                val generated = UUID.randomUUID().toString()
                prefs.edit().putString(KEY_DEVICE_ID, generated).apply()
                CloudDreamLog.d("Created CloudDream device id")
                generated
            }
        } catch (t: Throwable) {
            // A device id is only sync metadata, so never let it break a caller: fall
            // back to a per-process id rather than throwing.
            CloudDreamLog.e("Could not persist CloudDream device id", t)
            "ephemeral-${UUID.randomUUID()}"
        }
    }
}
