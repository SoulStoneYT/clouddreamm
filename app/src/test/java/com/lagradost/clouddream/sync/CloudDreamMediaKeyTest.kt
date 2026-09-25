package com.lagradost.clouddream.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks down the contract of [CloudDreamMediaKey].
 *
 * The cloud document id is derived from this key, so if it is not deterministic, or two
 * genuinely different media can produce the same id, records would silently overwrite each
 * other across devices. These tests are the guard against that.
 */
class CloudDreamMediaKeyTest {

    private fun title(
        apiName: String = "Anime",
        type: String = "TvSeries",
        uniqueUrl: String = "anime:12345",
        year: Int? = 2019,
    ) = CloudDreamMediaKey.forTitle(apiName, type, uniqueUrl, year)

    @Test
    fun `document id is deterministic for the same media`() {
        assertEquals(title().documentId, title().documentId)
    }

    @Test
    fun `document id is a 32 character lowercase hex string`() {
        val id = title().documentId
        assertEquals(32, id.length)
        assertTrue("unexpected document id: $id", id.all { it in "0123456789abcdef" })
    }

    @Test
    fun `document id is safe to use as a Firestore document id`() {
        // uniqueUrl realistically contains slashes, colons and spaces.
        val id = title(uniqueUrl = "https://example.com/season 1/ep 2?a=b").documentId
        assertTrue(id.none { it == '/' || it == '.' || it == ' ' })
    }

    @Test
    fun `a different provider yields a different document id`() {
        assertNotEquals(title(apiName = "Anime").documentId, title(apiName = "Torrentio").documentId)
    }

    @Test
    fun `a different type yields a different document id`() {
        assertNotEquals(title(type = "TvSeries").documentId, title(type = "Movie").documentId)
    }

    @Test
    fun `a different uniqueUrl yields a different document id`() {
        assertNotEquals(title(uniqueUrl = "anime:1").documentId, title(uniqueUrl = "anime:2").documentId)
    }

    @Test
    fun `a different year yields a different document id`() {
        assertNotEquals(title(year = 2019).documentId, title(year = 2021).documentId)
    }

    @Test
    fun `a null year is not the same as a zero year`() {
        assertNotEquals(title(year = null).documentId, title(year = 0).documentId)
    }

    @Test
    fun `season and episode scope progress separately from the parent title`() {
        val parent = title()
        val seasonOne = CloudDreamMediaKey.forEpisode("Anime", "TvSeries", "anime:12345", 2019, 1, 1)
        val seasonOneTwo = CloudDreamMediaKey.forEpisode("Anime", "TvSeries", "anime:12345", 2019, 1, 2)
        val seasonTwoOne = CloudDreamMediaKey.forEpisode("Anime", "TvSeries", "anime:12345", 2019, 2, 1)

        assertNotEquals(parent.documentId, seasonOne.documentId)
        assertNotEquals(seasonOne.documentId, seasonOneTwo.documentId)
        assertNotEquals(seasonOne.documentId, seasonTwoOne.documentId)
    }

    @Test
    fun `season ten does not collide with season one episode ten`() {
        // CloudStream's own arithmetic episode id collapses these two, which is one of the
        // reasons the cloud key is built from separate fields instead.
        val seasonTen = CloudDreamMediaKey.forEpisode("Anime", "TvSeries", "anime:12345", 2019, 10, 1)
        val seasonOne = CloudDreamMediaKey.forEpisode("Anime", "TvSeries", "anime:12345", 2019, 1, 10)
        assertNotEquals(seasonTen.documentId, seasonOne.documentId)
    }

    @Test
    fun `field boundaries cannot be spoofed by a value containing the separator`() {
        // Joined naively with the separator, these two produce the identical string
        // "cdm1|A|B|C|D|||", because a "|" inside a value is then indistinguishable from a
        // field boundary. Length prefixing is what keeps them apart.
        val a = title(apiName = "A|B", type = "C", uniqueUrl = "D")
        val b = title(apiName = "A", type = "B", uniqueUrl = "C|D")

        assertNotEquals(a.canonical, b.canonical)
        assertNotEquals(a.documentId, b.documentId)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a blank apiName is rejected`() {
        CloudDreamMediaKey.forTitle("", "TvSeries", "anime:1", null)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a blank type is rejected`() {
        CloudDreamMediaKey.forTitle("Anime", "", "anime:1", null)
    }
}
