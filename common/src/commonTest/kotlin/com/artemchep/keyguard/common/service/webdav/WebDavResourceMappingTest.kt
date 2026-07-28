package com.artemchep.keyguard.common.service.webdav

import com.artemchep.keyguard.util.webdav.WebDavResource
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WebDavResourceMappingTest {
    @Test
    fun `file resource is retained`() {
        val resource = resource(isCollection = false)

        assertTrue(resource.isFileResource)
        assertSame(resource, resource.takeFileResourceOrNull())
    }

    @Test
    fun `collection resource is omitted`() {
        val resource = resource(isCollection = true)

        assertFalse(resource.isFileResource)
        assertNull(resource.takeFileResourceOrNull())
    }

    private fun resource(
        isCollection: Boolean,
    ): WebDavResource = WebDavResource(
        path = if (isCollection) "folder/" else "file.bin",
        isCollection = isCollection,
        size = if (isCollection) null else 42L,
        lastModified = null,
        etag = null,
    )
}
