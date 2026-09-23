package com.artemchep.keyguard.util.webdav.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WebDavUnicodeDecodingTest {
    @Test
    fun `numeric entities decode supplementary code points as surrogate pairs`() {
        assertEquals(
            listOf(GRINNING_FACE, GRINNING_FACE, G_CLEF, G_CLEF),
            parseHrefs("&#x1F600;", "&#128512;", "&#x1D11E;", "&#119070;"),
        )
    }

    @Test
    fun `numeric entities decode basic plane code points`() {
        assertEquals(
            listOf("é", "é", "�"),
            parseHrefs("&#xE9;", "&#233;", "&#xFFFD;"),
        )
    }

    @Test
    fun `invalid numeric entities are kept as text`() {
        val entities = listOf("&#xD800;", "&#xDFFF;", "&#x110000;", "&#0;", "&#-1;", "&#xZZ;")
        assertEquals(entities, parseHrefs(*entities.toTypedArray()))
    }

    @Test
    fun `href path keeps a literal surrogate pair together`() {
        val baseUrl = normalizeBaseCollectionUrl(BASE_URL)
        val expected = "folder/$GRINNING_FACE.kdbx"

        assertEquals(expected, hrefToWebDavPath(baseUrl, "/dav/root/folder/$GRINNING_FACE.kdbx"))
        assertEquals(expected, hrefToWebDavPath(baseUrl, "/dav/root/folder/%F0%9F%98%80.kdbx"))
        assertEquals(expected, hrefToWebDavPath(baseUrl, "$BASE_URL/folder/$GRINNING_FACE.kdbx"))
    }

    @Test
    fun `href path tolerates a lone surrogate`() {
        val baseUrl = normalizeBaseCollectionUrl(BASE_URL)

        val path = assertNotNull(hrefToWebDavPath(baseUrl, "/dav/root/\uD83D.kdbx"))

        assertTrue(path.endsWith(".kdbx"), path)
    }

    private fun parseHrefs(
        vararg hrefs: String,
    ): List<String> = WebDavXml
        .parseMultiStatus(
            """
                <D:multistatus xmlns:D="DAV:">
                  ${hrefs.joinToString("\n") { href -> "<D:response><D:href>$href</D:href></D:response>" }}
                </D:multistatus>
            """.trimIndent(),
        )
        .map { entry -> entry.href }

    private companion object {
        private const val BASE_URL = "https://example.com/dav/root"
        private const val GRINNING_FACE = "😀"
        private const val G_CLEF = "𝄞"
    }
}
