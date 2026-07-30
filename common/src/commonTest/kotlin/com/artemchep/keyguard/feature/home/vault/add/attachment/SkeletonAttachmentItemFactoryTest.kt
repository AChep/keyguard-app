package com.artemchep.keyguard.feature.home.vault.add.attachment

import com.artemchep.keyguard.common.model.create.CreateRequest
import com.artemchep.keyguard.platform.leParseUri
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

class SkeletonAttachmentItemFactoryTest {
    @Test
    fun `remote skeleton attachment preserves remote identity`() {
        val output = CreateRequest(
            now = TEST_INSTANT,
        ).withSkeletonAttachment(
            identity = SkeletonAttachment.Remote.Identity(
                id = "remote-1",
            ),
            name = "invoice.pdf",
        )

        val attachment = assertIs<CreateRequest.Attachment.Remote>(
            output.attachments.single(),
        )
        assertEquals("remote-1", attachment.id)
        assertEquals("invoice.pdf", attachment.name)
    }

    @Test
    fun `local skeleton attachment preserves local metadata`() {
        val output = CreateRequest(
            now = TEST_INSTANT,
        ).withSkeletonAttachment(
            identity = SkeletonAttachment.Local.Identity(
                id = "local-1",
                uri = leParseUri("content://attachment/file"),
                size = 2048L,
                keyBase64 = "attachment-key",
            ),
            name = "invoice.pdf",
        )

        val attachment = assertIs<CreateRequest.Attachment.Local>(
            output.attachments.single(),
        )
        assertEquals("local-1", attachment.id)
        assertEquals("content://attachment/file", attachment.uri.toString())
        assertEquals(2048L, attachment.size)
        assertEquals("attachment-key", attachment.keyBase64)
        assertEquals("invoice.pdf", attachment.name)
    }
}

private val TEST_INSTANT = Instant.parse("2024-01-01T00:00:00Z")
