package com.lagradost.clouddream.sync.local

import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.ui.WatchType
import com.lagradost.cloudstream3.utils.DataStoreHelper.BookmarkedData
import com.lagradost.clouddream.sync.CloudDreamBookmarkRecord
import com.lagradost.clouddream.sync.CloudDreamHistoryRecord
import com.lagradost.clouddream.sync.CloudDreamMediaKey
import com.lagradost.clouddream.sync.CloudDreamProgressRecord
import com.lagradost.clouddream.sync.CloudDreamRecordMeta
import com.lagradost.clouddream.sync.CloudDreamSkipReason
import com.lagradost.clouddream.sync.CloudDreamSyncAvailability
import com.lagradost.clouddream.sync.CloudDreamSyncError
import com.lagradost.clouddream.sync.CloudDreamSyncManager
import com.lagradost.clouddream.sync.CloudDreamSyncResult
import com.lagradost.clouddream.sync.CloudDreamSyncState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2D.1 — the bookmark ↔ title-watch-state bridge.
 *
 * Everything here runs on the JVM with no Android device, no Firebase project and no
 * credentials: the local side is an in-memory [LocalBookmarkStore] and the cloud side is
 * a [FakeSyncManager]. What is being locked down is the *mapping contract* — which is the
 * part that silently corrupts a library if it drifts.
 */
class CloudDreamBookmarkAdapterTest {

    companion object {
        private const val DEVICE_ID = "test-device"
    }

    // ---------------------------------------------------------------- fakes

    /**
     * In-memory stand-in for the `DataStoreHelper` slice bookmark sync needs.
     *
     * @param log shared ordered call log. A test that needs to assert *interleaving*
     *   between local and cloud operations must pass the same list to both fakes,
     *   since each otherwise records into its own.
     */
    private class FakeLocalStore(
        private val log: MutableList<String> = mutableListOf(),
    ) : LocalBookmarkStore {
        val data = mutableMapOf<Int, BookmarkedData>()
        val watchStates = mutableMapOf<Int, WatchType>()

        /** Ordered log of cloud/local calls, so ordering guarantees can be asserted. */
        val calls: MutableList<String> get() = log

        override fun watchStateIds(): List<Int> = watchStates.keys.sorted()
        override fun getWatchState(id: Int): WatchType = watchStates[id] ?: WatchType.NONE
        override fun getBookmarkData(id: Int?): BookmarkedData? = data[id]

        override fun setBookmarkData(id: Int?, data: BookmarkedData) {
            calls += "local.set(${requireNotNull(id)})"
            this.data[requireNotNull(id)] = data
        }

        override fun setWatchState(id: Int?, watchTypeId: Int) {
            val key = requireNotNull(id)
            val type = WatchType.fromInternalId(watchTypeId)
            if (type == WatchType.NONE) {
                watchStates.remove(key)
                data.remove(key)
            } else {
                watchStates[key] = type
            }
        }

        override fun deleteBookmarkData(id: Int?) {
            val key = requireNotNull(id)
            calls += "local.delete($key)"
            watchStates.remove(key)
            data.remove(key)
        }

        fun put(id: Int, data: BookmarkedData, watchType: WatchType) {
            this.data[id] = data
            watchStates[id] = watchType
        }
    }

    /** Records every call so the tests can assert the bridge actually talked to the cloud. */
    private class FakeSyncManager(
        private val log: MutableList<String> = mutableListOf(),
    ) : CloudDreamSyncManager {
        private val mutableState = MutableStateFlow(CloudDreamSyncState(CloudDreamSyncAvailability.READY))
        override val state: StateFlow<CloudDreamSyncState> get() = mutableState

        val bookmarks = mutableMapOf<String, CloudDreamBookmarkRecord>()
        val uploaded = mutableListOf<CloudDreamBookmarkRecord>()
        val deletedKeys = mutableListOf<String>()

        /** Ordered log of cloud/local calls, so test-ordering guarantees can be asserted. */
        val calls: MutableList<String> get() = log

        var listResult: CloudDreamSyncResult<List<CloudDreamBookmarkRecord>> =
            CloudDreamSyncResult.Success(emptyList())

        var deleteResult: CloudDreamSyncResult<Unit> = CloudDreamSyncResult.Success(Unit)

        override suspend fun ensureUserDocument(): CloudDreamSyncResult<Unit> =
            CloudDreamSyncResult.Success(Unit)

        override suspend fun putProgress(record: CloudDreamProgressRecord) = CloudDreamSyncResult.Success(Unit)
        override suspend fun putHistory(record: CloudDreamHistoryRecord) = CloudDreamSyncResult.Success(Unit)
        override suspend fun getProgress(key: CloudDreamMediaKey) = CloudDreamSyncResult.Success(null)
        override suspend fun getBookmark(key: CloudDreamMediaKey) =
            CloudDreamSyncResult.Success(bookmarks[key.documentId])

        override suspend fun getHistory(key: CloudDreamMediaKey) = CloudDreamSyncResult.Success(null)
        override suspend fun listProgress() = CloudDreamSyncResult.Success(emptyList<CloudDreamProgressRecord>())
        override suspend fun listHistory() = CloudDreamSyncResult.Success(emptyList<CloudDreamHistoryRecord>())

        override suspend fun putBookmark(record: CloudDreamBookmarkRecord): CloudDreamSyncResult<Unit> {
            uploaded += record
            bookmarks[record.key.documentId] = record
            return CloudDreamSyncResult.Success(Unit)
        }

        override suspend fun deleteBookmark(key: CloudDreamMediaKey): CloudDreamSyncResult<Unit> {
            calls += "cloud.delete(${key.documentId})"
            if (deleteResult is CloudDreamSyncResult.Success) {
                deletedKeys += key.documentId
                bookmarks.remove(key.documentId)
            }
            return deleteResult
        }

        override suspend fun listBookmarks() = listResult
    }

    // ------------------------------------------------------------- fixtures

    private fun bookmark(
        id: Int? = 4242,
        name: String = "Frieren: Beyond Journey's End",
        url: String = "anime:21",
        apiName: String = "Anime",
        type: TvType? = TvType.TvSeries,
        year: Int? = 2023,
        posterUrl: String? = "https://example.com/poster.jpg",
        plot: String? = "An elf goes on a journey.",
        tags: List<String>? = listOf("fantasy"),
        bookmarkedTime: Long = 1_000L,
        latestUpdatedTime: Long = 2_000L,
    ) = BookmarkedData(
        bookmarkedTime = bookmarkedTime,
        id = id,
        latestUpdatedTime = latestUpdatedTime,
        name = name,
        url = url,
        apiName = apiName,
        type = type,
        posterUrl = posterUrl,
        year = year,
        plot = plot,
        tags = tags,
    )

    private fun cloudRecord(
        url: String = "anime:21",
        apiName: String = "Anime",
        type: String = "TvSeries",
        year: Int? = 2023,
        watchType: String = "WATCHING",
        localId: Int? = 4242,
        name: String = "Frieren: Beyond Journey's End",
        updatedAt: Long = 2_000L,
        deviceId: String = "other-device",
        bookmarkedAt: Long = 1_000L,
    ) = CloudDreamBookmarkRecord(
        key = CloudDreamMediaKey.forTitle(apiName, type, url, year),
        name = name,
        watchType = watchType,
        posterUrl = "https://example.com/poster.jpg",
        plot = "An elf goes on a journey.",
        tags = listOf("fantasy"),
        bookmarkedAt = bookmarkedAt,
        localId = localId,
        meta = CloudDreamRecordMeta(updatedAt = updatedAt, deviceId = deviceId),
    )

    private fun adapter(
        local: LocalBookmarkStore,
        manager: CloudDreamSyncManager,
    ) = CloudDreamBookmarkAdapter(
        manager = manager,
        local = local,
        deviceId = { DEVICE_ID },
    )

    private fun snapshot(adapter: CloudDreamBookmarkAdapter): List<CloudDreamBookmarkRecord> {
        val result = runBlocking { adapter.snapshotBookmarks() }
        assertTrue("snapshot failed: $result", result is CloudDreamSyncResult.Success)
        return (result as CloudDreamSyncResult.Success).value
    }

    // -------------------------------------------------- local -> cloud mapping

    @Test
    fun `local bookmark and watch type become one cloud record`() {
        val record = BookmarkMapper.toCloud(bookmark(), WatchType.COMPLETED, DEVICE_ID)!!

        assertEquals("Frieren: Beyond Journey's End", record.name)
        assertEquals("COMPLETED", record.watchType)
        assertEquals("https://example.com/poster.jpg", record.posterUrl)
        assertEquals("An elf goes on a journey.", record.plot)
        assertEquals(listOf("fantasy"), record.tags)
        assertEquals(1_000L, record.bookmarkedAt)
        assertEquals(4242, record.localId)
        assertEquals(DEVICE_ID, record.meta.deviceId)
    }

    @Test
    fun `cloud key is built from apiName, type name, url and year`() {
        val record = BookmarkMapper.toCloud(
            bookmark(apiName = "Torrentio", type = TvType.Movie, url = "https://t.io/x", year = 1999),
            WatchType.WATCHING,
            DEVICE_ID,
        )!!

        assertEquals("Torrentio", record.key.apiName)
        assertEquals("Movie", record.key.type)
        assertEquals("https://t.io/x", record.key.uniqueUrl)
        assertEquals(1999, record.key.year)
    }

    @Test
    fun `cloud key is never the local integer id`() {
        val record = BookmarkMapper.toCloud(bookmark(id = 4242), WatchType.WATCHING, DEVICE_ID)!!

        assertNotEquals("4242", record.key.documentId)
        assertEquals(32, record.key.documentId.length)
    }

    @Test
    fun `updatedAt comes from latestUpdatedTime, not from upload time`() {
        val record = BookmarkMapper.toCloud(bookmark(latestUpdatedTime = 1_234_567L), WatchType.WATCHING, DEVICE_ID)!!

        assertEquals(1_234_567L, record.meta.updatedAt)
        // The wall clock is far past 1_234_567 ms since epoch; if the mapper had used
        // "now" the assertion above could not hold.
        assertNotEquals(System.currentTimeMillis(), record.meta.updatedAt)
    }

    @Test
    fun `a watch state with no bookmark metadata is skipped`() {
        assertNull(BookmarkMapper.toCloud(null, WatchType.WATCHING, DEVICE_ID))
    }

    @Test
    fun `WatchType NONE is never uploaded`() {
        assertNull(BookmarkMapper.toCloud(bookmark(), WatchType.NONE, DEVICE_ID))
    }

    @Test
    fun `a bookmark with no TvType cannot form a key and is skipped`() {
        assertNull(BookmarkMapper.toCloud(bookmark(type = null), WatchType.WATCHING, DEVICE_ID))
    }

    @Test
    fun `snapshot skips ids that have no metadata and orders by latestUpdatedTime`() {
        val local = FakeLocalStore()
        local.put(1, bookmark(id = 1, name = "Older", latestUpdatedTime = 100L), WatchType.WATCHING)
        local.put(2, bookmark(id = 2, name = "Newer", latestUpdatedTime = 900L), WatchType.COMPLETED)
        // A watch state with no metadata: the library skips these too.
        local.watchStates[3] = WatchType.ONHOLD

        val records = snapshot(adapter(local, FakeSyncManager()))

        assertEquals(listOf("Newer", "Older"), records.map { it.name })
    }

    // ------------------------------------------------- watch type conversion

    @Test
    fun `watch type is stored as a name, never as the raw integer`() {
        assertEquals("WATCHING", BookmarkMapper.nameFromInt(WatchType.WATCHING.internalId))
        assertEquals("COMPLETED", BookmarkMapper.nameFromInt(WatchType.COMPLETED.internalId))
        assertEquals("ONHOLD", BookmarkMapper.nameFromInt(WatchType.ONHOLD.internalId))
        assertEquals("DROPPED", BookmarkMapper.nameFromInt(WatchType.DROPPED.internalId))
        assertEquals("PLANTOWATCH", BookmarkMapper.nameFromInt(WatchType.PLANTOWATCH.internalId))
    }

    @Test
    fun `an unknown watch type integer degrades to NONE`() {
        assertEquals(WatchType.NONE, BookmarkMapper.watchTypeFromName(BookmarkMapper.nameFromInt(99)))
    }

    @Test
    fun `watch type names round trip back to the local integer`() {
        for (type in WatchType.entries) {
            assertEquals(type, BookmarkMapper.watchTypeFromName(type.name))
            assertEquals(type.internalId, BookmarkMapper.intFromName(type.name))
        }
    }

    @Test
    fun `unknown or blank watch type names do not resolve`() {
        assertNull(BookmarkMapper.watchTypeFromName("REWATCHING"))
        assertNull(BookmarkMapper.watchTypeFromName(""))
        assertNull(BookmarkMapper.watchTypeFromName(null))
        assertNull(BookmarkMapper.intFromName("nonsense"))
    }

    // ------------------------------------------------- cloud -> local mapping

    @Test
    fun `a cloud record reconstructs the local bookmark and watch state`() {
        val mapped = BookmarkMapper.toLocal(cloudRecord())!!

        assertEquals(WatchType.WATCHING, mapped.watchType)
        assertEquals(4242, mapped.localId)
        assertEquals(WatchType.WATCHING.internalId, mapped.watchType.internalId)

        val data = mapped.bookmarked
        assertEquals("anime:21", data.url)
        assertEquals("Anime", data.apiName)
        assertEquals(TvType.TvSeries, data.type)
        assertEquals(2023, data.year)
        assertEquals(1_000L, data.bookmarkedTime)
        assertEquals(2_000L, data.latestUpdatedTime)
        assertEquals("https://example.com/poster.jpg", data.posterUrl)
    }

    @Test
    fun `a cloud record with watchType NONE is rejected rather than applied`() {
        assertNull(BookmarkMapper.toLocal(cloudRecord(watchType = "NONE")))
    }

    @Test
    fun `a cloud record with an unknown watch type is rejected`() {
        assertNull(BookmarkMapper.toLocal(cloudRecord(watchType = "REWATCHING")))
    }

    @Test
    fun `a cloud record with an unknown TvType is rejected`() {
        assertNull(BookmarkMapper.toLocal(cloudRecord(type = "Hologram")))
    }

    @Test
    fun `putBookmarkLocally writes both keys and does not invent a state`() {
        val local = FakeLocalStore()
        val adapter = adapter(local, FakeSyncManager())

        val result = runBlocking { adapter.putBookmarkLocally(cloudRecord(watchType = "COMPLETED")) }

        assertEquals(4242, requireNotNull((result as CloudDreamSyncResult.Success).value))
        assertEquals(WatchType.COMPLETED, local.getWatchState(4242))
        assertEquals("anime:21", local.getBookmarkData(4242)!!.url)
    }

    @Test
    fun `putBookmarkLocally leaves storage untouched for an invalid record`() {
        val local = FakeLocalStore()
        val adapter = adapter(local, FakeSyncManager())

        val result = runBlocking { adapter.putBookmarkLocally(cloudRecord(watchType = "NONE")) }

        assertTrue(result is CloudDreamSyncResult.Failure)
        assertTrue(local.data.isEmpty())
        assertTrue(local.watchStates.isEmpty())
    }

    @Test
    fun `putBookmarkLocally skips a record that carries no localId`() {
        val local = FakeLocalStore()
        val adapter = adapter(local, FakeSyncManager())

        val result = runBlocking { adapter.putBookmarkLocally(cloudRecord(localId = null)) }

        assertTrue(result is CloudDreamSyncResult.Failure)
        assertTrue(local.data.isEmpty())
    }

    // ------------------------------------------------------- one-shot sync

    @Test
    fun `sync is a no-op when signed out`() {
        val local = FakeLocalStore()
        local.put(1, bookmark(id = 1), WatchType.WATCHING)
        val manager = FakeSyncManager().apply {
            listResult = CloudDreamSyncResult.Skipped(CloudDreamSkipReason.SIGNED_OUT)
        }

        val result = runBlocking { adapter(local, manager).syncBookmarksOnce() }

        assertEquals(CloudDreamSyncResult.Skipped(CloudDreamSkipReason.SIGNED_OUT), result)
        assertTrue("nothing may be uploaded while signed out", manager.uploaded.isEmpty())
    }

    @Test
    fun `sync is a no-op when Firestore is unconfigured`() {
        val local = FakeLocalStore()
        val manager = FakeSyncManager().apply {
            listResult = CloudDreamSyncResult.Skipped(CloudDreamSkipReason.NOT_CONFIGURED)
        }

        val result = runBlocking { adapter(local, manager).syncBookmarksOnce() }

        assertEquals(CloudDreamSyncResult.Skipped(CloudDreamSkipReason.NOT_CONFIGURED), result)
        assertTrue(manager.uploaded.isEmpty())
    }

    @Test
    fun `sync surfaces a listing failure instead of writing anything`() {
        val local = FakeLocalStore()
        local.put(1, bookmark(id = 1), WatchType.WATCHING)
        val manager = FakeSyncManager().apply {
            listResult = CloudDreamSyncResult.Failure(CloudDreamSyncError.FIRESTORE_FAILURE)
        }

        val result = runBlocking { adapter(local, manager).syncBookmarksOnce() }

        assertTrue(result is CloudDreamSyncResult.Failure)
        assertTrue(manager.uploaded.isEmpty())
    }

    @Test
    fun `a newer local record is uploaded and a newer cloud record is applied`() {
        val local = FakeLocalStore()
        local.put(1, bookmark(id = 1, url = "anime:1", name = "Local wins", latestUpdatedTime = 5_000L), WatchType.WATCHING)
        local.put(2, bookmark(id = 2, url = "anime:2", name = "Cloud wins", latestUpdatedTime = 1_000L), WatchType.WATCHING)

        val manager = FakeSyncManager().apply {
            listResult = CloudDreamSyncResult.Success(
                listOf(
                    cloudRecord(url = "anime:1", name = "Stale cloud", localId = 1, updatedAt = 1_000L),
                    cloudRecord(url = "anime:2", name = "Fresh cloud", localId = 2, updatedAt = 9_000L),
                )
            )
        }

        val result = runBlocking { adapter(local, manager).syncBookmarksOnce() }

        assertTrue(result is CloudDreamSyncResult.Success)
        assertEquals(listOf("Local wins"), manager.uploaded.map { it.name })
        assertEquals(WatchType.WATCHING, local.getWatchState(1))
        assertEquals("Local wins", local.getBookmarkData(1)!!.name)
        assertEquals("Fresh cloud", local.getBookmarkData(2)!!.name)
    }

    @Test
    fun `a local record missing from the cloud is uploaded`() {
        val local = FakeLocalStore()
        local.put(7, bookmark(id = 7, url = "anime:7"), WatchType.COMPLETED)
        val manager = FakeSyncManager()

        runBlocking { adapter(local, manager).syncBookmarksOnce() }

        assertEquals(1, manager.uploaded.size)
        assertEquals("COMPLETED", manager.uploaded.single().watchType)
        assertEquals(manager.uploaded.single().key.documentId, manager.bookmarks.keys.single())
    }

    @Test
    fun `equal timestamps are left alone on both sides`() {
        val local = FakeLocalStore()
        local.put(1, bookmark(id = 1, url = "anime:1", name = "Unchanged", latestUpdatedTime = 3_000L), WatchType.WATCHING)
        val manager = FakeSyncManager().apply {
            listResult = CloudDreamSyncResult.Success(
                listOf(cloudRecord(url = "anime:1", name = "Should not be applied", localId = 1, updatedAt = 3_000L))
            )
        }

        runBlocking { adapter(local, manager).syncBookmarksOnce() }

        assertTrue("a tie must not upload", manager.uploaded.isEmpty())
        assertEquals("Unchanged", local.getBookmarkData(1)!!.name)
    }

    // ------------------------------------------------------------- deletion

    @Test
    fun `deleteBookmarkLocally captures the key, deletes the cloud record, then the local one`() {
        val local = FakeLocalStore()
        local.put(4242, bookmark(id = 4242), WatchType.WATCHING)
        val manager = FakeSyncManager()
        val key = BookmarkMapper.keyFor(local.getBookmarkData(4242))!!
        manager.bookmarks[key.documentId] = cloudRecord()

        val result = runBlocking { adapter(local, manager).deleteBookmarkLocally(4242) }

        assertTrue(result is CloudDreamSyncResult.Success)
        assertEquals(listOf(key.documentId), manager.deletedKeys)
        assertTrue("both local keys must be gone", local.data.isEmpty() && local.watchStates.isEmpty())
    }

    @Test
    fun `deleting an unknown local id is a no-op`() {
        val local = FakeLocalStore()
        val manager = FakeSyncManager()

        val result = runBlocking { adapter(local, manager).deleteBookmarkLocally(999) }

        assertTrue(result is CloudDreamSyncResult.Success)
        assertTrue(manager.deletedKeys.isEmpty())
    }

    @Test
    fun `a null local id is a no-op`() {
        val manager = FakeSyncManager()
        val result = runBlocking { adapter(FakeLocalStore(), manager).deleteBookmarkLocally(null) }

        assertTrue(result is CloudDreamSyncResult.Success)
        assertTrue(manager.deletedKeys.isEmpty())
    }

    @Test
    fun `a bookmark with no TvType still clears locally and reports success`() {
        val local = FakeLocalStore()
        local.put(1, bookmark(id = 1, type = null), WatchType.WATCHING)
        val manager = FakeSyncManager()

        val result = runBlocking { adapter(local, manager).deleteBookmarkLocally(1) }

        // No TvType means the row was never uploaded, so there is nothing on the cloud.
        assertTrue(result is CloudDreamSyncResult.Success)
        assertTrue(manager.deletedKeys.isEmpty())
        assertTrue(local.data.isEmpty() && local.watchStates.isEmpty())
    }

    @Test
    fun `the key derived before deletion is stable`() {
        val data = bookmark()
        assertEquals(BookmarkMapper.keyFor(data), BookmarkMapper.keyFor(data))
        assertNull(BookmarkMapper.keyFor(null))
    }

    // ------------------------------------------- malformed local data (issue C)

    @Test
    fun `a blank apiName cannot form a key and is skipped rather than thrown`() {
        // CloudDreamMediaKey.require() would throw on this; the mapper must not reach it.
        assertNull(BookmarkMapper.keyFor(bookmark(apiName = "")))
        assertNull(BookmarkMapper.keyFor(bookmark(apiName = "   ")))
        assertNull(BookmarkMapper.toCloud(bookmark(apiName = ""), WatchType.WATCHING, DEVICE_ID))
    }

    @Test
    fun `a malformed bookmark is skipped without failing the whole snapshot`() {
        val local = FakeLocalStore()
        local.put(1, bookmark(id = 1, url = "anime:1", name = "Good", latestUpdatedTime = 900L), WatchType.WATCHING)
        // apiName blank: the exact input that used to throw out of CloudDreamMediaKey.
        local.put(2, bookmark(id = 2, url = "anime:2", name = "Corrupt", apiName = "", latestUpdatedTime = 800L), WatchType.WATCHING)
        local.put(3, bookmark(id = 3, url = "anime:3", name = "Also good", latestUpdatedTime = 700L), WatchType.COMPLETED)

        val records = snapshot(adapter(local, FakeSyncManager()))

        // The good rows still sync; the corrupt one is dropped, not thrown over.
        assertEquals(listOf("Good", "Also good"), records.map { it.name })
    }

    @Test
    fun `a snapshot of only malformed bookmarks yields an empty result, not an exception`() {
        val local = FakeLocalStore()
        local.put(1, bookmark(id = 1, apiName = ""), WatchType.WATCHING)

        val records = snapshot(adapter(local, FakeSyncManager()))

        assertTrue(records.isEmpty())
    }

    // ---------------------------------------- localId collision safety (issue B)

    @Test
    fun `a cloud record is refused when its localId already holds different media`() {
        val local = FakeLocalStore()
        // A real, unrelated bookmark the user has.
        local.put(4242, bookmark(id = 4242, url = "anime:99", name = "Something else"), WatchType.WATCHING)
        val adapter = adapter(local, FakeSyncManager())

        val result = runBlocking {
            adapter.putBookmarkLocally(cloudRecord(url = "anime:21", name = "Intruder", localId = 4242))
        }

        assertTrue("a colliding write must be refused", result is CloudDreamSyncResult.Failure)
        assertEquals("Something else", local.getBookmarkData(4242)!!.name)
        assertEquals("anime:99", local.getBookmarkData(4242)!!.url)
    }

    @Test
    fun `a cloud record is allowed when the localId holds the same media`() {
        val local = FakeLocalStore()
        local.put(4242, bookmark(id = 4242, url = "anime:21", name = "Stale name"), WatchType.WATCHING)
        val adapter = adapter(local, FakeSyncManager())

        val result = runBlocking {
            adapter.putBookmarkLocally(cloudRecord(url = "anime:21", name = "Fresh name", localId = 4242))
        }

        assertTrue(result is CloudDreamSyncResult.Success)
        assertEquals("Fresh name", local.getBookmarkData(4242)!!.name)
    }

    @Test
    fun `a cloud record with no local row at its localId is written`() {
        val local = FakeLocalStore()
        val adapter = adapter(local, FakeSyncManager())

        val result = runBlocking { adapter.putBookmarkLocally(cloudRecord(localId = 777)) }

        assertTrue(result is CloudDreamSyncResult.Success)
        assertEquals("anime:21", local.getBookmarkData(777)!!.url)
    }

    @Test
    fun `a sync does not clobber an unrelated bookmark when a cloud id collides`() {
        val local = FakeLocalStore()
        local.put(4242, bookmark(id = 4242, url = "anime:99", name = "Mine", latestUpdatedTime = 1_000L), WatchType.WATCHING)
        val manager = FakeSyncManager().apply {
            listResult = CloudDreamSyncResult.Success(
                listOf(cloudRecord(url = "anime:21", name = "Theirs", localId = 4242, updatedAt = 9_000L))
            )
        }

        runBlocking { adapter(local, manager).syncBookmarksOnce() }

        assertEquals("Mine", local.getBookmarkData(4242)!!.name)
        assertEquals("anime:99", local.getBookmarkData(4242)!!.url)
    }

    // ------------------------------------ deletion retry safety (issue A)

    @Test
    fun `a failed cloud delete keeps the local bookmark so it can be retried`() {
        val local = FakeLocalStore()
        local.put(4242, bookmark(id = 4242), WatchType.WATCHING)
        val manager = FakeSyncManager().apply {
            deleteResult = CloudDreamSyncResult.Failure(CloudDreamSyncError.FIRESTORE_FAILURE)
        }

        val result = runBlocking { adapter(local, manager).deleteBookmarkLocally(4242) }

        assertTrue(result is CloudDreamSyncResult.Failure)
        assertEquals(
            "local data must survive a failed cloud delete",
            "Frieren: Beyond Journey's End",
            local.getBookmarkData(4242)!!.name,
        )
        assertEquals(WatchType.WATCHING, local.getWatchState(4242))
    }

    @Test
    fun `a skipped cloud delete keeps the local bookmark`() {
        val local = FakeLocalStore()
        local.put(4242, bookmark(id = 4242), WatchType.WATCHING)
        val manager = FakeSyncManager().apply {
            deleteResult = CloudDreamSyncResult.Skipped(CloudDreamSkipReason.SIGNED_OUT)
        }

        val result = runBlocking { adapter(local, manager).deleteBookmarkLocally(4242) }

        assertEquals(CloudDreamSyncResult.Skipped(CloudDreamSkipReason.SIGNED_OUT), result)
        assertTrue(local.data.containsKey(4242))
        assertTrue(local.watchStates.containsKey(4242))
    }

    @Test
    fun `a retried delete after a failure completes and removes both sides`() {
        val local = FakeLocalStore()
        local.put(4242, bookmark(id = 4242), WatchType.WATCHING)
        val manager = FakeSyncManager()
        val key = BookmarkMapper.keyFor(local.getBookmarkData(4242))!!
        manager.bookmarks[key.documentId] = cloudRecord()
        manager.deleteResult = CloudDreamSyncResult.Failure(CloudDreamSyncError.FIRESTORE_FAILURE)

        // First attempt fails and keeps everything.
        runBlocking { adapter(local, manager).deleteBookmarkLocally(4242) }
        assertTrue(local.data.containsKey(4242))

        // Second attempt, now reachable, completes.
        manager.deleteResult = CloudDreamSyncResult.Success(Unit)
        val second = runBlocking { adapter(local, manager).deleteBookmarkLocally(4242) }

        assertTrue(second is CloudDreamSyncResult.Success)
        assertTrue(local.data.isEmpty() && local.watchStates.isEmpty())
        assertEquals(2, manager.calls.count { it.startsWith("cloud.delete") })
    }

    @Test
    fun `the cloud document is deleted before the local row`() {
        // One shared log, so the interleaving of the two fakes is actually observable.
        val log = mutableListOf<String>()
        val local = FakeLocalStore(log)
        local.put(4242, bookmark(id = 4242), WatchType.WATCHING)
        val manager = FakeSyncManager(log)

        runBlocking { adapter(local, manager).deleteBookmarkLocally(4242) }

        val cloudAt = log.indexOfFirst { it.startsWith("cloud.delete") }
        val localAt = log.indexOfFirst { it.startsWith("local.delete") }
        assertTrue("cloud delete must happen first, log=$log", cloudAt >= 0 && cloudAt < localAt)
    }
}
