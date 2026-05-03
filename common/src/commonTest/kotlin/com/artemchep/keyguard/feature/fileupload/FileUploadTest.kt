package com.artemchep.keyguard.feature.fileupload

import com.artemchep.keyguard.feature.filepicker.FilePickerResult
import com.artemchep.keyguard.platform.leParseUri
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileUploadTest {
    @Test
    fun `bitwarden upload file size allows unknown and limit values`() {
        assertTrue(isBitwardenUploadFileSizeAllowed(null))
        assertTrue(isBitwardenUploadFileSizeAllowed(BITWARDEN_FILE_UPLOAD_MAX_BYTES))
    }

    @Test
    fun `bitwarden upload file size rejects values over limit`() {
        assertFalse(isBitwardenUploadFileSizeAllowed(BITWARDEN_FILE_UPLOAD_MAX_BYTES + 1L))
    }

    @Test
    fun `attachment file metadata keeps picker name`() {
        val metadata = FilePickerResult(
            uri = leParseUri("content://attachment/file"),
            name = "invoice.pdf",
            size = 2048L,
        ).toAttachmentFileMetadata(
            fallbackName = "File",
        )

        assertEquals(leParseUri("content://attachment/file"), metadata.uri)
        assertEquals("content://attachment/file", metadata.uriString)
        assertEquals("invoice.pdf", metadata.name)
        assertEquals("invoice.pdf", metadata.rawName)
        assertEquals(2048L, metadata.size)
    }

    @Test
    fun `attachment file metadata uses configurable fallback name`() {
        val metadata = FilePickerResult(
            uri = leParseUri("content://attachment/file"),
            name = null,
            size = null,
        ).toAttachmentFileMetadata(
            fallbackName = "File",
        )

        assertEquals("File", metadata.name)
        assertNull(metadata.rawName)
        assertNull(metadata.size)
    }
}
