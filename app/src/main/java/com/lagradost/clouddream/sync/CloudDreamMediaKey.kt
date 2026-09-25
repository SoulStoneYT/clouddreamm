package com.lagradost.clouddream.sync

import java.security.MessageDigest

/**
 * CloudDream's stable cross-device identity for a piece of media.
 *
 * ## Why not the local integer id
 *
 * CloudStream persists everything against `SearchResponse.id: Int?`, but that value is
 * **not** a portable identity:
 *
 * - It is produced by `ResultViewModel2.getLoadResponseIdFromUrl`, which is
 *   `uniqueUrl.replace(providerMainUrl, "").replace("/", "").hashCode()`. It is therefore
 *   scoped to one provider, is a 32-bit signed `Int` (so it can be negative and can
 *   collide), and changes if the provider's `mainUrl` or URL format changes. The
 *   `result_resume_watching` -> `result_resume_watching_2` migration in CloudStream exists
 *   precisely because those ids moved once already.
 * - It is frequently `null` on search results, because the library never computes it; only
 *   `LoadResponse.getId()` does, once a title has been opened.
 *
 * So it is unusable as a cloud key. This class is built from the underlying fields
 * instead.
 *
 * ## What makes up the key
 *
 * | Field | Why it is part of the identity |
 * |---|---|
 * | [apiName] | The provider/extension name. CloudStream's own id hash mixes the provider's `mainUrl`, so the same title on two providers is genuinely two different items. Without this, ids collide across providers. |
 * | [type] | `TvType`. A `TvSeries` and a `Movie` can legitimately share a provider URL space, and different types have different follow-on semantics (episode-based vs. not). Stored by enum **name**, never ordinal, matching CloudStream's own `DataStoreHelper.serializeTv` convention. |
 * | [uniqueUrl] | `LoadResponse.uniqueUrl`, documented in the library as "the key used for storing the persistent data about an entry", introduced specifically to survive URL format changes. This is the strongest stable signal available. |
 * | [year] | CloudStream's own duplicate detection (`checkAndWarnDuplicates`) treats a matching normalized name plus year as a duplicate, so year is a real disambiguator for remakes. Left nullable because not every provider returns it. |
 * | [season] / [episode] | Present only for episode-scoped documents (progress). CloudStream stores progress against an episode, while bookmarks and history are stored against the parent title, so the collections are keyed differently on purpose. |
 *
 * [name] is deliberately **excluded**: it is display data, is not unique (which is why
 * CloudStream has to scan every bookmark to find duplicates), and changes with
 * localisation and provider metadata updates.
 *
 * The local integer `id` is carried in the document as [localId] purely as a debugging
 * aid; it is never part of the identity.
 *
 * ## Canonical form and document id
 *
 * [canonical] is a length-prefixed, order-fixed string that is unambiguous even when a
 * field contains the separator. [documentId] is the first 128 bits of its SHA-256 digest
 * in lowercase hex: fixed length, free of `/` `.` and other characters Firestore rejects
 * in a document id, and collision-free in practice.
 */
data class CloudDreamMediaKey(
    val apiName: String,
    val type: String,
    val uniqueUrl: String,
    val year: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
) {
    init {
        require(apiName.isNotBlank()) { "apiName must not be blank" }
        require(type.isNotBlank()) { "type must not be blank" }
    }

    /**
     * Fixed-order, length-prefixed encoding of every identity field.
     *
     * Prefixing each value with its length means no combination of characters inside a
     * value can be mistaken for a field boundary, so two different keys can never
     * produce the same canonical string.
     */
    val canonical: String = buildString {
        append(SCHEMA_PREFIX)
        appendField(apiName)
        appendField(type)
        appendField(uniqueUrl)
        appendField(year?.toString() ?: "")
        appendField(season?.toString() ?: "")
        appendField(episode?.toString() ?: "")
    }

    /** Firestore document id: the first 128 bits of the canonical form's SHA-256. */
    val documentId: String = sha256Hex(canonical).substring(0, DOCUMENT_ID_HEX_LENGTH)

    private fun StringBuilder.appendField(value: String) {
        append(SEPARATOR).append(value.length).append(SEPARATOR).append(value)
    }

    companion object {
        /** Bumped only if the canonical encoding itself changes, never for data changes. */
        const val SCHEMA_PREFIX = "cdm1"

        private const val SEPARATOR = '|'
        private const val DOCUMENT_ID_HEX_LENGTH = 32

        /**
         * Builds a parent-title key (no season/episode) for bookmarks and history.
         */
        fun forTitle(
            apiName: String,
            type: String,
            uniqueUrl: String,
            year: Int? = null,
        ): CloudDreamMediaKey = CloudDreamMediaKey(apiName, type, uniqueUrl, year)

        /**
         * Builds an episode-scoped key for progress, which CloudStream stores per
         * playable item rather than per title.
         */
        fun forEpisode(
            apiName: String,
            type: String,
            uniqueUrl: String,
            year: Int? = null,
            season: Int? = null,
            episode: Int? = null,
        ): CloudDreamMediaKey = CloudDreamMediaKey(apiName, type, uniqueUrl, year, season, episode)

        private fun sha256Hex(value: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            val out = StringBuilder(digest.size * 2)
            for (byte in digest) {
                val v = byte.toInt() and 0xFF
                out.append(HEX[v ushr 4])
                out.append(HEX[v and 0x0F])
            }
            return out.toString()
        }

        private val HEX = "0123456789abcdef".toCharArray()
    }
}
