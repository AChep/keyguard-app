package com.artemchep.keyguard.util.webdav.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WebDavXmlValidationTest {
    @Test
    fun `multistatus requires a complete well formed document`() {
        for (xml in listOf(
            "<multistatus xmlns='DAV:'><response><href>/file</href>",
            "<multistatus xmlns='DAV:'><response></multistatus>",
            "<multistatus xmlns='DAV:'></wrong>",
            "<multistatus xmlns='DAV:'/><multistatus xmlns='DAV:'/>",
            "text<multistatus xmlns='DAV:'/>",
            "<multistatus xmlns='DAV:'><response><href>/a&b</href></response></multistatus>",
        )) {
            assertFailsWith<IllegalArgumentException>(xml) { WebDavXml.parseMultiStatus(xml) }
        }
    }

    @Test
    fun `DAV matching retains namespace identity across prefix changes`() {
        val entries = WebDavXml.parseMultiStatus(
            """
            <multistatus xmlns="DAV:" xmlns:x="urn:foreign">
              <x:response><href>/ignored</href></x:response>
              <response><x:href>/ignored</x:href><href>/first</href></response>
              <d:response xmlns:d="DAV:"><d:href>/second</d:href></d:response>
            </multistatus>
            """.trimIndent(),
        )

        assertEquals(listOf("/first", "/second"), entries.map { it.href })
    }

    @Test
    fun `href line endings normalize while carriage return references are preserved`() {
        val entries = WebDavXml.parseMultiStatus(
            "<multistatus xmlns='DAV:'><response><href>/a\r\nb\rc&#13;d</href></response></multistatus>",
        )

        assertEquals("/a\nb\nc\rd", entries.single().href)
    }
}
