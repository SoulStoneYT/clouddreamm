package com.lagradost.clouddream.sync

import android.content.Context
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.lagradost.clouddream.CloudDream
import com.lagradost.clouddream.CloudDreamLog
import com.lagradost.clouddream.auth.CloudDreamAuth
import com.lagradost.clouddream.auth.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger

/**
 * Firestore-backed [CloudDreamSyncManager].
 *
 * This is the only file in CloudDream that knows Firestore exists. Everything above it
 * works in terms of records and results; everything below it is the SDK.
 *
 * ## Failure policy
 *
 * Firestore calls are allowed to fail, and every one of them is contained here:
 * unconfigured build, signed out, Firestore not enabled in the console, security rules
 * denying access, offline. None of those escape as an exception, and none of them can
 * affect local playback, because nothing local calls this class yet.
 */
class FirestoreCloudDreamSyncManager(context: Context) : CloudDreamSyncManager {

    private val appContext = context.applicationContext

    private val _state = MutableStateFlow(
        CloudDreamSyncState(CloudDreamSyncAvailability.NOT_CONFIGURED)
    )
    override val state: StateFlow<CloudDreamSyncState> = _state.asStateFlow()

    private val inFlight = AtomicInteger(0)

    /**
     * Builds record metadata stamped with this installation's device id and the current
     * time, so a caller never has to remember to set either one.
     *
     * This is the only place the device identity is resolved, which keeps
     * [CloudDreamDeviceId] out of the callers and guarantees every record written through
     * this manager carries a valid id.
     */
    fun newMeta(now: Long = System.currentTimeMillis()): CloudDreamRecordMeta =
        CloudDreamRecordMeta(updatedAt = now, deviceId = CloudDreamDeviceId.get(appContext))

    private fun currentAvailability(): CloudDreamSyncAvailability = when {
        !CloudDream.isFirebaseInitialized -> CloudDreamSyncAvailability.NOT_CONFIGURED
        CloudDreamAuth.currentUser == null -> CloudDreamSyncAvailability.SIGNED_OUT
        else -> CloudDreamSyncAvailability.READY
    }

    private fun publish() {
        _state.value = CloudDreamSyncState(currentAvailability(), inFlight.get() > 0)
    }

    /**
     * Runs [block] with a ready Firestore handle, or returns why it could not.
     *
     * `FirebaseFirestore.getInstance()` throws when the Firebase app is missing, and the
     * first call also throws when Firestore was never enabled in the console, so both are
     * contained here instead of at every call site.
     */
    private suspend fun <T> withFirestore(
        block: suspend (uid: String, db: FirebaseFirestore) -> CloudDreamSyncResult<T>,
    ): CloudDreamSyncResult<T> {
        if (!CloudDream.isFirebaseInitialized) {
            return CloudDreamSyncResult.Skipped(CloudDreamSkipReason.NOT_CONFIGURED)
        }
        // Cloud identity is the Firebase Auth uid, which is exactly what the published
        // security rules scope to.
        val uid = CloudDreamAuth.currentUser?.uid
            ?: return CloudDreamSyncResult.Skipped(CloudDreamSkipReason.SIGNED_OUT)

        val db = try {
            FirebaseFirestore.getInstance()
        } catch (t: Throwable) {
            CloudDreamLog.e("Firestore unavailable", t)
            return CloudDreamSyncResult.Skipped(CloudDreamSkipReason.FIRESTORE_UNAVAILABLE)
        }

        inFlight.incrementAndGet()
        publish()
        return try {
            block(uid, db)
        } catch (t: Throwable) {
            if (t is CancellationException) {
                CloudDreamSyncResult.Failure(CloudDreamSyncError.CANCELLED, t)
            } else {
                CloudDreamLog.e("Firestore operation failed", t)
                CloudDreamSyncResult.Failure(CloudDreamSyncError.FIRESTORE_FAILURE, t)
            }
        } finally {
            inFlight.decrementAndGet()
            publish()
        }
    }

    override suspend fun ensureUserDocument(): CloudDreamSyncResult<Unit> = withFirestore { uid, db ->
        val ref = db.collection(CloudDreamFirestore.USERS).document(uid)
        // createdAt is written once; lastSeenAt is refreshed on every call. A read is
        // needed to keep that promise, which is acceptable because this is called rarely
        // and never from a latency-sensitive path.
        if (ref.get().await().exists()) {
            ref.set(stamp(includeCreatedAt = false), SetOptions.merge()).await()
        } else {
            ref.set(stamp(includeCreatedAt = true)).await()
        }
        CloudDreamSyncResult.Success(Unit)
    }

    private fun stamp(includeCreatedAt: Boolean): Map<String, Any?> = buildMap {
        if (includeCreatedAt) put(CloudDreamFirestore.FIELD_CREATED_AT, System.currentTimeMillis())
        put(CloudDreamFirestore.FIELD_LAST_SEEN_AT, System.currentTimeMillis())
        put(CloudDreamFirestore.FIELD_SCHEMA_VERSION, CloudDreamRecordMeta.SCHEMA_VERSION)
    }

    override suspend fun putProgress(record: CloudDreamProgressRecord): CloudDreamSyncResult<Unit> =
        writeRecord(CloudDreamFirestore.PROGRESS, record.key.documentId) { progressDoc(record) }

    override suspend fun putBookmark(record: CloudDreamBookmarkRecord): CloudDreamSyncResult<Unit> =
        writeRecord(CloudDreamFirestore.BOOKMARKS, record.key.documentId) { bookmarkDoc(record) }

    override suspend fun putHistory(record: CloudDreamHistoryRecord): CloudDreamSyncResult<Unit> =
        writeRecord(CloudDreamFirestore.HISTORY, record.key.documentId) { historyDoc(record) }

    private suspend fun writeRecord(
        collection: String,
        documentId: String,
        build: () -> Map<String, Any?>,
    ): CloudDreamSyncResult<Unit> = withFirestore { uid, db ->
        // Replaces the document rather than merging: a record is a complete snapshot,
        // and merging would leave fields behind when one becomes null.
        db.collection(CloudDreamFirestore.USERS)
            .document(uid)
            .collection(collection)
            .document(documentId)
            .set(build().withoutNulls())
            .await()
        CloudDreamSyncResult.Success(Unit)
    }

    override suspend fun getProgress(key: CloudDreamMediaKey): CloudDreamSyncResult<CloudDreamProgressRecord?> =
        readRecord(CloudDreamFirestore.PROGRESS, key) { progressFrom(it) }

    override suspend fun getBookmark(key: CloudDreamMediaKey): CloudDreamSyncResult<CloudDreamBookmarkRecord?> =
        readRecord(CloudDreamFirestore.BOOKMARKS, key) { bookmarkFrom(it) }

    override suspend fun getHistory(key: CloudDreamMediaKey): CloudDreamSyncResult<CloudDreamHistoryRecord?> =
        readRecord(CloudDreamFirestore.HISTORY, key) { historyFrom(it) }

    private suspend fun <T> readRecord(
        collection: String,
        key: CloudDreamMediaKey,
        convert: (Map<String, Any?>) -> T?,
    ): CloudDreamSyncResult<T?> = withFirestore { uid, db ->
        val snapshot = db.collection(CloudDreamFirestore.USERS)
            .document(uid)
            .collection(collection)
            .document(key.documentId)
            .get()
            .await()
        CloudDreamSyncResult.Success(if (snapshot.exists()) convert(snapshot.fields()) else null)
    }

    override suspend fun listProgress(): CloudDreamSyncResult<List<CloudDreamProgressRecord>> =
        listRecords(CloudDreamFirestore.PROGRESS) { progressFrom(it) }

    override suspend fun listBookmarks(): CloudDreamSyncResult<List<CloudDreamBookmarkRecord>> =
        listRecords(CloudDreamFirestore.BOOKMARKS) { bookmarkFrom(it) }

    override suspend fun listHistory(): CloudDreamSyncResult<List<CloudDreamHistoryRecord>> =
        listRecords(CloudDreamFirestore.HISTORY) { historyFrom(it) }

    private suspend fun <T> listRecords(
        collection: String,
        convert: (Map<String, Any?>) -> T?,
    ): CloudDreamSyncResult<List<T>> = withFirestore { uid, db ->
        val snapshots = db.collection(CloudDreamFirestore.USERS)
            .document(uid)
            .collection(collection)
            .get()
            .await()
        CloudDreamSyncResult.Success(snapshots.documents.mapNotNull { convert(it.fields()) })
    }

    // ---- document mapping ------------------------------------------------------------

    private fun progressDoc(record: CloudDreamProgressRecord): Map<String, Any?> = buildMap {
        putMediaFields(record.key, record.meta)
        put(CloudDreamFirestore.FIELD_POSITION_MS, record.positionMs)
        put(CloudDreamFirestore.FIELD_DURATION_MS, record.durationMs)
        put(CloudDreamFirestore.FIELD_WATCHED, record.watched)
        put(CloudDreamFirestore.FIELD_PARENT_API_NAME, record.parentApiName)
        put(CloudDreamFirestore.FIELD_PARENT_UNIQUE_URL, record.parentUniqueUrl)
        record.localId?.let { put(CloudDreamFirestore.FIELD_LOCAL_ID, it) }
    }

    private fun bookmarkDoc(record: CloudDreamBookmarkRecord): Map<String, Any?> = buildMap {
        putMediaFields(record.key, record.meta)
        put(CloudDreamFirestore.FIELD_NAME, record.name)
        put(CloudDreamFirestore.FIELD_WATCH_TYPE, record.watchType)
        put(CloudDreamFirestore.FIELD_POSTER_URL, record.posterUrl)
        put(CloudDreamFirestore.FIELD_PLOT, record.plot)
        put(CloudDreamFirestore.FIELD_TAGS, record.tags)
        put(CloudDreamFirestore.FIELD_BOOKMARKED_AT, record.bookmarkedAt)
        record.localId?.let { put(CloudDreamFirestore.FIELD_LOCAL_ID, it) }
    }

    private fun historyDoc(record: CloudDreamHistoryRecord): Map<String, Any?> = buildMap {
        putMediaFields(record.key, record.meta)
        put(CloudDreamFirestore.FIELD_IS_FROM_DOWNLOAD, record.isFromDownload)
        // History carries its own newer clock for the continue-watching sort. It shares the
        // updatedAt field name so a merge decision can compare like with like, and
        // readHistory falls back to the shared meta clock when it is absent.
        put(CloudDreamFirestore.FIELD_UPDATED_AT, record.updatedAt)
        record.localId?.let { put(CloudDreamFirestore.FIELD_LOCAL_ID, it) }
    }

    private fun MutableMap<String, Any?>.putMediaFields(
        key: CloudDreamMediaKey,
        meta: CloudDreamRecordMeta,
    ) {
        put(CloudDreamFirestore.FIELD_MEDIA_ID, key.documentId)
        put(CloudDreamFirestore.FIELD_CANONICAL_KEY, key.canonical)
        put(CloudDreamFirestore.FIELD_API_NAME, key.apiName)
        put(CloudDreamFirestore.FIELD_TYPE, key.type)
        put(CloudDreamFirestore.FIELD_UNIQUE_URL, key.uniqueUrl)
        key.year?.let { put(CloudDreamFirestore.FIELD_YEAR, it) }
        key.season?.let { put(CloudDreamFirestore.FIELD_SEASON, it) }
        key.episode?.let { put(CloudDreamFirestore.FIELD_EPISODE, it) }
        put(CloudDreamFirestore.FIELD_UPDATED_AT, meta.updatedAt)
        put(CloudDreamFirestore.FIELD_DEVICE_ID, meta.deviceId)
        put(CloudDreamFirestore.FIELD_SCHEMA, CloudDreamRecordMeta.SCHEMA_VERSION)
    }

    private fun progressFrom(data: Map<String, Any?>): CloudDreamProgressRecord? {
        val key = keyFrom(data) ?: return null
        val meta = metaFrom(data) ?: return null
        return CloudDreamProgressRecord(
            key = key,
            positionMs = data.long(CloudDreamFirestore.FIELD_POSITION_MS) ?: return null,
            durationMs = data.long(CloudDreamFirestore.FIELD_DURATION_MS) ?: return null,
            watched = data[CloudDreamFirestore.FIELD_WATCHED] as? Boolean ?: false,
            parentApiName = data.string(CloudDreamFirestore.FIELD_PARENT_API_NAME),
            parentUniqueUrl = data.string(CloudDreamFirestore.FIELD_PARENT_UNIQUE_URL),
            localId = data.int(CloudDreamFirestore.FIELD_LOCAL_ID),
            meta = meta,
        )
    }

    private fun bookmarkFrom(data: Map<String, Any?>): CloudDreamBookmarkRecord? {
        val key = keyFrom(data) ?: return null
        val meta = metaFrom(data) ?: return null
        return CloudDreamBookmarkRecord(
            key = key,
            name = data.string(CloudDreamFirestore.FIELD_NAME) ?: return null,
            watchType = data.string(CloudDreamFirestore.FIELD_WATCH_TYPE) ?: return null,
            posterUrl = data.string(CloudDreamFirestore.FIELD_POSTER_URL),
            plot = data.string(CloudDreamFirestore.FIELD_PLOT),
            tags = (data[CloudDreamFirestore.FIELD_TAGS] as? List<*>)?.mapNotNull { it as? String },
            bookmarkedAt = data.long(CloudDreamFirestore.FIELD_BOOKMARKED_AT) ?: return null,
            localId = data.int(CloudDreamFirestore.FIELD_LOCAL_ID),
            meta = meta,
        )
    }

    private fun historyFrom(data: Map<String, Any?>): CloudDreamHistoryRecord? {
        val key = keyFrom(data) ?: return null
        val meta = metaFrom(data) ?: return null
        return CloudDreamHistoryRecord(
            key = key,
            season = data.int(CloudDreamFirestore.FIELD_SEASON),
            episode = data.int(CloudDreamFirestore.FIELD_EPISODE),
            updatedAt = data.long(CloudDreamFirestore.FIELD_UPDATED_AT) ?: meta.updatedAt,
            isFromDownload = data[CloudDreamFirestore.FIELD_IS_FROM_DOWNLOAD] as? Boolean ?: false,
            localId = data.int(CloudDreamFirestore.FIELD_LOCAL_ID),
            meta = meta,
        )
    }

    private fun keyFrom(data: Map<String, Any?>): CloudDreamMediaKey? {
        val apiName = data.string(CloudDreamFirestore.FIELD_API_NAME) ?: return null
        val type = data.string(CloudDreamFirestore.FIELD_TYPE) ?: return null
        val uniqueUrl = data.string(CloudDreamFirestore.FIELD_UNIQUE_URL) ?: return null
        return CloudDreamMediaKey(
            apiName = apiName,
            type = type,
            uniqueUrl = uniqueUrl,
            year = data.int(CloudDreamFirestore.FIELD_YEAR),
            season = data.int(CloudDreamFirestore.FIELD_SEASON),
            episode = data.int(CloudDreamFirestore.FIELD_EPISODE),
        )
    }

    private fun metaFrom(data: Map<String, Any?>): CloudDreamRecordMeta? {
        val updatedAt = data.long(CloudDreamFirestore.FIELD_UPDATED_AT) ?: return null
        val deviceId = data.string(CloudDreamFirestore.FIELD_DEVICE_ID) ?: return null
        return CloudDreamRecordMeta(updatedAt = updatedAt, deviceId = deviceId)
    }
}

/**
 * `DocumentSnapshot.getData()` is a Java `@Nullable Map`, so treat a missing body as an
 * empty document. Every converter then returns null and the record is skipped, which is
 * the right outcome for an unreadable document.
 */
private fun DocumentSnapshot.fields(): Map<String, Any?> = data ?: emptyMap()

/**
 * Firestore rejects a map containing null values, so they are dropped before the write.
 * An absent field and a null field mean the same thing in this schema.
 */
private fun Map<String, Any?>.withoutNulls(): Map<String, Any> {
    val out = LinkedHashMap<String, Any>(size)
    for ((field, value) in this) {
        if (value != null) out[field] = value
    }
    return out
}

private fun Map<String, Any?>.string(field: String): String? = this[field] as? String

private fun Map<String, Any?>.long(field: String): Long? = (this[field] as? Number)?.toLong()

private fun Map<String, Any?>.int(field: String): Int? = (this[field] as? Number)?.toInt()
