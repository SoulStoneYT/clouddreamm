package com.lagradost.clouddream.sync.local

import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.ui.WatchType
import com.lagradost.cloudstream3.utils.DataStoreHelper.BookmarkedData
import com.lagradost.clouddream.sync.CloudDreamBookmarkRecord
import com.lagradost.clouddream.sync.CloudDreamMediaKey
import com.lagradost.clouddream.sync.CloudDreamRecordMeta

/**
 * Pure, Android-free mapping between CloudStream's local bookmark model and
 * CloudDream's cloud records.
 *
 * This object deliberately holds no references to `DataStoreHelper`, the
 * `CloudDreamSyncManager`, Firestore, or any Android API. That is what makes the
 * Phase 2D.1 media-key / field / enum conversions unit-testable on the JVM,
 * independent of a device or a Firebase project.
 *
 * ## Local ↔ cloud shape
 *
 * CloudStream stores a bookmark's *metadata* and its *title watch state* as two
 * keys under the same local integer id (`result_watch_state_data/{id}` =
 * [BookmarkedData], `result_watch_state/{id}` = a [WatchType] ordinal). They are
 * one logical entity — `setResultWatchState(NONE)` deletes the metadata too —
 * which is exactly what CloudDream models as a single
 * [CloudDreamBookmarkRecord] carrying a `watchType`.
 *
 * ## Watch type convention
 *
 * The watch type is stored on the cloud document as the `WatchType` **name**
 * (`COMPLETED`, `WATCHING`, …) and is never the raw integer ordinal. CloudStream's
 * own `DataStoreHelper.serializeTv` already serializes [`TvType`] by name for the
 * same reason its ordinals are not a stable wire format.
 */
internal object BookmarkMapper {

    /**
     * Local → cloud for a single id.
     *
     * Returns `null` (i.e. "do not sync this one") when the local state is not a
     * real bookmark:
     *  - no `BookmarkedData` (a watch-state-only row, which the library itself
     *    skips when rendering), or
     *  - the watch type is [WatchType.NONE] (an unbookmark, which deletes the
     *    metadata rather than recording a "none" status).
     */
    fun toCloud(
        data: BookmarkedData?,
        watchType: WatchType,
        deviceId: String,
    ): CloudDreamBookmarkRecord? {
        if (data == null) return null
        if (watchType == WatchType.NONE) return null
        val type = data.type ?: return null
        return CloudDreamBookmarkRecord(
            key = CloudDreamMediaKey.forTitle(data.apiName, type.name, data.url, data.year),
            name = data.name,
            watchType = watchType.name,
            posterUrl = data.posterUrl,
            plot = data.plot,
            tags = data.tags,
            bookmarkedAt = data.bookmarkedTime,
            localId = data.id,
            meta = CloudDreamRecordMeta(updatedAt = data.latestUpdatedTime, deviceId = deviceId),
        )
    }

    /**
     * Derives the stable cloud key for a local bookmark, or `null` when the
     * record is missing the `TvType` needed to form one (in which case it was
     * never uploaded, so there is nothing on the cloud to delete).
     *
     * Used both for upload and for capturing the key *before* a local deletion is
     * applied.
     */
    fun keyFor(data: BookmarkedData?): CloudDreamMediaKey? {
        val type = data?.type ?: return null
        return CloudDreamMediaKey.forTitle(data.apiName, type.name, data.url, data.year)
    }

    /** `WatchType` name → enum, or `null` for an unknown/blank name. */
    fun watchTypeFromName(name: String?): WatchType? =
        if (name == null) null else WatchType.entries.find { it.name == name }

    /** `WatchType` ordinal → name. Never store the raw integer on the wire. */
    fun nameFromInt(id: Int): String = WatchType.fromInternalId(id).name

    /** `WatchType` name → ordinal, or `null` for an unknown/blank name. */
    fun intFromName(name: String?): Int? = watchTypeFromName(name)?.internalId

    /**
     * Cloud → local reconstruction.
     *
     * Returns `null` (i.e. "do not materialise this one") when the cloud record
     * is not a real bookmark:
     *  - `watchType` is missing, `NONE`, or not a valid [WatchType] name, or
     *  - `type` is not a valid [TvType] name.
     *
     * The caller is expected to skip a `null` result rather than invent a state.
     */
    fun toLocal(record: CloudDreamBookmarkRecord): BookmarkLocal? {
        val watchType = watchTypeFromName(record.watchType) ?: return null
        if (watchType == WatchType.NONE) return null
        val tvType = TvType.entries.find { it.name == record.key.type } ?: return null
        val bookmarked = BookmarkedData(
            bookmarkedTime = record.bookmarkedAt,
            id = record.localId,
            latestUpdatedTime = record.meta.updatedAt,
            name = record.name,
            url = record.key.uniqueUrl,
            apiName = record.key.apiName,
            type = tvType,
            posterUrl = record.posterUrl,
            year = record.key.year,
            plot = record.plot,
            tags = record.tags,
        )
        return BookmarkLocal(localId = record.localId, watchType = watchType, bookmarked = bookmarked)
    }

    /**
     * A cloud bookmark materialised into the two local writes the library needs:
     * the `BookmarkedData` metadata plus the integer watch-state ordinal to store
     * under `result_watch_state/{id}`.
     */
    data class BookmarkLocal(
        val localId: Int?,
        val watchType: WatchType,
        val bookmarked: BookmarkedData,
    )
}
