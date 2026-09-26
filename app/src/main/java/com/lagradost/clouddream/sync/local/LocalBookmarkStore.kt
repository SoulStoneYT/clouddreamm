package com.lagradost.clouddream.sync.local

import com.lagradost.cloudstream3.ui.WatchType
import com.lagradost.cloudstream3.utils.DataStoreHelper

/**
 * The narrow slice of [DataStoreHelper] that bookmark sync needs.
 *
 * CloudDream talks to CloudStream storage only through this interface (and its
 * [DataStoreBookmarkStore] default, which delegates to `DataStoreHelper`'s public
 * APIs). That keeps the adapter testable on the JVM with an in-memory fake, and
 * keeps CloudDream out of CloudStream's `rebuild_preference` SharedPreferences
 * directly.
 */
interface LocalBookmarkStore {

    /** Local ids that carry a title-level watch state (`result_watch_state/{id}`). */
    fun watchStateIds(): List<Int>

    /** The stored [WatchType] for a local id, or [WatchType.NONE] when unset. */
    fun getWatchState(id: Int): WatchType

    /** The bookmarked-title metadata for a local id, or `null` when absent. */
    fun getBookmarkData(id: Int?): DataStoreHelper.BookmarkedData?

    /** Writes bookmark metadata under `result_watch_state_data/{id}`. */
    fun setBookmarkData(id: Int?, data: DataStoreHelper.BookmarkedData)

    /** Writes the title watch-state ordinal under `result_watch_state/{id}` (NONE deletes both). */
    fun setWatchState(id: Int?, watchTypeId: Int)

    /** Deletes both `result_watch_state/{id}` and `result_watch_state_data/{id}`. */
    fun deleteBookmarkData(id: Int?)
}

/**
 * Production [LocalBookmarkStore], backed entirely by [DataStoreHelper]'s public
 * APIs. It does not touch `rebuild_preference` or any SharedPreferences directly.
 */
object DataStoreBookmarkStore : LocalBookmarkStore {

    override fun watchStateIds(): List<Int> =
        DataStoreHelper.getAllWatchStateIds() ?: emptyList()

    override fun getWatchState(id: Int): WatchType =
        DataStoreHelper.getResultWatchState(id)

    override fun getBookmarkData(id: Int?): DataStoreHelper.BookmarkedData? =
        DataStoreHelper.getBookmarkedData(id)

    override fun setBookmarkData(id: Int?, data: DataStoreHelper.BookmarkedData) {
        DataStoreHelper.setBookmarkedData(id, data)
    }

    override fun setWatchState(id: Int?, watchTypeId: Int) {
        DataStoreHelper.setResultWatchState(id, watchTypeId)
    }

    override fun deleteBookmarkData(id: Int?) {
        DataStoreHelper.deleteBookmarkedData(id)
    }
}
