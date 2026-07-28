package com.artemchep.keyguard.feature.attachmentpreview

import arrow.core.left
import arrow.core.right
import com.artemchep.keyguard.common.service.download.DownloadInfoEntity
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.AttachmentPreviewLimits
import com.artemchep.keyguard.common.model.AttachmentPreviewPayload
import com.artemchep.keyguard.common.model.AttachmentPreviewPolicy
import com.artemchep.keyguard.common.model.AttachmentPreviewRequest
import com.artemchep.keyguard.common.service.clipboard.ClipboardService
import com.artemchep.keyguard.common.service.download.DownloadManager
import com.artemchep.keyguard.common.service.download.DownloadProgress
import com.artemchep.keyguard.common.service.download.DownloadQueueRequest
import com.artemchep.keyguard.common.usecase.CanPreviewAttachment
import com.artemchep.keyguard.common.usecase.CopyText
import com.artemchep.keyguard.common.usecase.GetAttachmentPreview
import com.artemchep.keyguard.common.usecase.impl.CanPreviewAttachmentImpl
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringResource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AttachmentPreviewStateProducerTest {
    @Test
    fun `cached local url returns completed download uri`() = runTest {
        val localUrl = cachedAttachmentPreviewLocalUrl(
            args = args,
            downloadManager = FakeDownloadManager(
                status = DownloadProgress.Complete("file:///tmp/preview.txt".right()),
            ),
        )

        assertEquals("file:///tmp/preview.txt", localUrl)
    }

    @Test
    fun `cached local url ignores incomplete failed and null uri statuses`() = runTest {
        val statuses = listOf<DownloadProgress>(
            DownloadProgress.None,
            DownloadProgress.Loading(),
            DownloadProgress.Complete(RuntimeException().left()),
            DownloadProgress.Complete(null.right()),
        )

        statuses.forEach { status ->
            val localUrl = cachedAttachmentPreviewLocalUrl(
                args = args,
                downloadManager = FakeDownloadManager(status = status),
            )

            assertEquals(null, localUrl)
        }
    }

    @Test
    fun `plain text files decode as text content`() {
        val content = decodeTextPreview(
            fileName = "notes.txt",
            bytes = "hello".encodeToByteArray(),
            copyText = createCopyText(),
        )

        val text = assertIs<AttachmentPreviewContent.Text>(content)
        assertEquals("hello", text.text)
        assertEquals(1, text.lineIndex.size)
        assertEquals(5, text.lineIndex.maxLineLength)
    }

    @Test
    fun `markdown files decode as markdown content`() {
        val content = decodeTextPreview(
            fileName = "README.md",
            bytes = "# Hello".encodeToByteArray(),
            copyText = createCopyText(),
        )

        val markdown = assertIs<AttachmentPreviewContent.Markdown>(content)
        assertEquals("# Hello", markdown.text)
        assertEquals(1, markdown.lineIndex.size)
        assertEquals(7, markdown.lineIndex.maxLineLength)
    }

    @Test
    fun `text decode rejects nul bytes`() {
        val content = decodeTextPreview(
            fileName = "notes.txt",
            bytes = byteArrayOf('a'.code.toByte(), 0, 'b'.code.toByte()),
            copyText = createCopyText(),
        )

        val error = assertIs<AttachmentPreviewContent.Error>(content)
        assertEquals(AttachmentPreviewError.TextDecode, error.type)
    }

    @Test
    fun `text decode strips utf8 byte order mark`() {
        val content = decodeTextPreview(
            fileName = "notes.txt",
            bytes = byteArrayOf(
                0xEF.toByte(),
                0xBB.toByte(),
                0xBF.toByte(),
            ) + "hello".encodeToByteArray(),
            copyText = createCopyText(),
        )

        val text = assertIs<AttachmentPreviewContent.Text>(content)
        assertEquals("hello", text.text)
        assertEquals(5, text.lineIndex.maxLineLength)
    }

    @Test
    fun `policy errors are returned without local status or preview fetch`() = runTest {
        val cases = listOf(
            Triple(
                args.copy(
                    fileName = "notes.txt",
                    encryptedSize = AttachmentPreviewLimits.MAX_ENCRYPTED_BYTES + 1,
                ),
                CanPreviewAttachmentImpl(),
                AttachmentPreviewError.TooLarge,
            ),
            Triple(
                args.copy(
                    fileName = "archive.zip",
                    encryptedSize = 1L,
                ),
                CanPreviewAttachmentImpl(),
                AttachmentPreviewError.UnsupportedFileType,
            ),
            Triple(
                args.copy(
                    fileName = "notes.txt",
                    encryptedSize = 1L,
                ),
                StaticCanPreviewAttachment(AttachmentPreviewPolicy.UnsupportedPlatform),
                AttachmentPreviewError.UnsupportedPlatform,
            ),
        )

        cases.forEach { (args, canPreviewAttachment, expectedError) ->
            val downloadManager = FakeDownloadManager(status = DownloadProgress.None)
            val getAttachmentPreview = CountingGetAttachmentPreview()

            val state = createAttachmentPreviewState(
                args = args,
                canPreviewAttachment = canPreviewAttachment,
                getAttachmentPreview = getAttachmentPreview,
                downloadManager = downloadManager,
                copyText = createCopyText(),
            )

            val error = assertIs<AttachmentPreviewContent.Error>(state.content)
            assertEquals(expectedError, error.type)
            assertEquals(0, downloadManager.statusByTagCalls)
            assertEquals(0, getAttachmentPreview.calls)
        }
    }

    @Test
    fun `previewable route passes encrypted size to preview request`() = runTest {
        val downloadManager = FakeDownloadManager(status = DownloadProgress.None)
        val getAttachmentPreview = CountingGetAttachmentPreview()

        val state = createAttachmentPreviewState(
            args = args.copy(encryptedSize = 123L),
            canPreviewAttachment = CanPreviewAttachmentImpl(),
            getAttachmentPreview = getAttachmentPreview,
            downloadManager = downloadManager,
            copyText = createCopyText(),
        )

        val text = assertIs<AttachmentPreviewContent.Text>(state.content)
        assertEquals("ok", text.text)
        assertEquals(1, downloadManager.statusByTagCalls)
        assertEquals(1, getAttachmentPreview.calls)
        assertEquals(123L, getAttachmentPreview.lastRequest?.encryptedSize)
    }

    private fun createCopyText(): CopyText = CopyText(
        clipboardService = object : ClipboardService {
            override fun setPrimaryClip(
                value: String,
                concealed: Boolean,
            ) = Unit

            override fun clearPrimaryClip() = Unit

            override fun hasCopyNotification(): Boolean = true
        },
        translator = object : TranslatorScope {
            override suspend fun translate(res: StringResource): String = res.toString()

            override suspend fun translate(res: StringResource, vararg args: Any): String =
                res.toString()

            override suspend fun translate(
                res: PluralStringResource,
                quantity: Int,
                vararg args: Any,
            ): String = res.toString()
        },
        onMessage = {},
    )
}

private class StaticCanPreviewAttachment(
    private val policy: AttachmentPreviewPolicy,
) : CanPreviewAttachment {
    override fun invoke(
        fileName: String,
        encryptedSize: Long?,
    ): AttachmentPreviewPolicy = policy
}

private class FakeDownloadManager(
    private val status: DownloadProgress,
) : DownloadManager {
    override fun statusByDownloadId2(
        downloadId: String,
    ): Flow<DownloadProgress> = flowOf(status)

    var statusByTagCalls = 0
        private set

    override fun statusByTag(
        tag: DownloadInfoEntity.AttachmentDownloadTag,
    ): Flow<DownloadProgress> {
        statusByTagCalls++
        return flowOf(status)
    }

    override suspend fun queue(
        downloadInfo: DownloadInfoEntity,
    ): DownloadManager.QueueResult = error("Unexpected queue.")

    override suspend fun queue(
        request: DownloadQueueRequest,
    ): DownloadManager.QueueResult = error("Unexpected queue.")

    override suspend fun removeByDownloadId(
        downloadId: String,
    ) = error("Unexpected remove.")

    override suspend fun removeByTag(
        tag: DownloadInfoEntity.AttachmentDownloadTag,
    ) = error("Unexpected remove.")
}

private class CountingGetAttachmentPreview : GetAttachmentPreview {
    var calls = 0
        private set
    var lastRequest: AttachmentPreviewRequest? = null
        private set

    override fun invoke(
        request: AttachmentPreviewRequest,
    ): IO<AttachmentPreviewPayload> = {
        calls++
        lastRequest = request
        AttachmentPreviewPayload(
            fileName = request.fileName,
            encryptedSize = request.encryptedSize ?: 1L,
            bytes = "ok".encodeToByteArray(),
        )
    }
}

private val args = AttachmentPreviewRoute.Args(
    localCipherId = "local-cipher",
    remoteCipherId = "remote-cipher",
    attachmentId = "attachment",
    fileName = "preview.txt",
)
