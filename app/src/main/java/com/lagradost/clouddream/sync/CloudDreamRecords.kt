package com.lagradost.clouddream.sync

/**
 * Metadata every cloud record carries so a future conflict-resolution engine can decide
 * which side wins without a second round trip.
 *
 * No conflict engine is implemented yet; these fields exist so that one can be added
 * later **without** a schema migration or a full re-upload.
 *
 * @param updatedAt epoch milliseconds of the write, from the writing device's clock.
 *   CloudStream has no per-item clock for progress at all (`DataStoreHelper.PosDur` is
 *   just position and duration), so this is currently the only ordering signal.
 * @param deviceId which CloudDream installation wrote this. See [CloudDreamDeviceId].
 */
data class CloudDreamRecordMeta(
    val updatedAt: Long,
    val deviceId: String,
) {
    companion object {
        /**
         * Current record schema. Bump only on an incompatible field change, so a future
         * client can migrate rather than guess.
         */
        const val SCHEMA_VERSION = 1
    }
}

/**
 * Resume position for one playable item (an episode, or the title itself for a movie).
 *
 * Mirrors the local `DataStoreHelper.PosDur` (`video_pos_dur/{episodeId}`) and adds the
 * missing clock. Note the local writer silently drops anything under 30 seconds
 * (`DataStoreHelper.setViewPos`); cloud sync does not re-impose that floor, so a cloud
 * record may legitimately hold a short item that the local store would not.
 *
 * @param positionMs playback position in milliseconds.
 * @param durationMs total duration in milliseconds.
 * @param watched the per-episode watched flag. CloudStream's own "is this watched"
 *   predicate is `videoWatchState == Watched || progress >= 0.90`
 *   (`ResultFragmentTv`), so both halves are recorded and the predicate is left to the
 *   merge logic.
 * @param parentApiName / [parentUniqueUrl] attribute an episode back to its series, since
 *   the progress key is episode-scoped while the history key is title-scoped.
 * @param localId CloudStream's own integer id, stored for debugging only. It is never
 *   part of the identity and must not be used to look a record up.
 */
data class CloudDreamProgressRecord(
    val key: CloudDreamMediaKey,
    val positionMs: Long,
    val durationMs: Long,
    val watched: Boolean,
    val parentApiName: String?,
    val parentUniqueUrl: String?,
    val localId: Int?,
    val meta: CloudDreamRecordMeta,
) {
    /** Progress in the range 0f..1f, or 0f when the duration is unknown. */
    val fraction: Float
        get() = if (durationMs <= 0L) 0f else (positionMs.toFloat() / durationMs.toFloat())
}

/**
 * A bookmarked title, mirroring the local `DataStoreHelper.BookmarkedData`
 * (`result_watch_state_data/{id}`).
 *
 * Only the fields needed to re-render a library row and to merge are carried; the local
 * record's poster headers, quality and rating bookkeeping stay local.
 *
 * @param watchType CloudStream's `WatchType` **name** (`WATCHING`, `COMPLETED`, ...), not
 *   its ordinal. The local enum's ordinals are not a stable wire format.
 * @param bookmarkedAt when the user first bookmarked this title. Local
 *   `bookmarkedTime` is write-once, so it is preserved across re-bookmarks.
 */
data class CloudDreamBookmarkRecord(
    val key: CloudDreamMediaKey,
    val name: String,
    val watchType: String,
    val posterUrl: String?,
    val plot: String?,
    val tags: List<String>?,
    val bookmarkedAt: Long,
    val localId: Int?,
    val meta: CloudDreamRecordMeta,
)

/**
 * A continue-watching entry, mirroring the local
 * `DownloadObjects.ResumeWatching` (`result_resume_watching_2/{parentId}`).
 *
 * Keyed by the parent title, not the episode, exactly like the local store. The position
 * itself is deliberately **not** duplicated here: locally the home screen reads it from
 * `video_pos_dur/{episodeId}` at display time, and storing a second copy would create a
 * second source of truth that can disagree.
 *
 * @param updatedAt local `updateTime`, the ordering key the home screen sorts by.
 */
data class CloudDreamHistoryRecord(
    val key: CloudDreamMediaKey,
    val season: Int?,
    val episode: Int?,
    val updatedAt: Long,
    val isFromDownload: Boolean,
    val localId: Int?,
    val meta: CloudDreamRecordMeta,
)
