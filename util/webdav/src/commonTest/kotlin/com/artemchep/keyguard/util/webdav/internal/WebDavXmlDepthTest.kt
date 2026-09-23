package com.artemchep.keyguard.util.webdav.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WebDavXmlDepthTest {
    @Test
    fun `parses nesting within the depth limit`() {
        val entries = WebDavXml.parseMultiStatus(multistatus(depth = 200))

        val property = entries
            .single()
            .propStats
            .single()
            .properties
            .getValue(XmlName("urn:x", "deep"))
        assertEquals("leaf", property.textContent)
    }

    @Test
    fun `rejects too deeply nested elements as malformed xml`() {
        assertFailsWith<IllegalArgumentException> {
            WebDavXml.parseMultiStatus(multistatus(depth = 20_000))
        }
    }

    private fun multistatus(
        depth: Int,
    ): String {
        val nested = "<x:deep>".repeat(depth) + "leaf" + "</x:deep>".repeat(depth)
        return """
            <D:multistatus xmlns:D="DAV:" xmlns:x="urn:x">
              <D:response>
                <D:href>/dav/root/object.kdbx</D:href>
                <D:propstat>
                  <D:prop>$nested</D:prop>
                  <D:status>HTTP/1.1 200 OK</D:status>
                </D:propstat>
              </D:response>
            </D:multistatus>
        """.trimIndent()
    }
}
