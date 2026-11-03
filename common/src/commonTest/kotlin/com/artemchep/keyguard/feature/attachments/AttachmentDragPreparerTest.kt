package com.artemchep.keyguard.feature.attachments

import com.artemchep.keyguard.common.model.DownloadAttachmentRequest
import com.artemchep.keyguard.common.usecase.PrepareAttachmentContent
import com.artemchep.keyguard.crypto.FileEncryptionFormat
import com.artemchep.keyguard.feature.attachments.model.AttachmentDragPreparer
import com.artemchep.keyguard.feature.dragout.compose.DragOutTestContent
import com.artemchep.keyguard.feature.dragout.model.DragOutCapabilities
import com.artemchep.keyguard.feature.dragout.model.DragRepresentation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class AttachmentDragPreparerTest {
    private val request = PrepareAttachmentContent.Request(
        DownloadAttachmentRequest.ByLocalCipherAttachment("cipher", "remote", "attachment"),
        "attachment.txt",
    )
    private val content = DragOutTestContent("attachment.txt").content

    @Test
    fun `an uncapped platform prepares one file item from the unchanged request`() = runTest {
        val prepare = AttachmentDragPreparer(
            prepareAttachmentContent = { actual, _ ->
                assertEquals(request, actual)
                content
            },
            capabilities = DragOutCapabilities(files = true, fileMaximumBytes = null, urls = true),
        )

        assertTrue(prepare.canPrepare(4L * 1024 * 1024 * 1024))
        assertFalse(prepare.canPrepare(-1))
        val file = assertIs<DragRepresentation.File>(prepare(request) {}.items.single().representations.single())
        assertSame(content, file.content)
    }

    @Test
    fun `a capped platform bounds verified bytes and allows encrypted metadata overhead`() = runTest {
        val limit = 1024L
        val prepare = AttachmentDragPreparer(
            prepareAttachmentContent = { actual, _ ->
                assertEquals(limit, actual.maximumBytes)
                content
            },
            capabilities = DragOutCapabilities(files = true, fileMaximumBytes = limit, urls = true),
        )
        val encrypted = limit + FileEncryptionFormat.HEADER_LENGTH + FileEncryptionFormat.IV_LENGTH

        assertTrue(prepare.canPrepare(encrypted))
        assertFalse(prepare.canPrepare(encrypted + 1))
        prepare(request) {}
    }

    @Test
    fun `a platform without file transport never prepares`() = runTest {
        val prepare = AttachmentDragPreparer(
            prepareAttachmentContent = { _, _ -> error("Must not prepare") },
            capabilities = DragOutCapabilities(files = false, fileMaximumBytes = null, urls = true),
        )

        assertFalse(prepare.canPrepare(0))
        assertFailsWith<IllegalStateException> { prepare(request) {} }
    }
}
