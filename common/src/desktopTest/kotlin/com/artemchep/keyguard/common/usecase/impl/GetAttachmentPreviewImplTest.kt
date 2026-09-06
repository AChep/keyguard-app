package com.artemchep.keyguard.common.usecase.impl

import arrow.core.right
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.AttachmentPreviewException
import com.artemchep.keyguard.common.model.AttachmentPreviewLimits
import com.artemchep.keyguard.common.model.AttachmentPreviewRequest
import com.artemchep.keyguard.common.model.DownloadAttachmentRequest
import com.artemchep.keyguard.common.model.DownloadAttachmentRequestData
import com.artemchep.keyguard.common.service.download.DownloadAttachmentSourceLoader
import com.artemchep.keyguard.common.service.download.DownloadProgress
import com.artemchep.keyguard.common.service.download.DownloadWriter
import com.artemchep.keyguard.common.service.download.locationUri
import com.artemchep.keyguard.common.service.download.writeBytes
import com.artemchep.keyguard.common.service.file.FileService
import com.artemchep.keyguard.common.service.file.FileServiceImpl
import com.artemchep.keyguard.common.usecase.DownloadAttachmentMetadata
import com.artemchep.keyguard.crypto.CryptoGeneratorJvm
import com.artemchep.keyguard.crypto.FileEncryptionCodecJvm
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.cache.HttpCache
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GetAttachmentPreviewImplTest {
    private val fileEncryptionCodec = FileEncryptionCodecJvm(
        cryptoGenerator = CryptoGeneratorJvm(),
    )
    private val unusedSourceLoader = object : DownloadAttachmentSourceLoader {
        override fun fileLoader(
            request: DownloadAttachmentRequestData,
            writer: DownloadWriter,
        ) = error("Unexpected attachment source loader call.")
    }

    @Test
    fun `local file source is read before resolving remote metadata`() = runTest {
        val bytes = "cached preview".encodeToByteArray()
        val file = File.createTempFile("attachment-preview", ".txt")
        file.writeBytes(bytes)

        try {
            val useCase = GetAttachmentPreviewImpl(
                downloadAttachmentMetadata = object : DownloadAttachmentMetadata {
                    override fun invoke(request: DownloadAttachmentRequest): IO<DownloadAttachmentRequestData> =
                        error("Remote metadata should not be resolved for a local preview.")
                },
                fileEncryptionCodec = fileEncryptionCodec,
                fileService = FileServiceImpl(),
                httpClient = HttpClient(
                    MockEngine {
                        error("Unexpected network request.")
                    },
                ),
                downloadAttachmentSourceLoader = unusedSourceLoader,
            )

            val payload = useCase(
                request.copy(
                    localUrl = file.toURI().toString(),
                ),
            )()

            assertEquals(request.fileName, payload.fileName)
            assertEquals(bytes.size.toLong(), payload.encryptedSize)
            assertContentEquals(bytes, payload.bytes)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `direct source accepts payload at the size limit`() = runTest {
        val bytes = ByteArray(AttachmentPreviewLimits.MAX_ENCRYPTED_BYTES.toInt()) { index ->
            (index % 251).toByte()
        }
        val useCase = useCase(
            metadata = metadata(
                source = DownloadAttachmentRequestData.DirectSource(bytes),
            ),
        )

        val payload = useCase(request)()

        assertEquals(AttachmentPreviewLimits.MAX_ENCRYPTED_BYTES, payload.encryptedSize)
        assertContentEquals(bytes, payload.bytes)
    }

    @Test
    fun `url source bypasses installed response cache`() = runTest {
        val bytes = "preview".encodeToByteArray()
        var requestCount = 0
        val useCase = useCase(
            metadata = metadata(
                source = DownloadAttachmentRequestData.UrlSource(
                    url = "https://example.com/attachment",
                    urlIsOneTime = true,
                ),
            ),
            httpClient = HttpClient(
                MockEngine { request ->
                    requestCount += 1
                    assertEquals("no-store", request.headers[HttpHeaders.CacheControl])
                    respond(
                        content = ByteReadChannel(bytes),
                        status = HttpStatusCode.OK,
                        headers = headersOf(
                            HttpHeaders.CacheControl,
                            "public, max-age=3600",
                        ),
                    )
                },
            ) {
                install(HttpCache)
            },
        )

        repeat(2) {
            val payload = useCase(request)()

            assertEquals(bytes.size.toLong(), payload.encryptedSize)
            assertContentEquals(bytes, payload.bytes)
        }

        assertEquals(2, requestCount)
    }

    @Test
    fun `url source rejects content length above the size limit before reading body`() = runTest {
        val bytes = ByteArray(AttachmentPreviewLimits.MAX_ENCRYPTED_BYTES.toInt() + 1)
        val useCase = useCase(
            metadata = metadata(
                source = DownloadAttachmentRequestData.UrlSource(
                    url = "https://example.com/attachment",
                    urlIsOneTime = true,
                ),
            ),
            httpClient = HttpClient(
                MockEngine {
                    respond(
                        content = ByteReadChannel(bytes),
                        status = HttpStatusCode.OK,
                        headers = headersOf(
                            HttpHeaders.ContentLength,
                            (AttachmentPreviewLimits.MAX_ENCRYPTED_BYTES + 1).toString(),
                        ),
                    )
                },
            ),
        )

        assertFailsWith<AttachmentPreviewException.TooLarge> {
            useCase(request)()
        }
    }

    @Test
    fun `url source rejects streamed response above the size limit`() = runTest {
        val bytes = ByteArray(AttachmentPreviewLimits.MAX_ENCRYPTED_BYTES.toInt() + 1) { index ->
            (index % 251).toByte()
        }
        val useCase = useCase(
            metadata = metadata(
                source = DownloadAttachmentRequestData.UrlSource(
                    url = "https://example.com/attachment",
                    urlIsOneTime = true,
                ),
            ),
            httpClient = HttpClient(
                MockEngine {
                    respond(
                        content = ByteReadChannel(bytes),
                        status = HttpStatusCode.OK,
                        headers = headersOf(),
                    )
                },
            ),
        )

        assertFailsWith<AttachmentPreviewException.TooLarge> {
            useCase(request)()
        }
    }

    @Test
    fun `url source surfaces network failure for non-success status`() = runTest {
        val useCase = useCase(
            metadata = metadata(
                source = DownloadAttachmentRequestData.UrlSource(
                    url = "https://example.com/attachment",
                    urlIsOneTime = true,
                ),
            ),
            httpClient = HttpClient(
                MockEngine {
                    respond(
                        content = ByteReadChannel(ByteArray(0)),
                        status = HttpStatusCode.NotFound,
                        headers = headersOf(),
                    )
                },
            ),
        )

        assertFailsWith<AttachmentPreviewException.NetworkFailed> {
            useCase(request)()
        }
    }

    @Test
    fun `encrypted source decrypts in memory`() = runTest {
        val plainBytes = "secret preview".encodeToByteArray()
        val key = ByteArray(64) { index -> index.toByte() }
        val encryptedBytes = fileEncryptionCodec.encrypt(
            data = plainBytes,
            key = key,
        )
        val useCase = useCase(
            metadata = metadata(
                source = DownloadAttachmentRequestData.DirectSource(encryptedBytes),
                encryptionKey = key,
            ),
        )

        val payload = useCase(request)()

        assertEquals(encryptedBytes.size.toLong(), payload.encryptedSize)
        assertContentEquals(plainBytes, payload.bytes)
    }

    @Test
    fun `encrypted source surfaces decryption failures`() = runTest {
        val key = ByteArray(64) { index -> index.toByte() }
        val useCase = useCase(
            metadata = metadata(
                source = DownloadAttachmentRequestData.DirectSource("not encrypted".encodeToByteArray()),
                encryptionKey = key,
            ),
        )

        assertFailsWith<AttachmentPreviewException.DecryptionFailed> {
            useCase(request)()
        }
    }

    @Test
    fun `keepass source rejects known size above limit before extraction`() = runTest {
        val useCase = useCase(
            metadata = metadata(
                source = DownloadAttachmentRequestData.KeePassSource(
                    hashRef = "hashref://oversized",
                    expectedSize = AttachmentPreviewLimits.MAX_ENCRYPTED_BYTES + 1,
                ),
            ),
            sourceLoader = object : DownloadAttachmentSourceLoader {
                override fun fileLoader(
                    request: DownloadAttachmentRequestData,
                    writer: DownloadWriter,
                ) = error("Oversized source must not be extracted.")
            },
        )

        assertFailsWith<AttachmentPreviewException.TooLarge> {
            useCase(request)()
        }
    }

    @Test
    fun `keepass source enforces limit while replaying verified attachment`() = runTest {
        val bytes = ByteArray(AttachmentPreviewLimits.MAX_ENCRYPTED_BYTES.toInt() + 1)
        val useCase = useCase(
            metadata = metadata(
                source = DownloadAttachmentRequestData.KeePassSource(
                    hashRef = "hashref://streamed",
                    expectedSize = null,
                ),
            ),
            sourceLoader = sourceLoader(bytes),
        )

        assertFailsWith<AttachmentPreviewException.TooLarge> {
            useCase(request)()
        }
    }

    @Test
    fun `keepass source returns verified attachment below limit`() = runTest {
        val bytes = "verified keepass preview".encodeToByteArray()
        val useCase = useCase(
            metadata = metadata(
                source = DownloadAttachmentRequestData.KeePassSource(
                    hashRef = "hashref://preview",
                    expectedSize = bytes.size.toLong(),
                ),
            ),
            sourceLoader = sourceLoader(bytes),
        )

        val payload = useCase(request)()

        assertEquals(bytes.size.toLong(), payload.encryptedSize)
        assertContentEquals(bytes, payload.bytes)
    }

    private fun useCase(
        metadata: DownloadAttachmentRequestData,
        fileService: FileService = FileServiceImpl(),
        httpClient: HttpClient = HttpClient(
            MockEngine {
                error("Unexpected network request.")
            },
        ),
        sourceLoader: DownloadAttachmentSourceLoader = unusedSourceLoader,
    ) = GetAttachmentPreviewImpl(
        downloadAttachmentMetadata = FakeDownloadAttachmentMetadata(metadata),
        fileEncryptionCodec = fileEncryptionCodec,
        fileService = fileService,
        httpClient = httpClient,
        downloadAttachmentSourceLoader = sourceLoader,
    )

    private fun sourceLoader(
        bytes: ByteArray,
    ) = object : DownloadAttachmentSourceLoader {
        override fun fileLoader(
            request: DownloadAttachmentRequestData,
            writer: DownloadWriter,
        ) = flow {
            writer.writeBytes(bytes)
            emit(DownloadProgress.Complete(writer.locationUri().right()))
        }
    }

    private fun metadata(
        source: DownloadAttachmentRequestData.Source,
        encryptionKey: ByteArray? = null,
    ) = DownloadAttachmentRequestData(
        localCipherId = request.localCipherId,
        remoteCipherId = request.remoteCipherId,
        attachmentId = request.attachmentId,
        source = source,
        name = "preview.txt",
        encryptionKey = encryptionKey,
    )

    private class FakeDownloadAttachmentMetadata(
        private val metadata: DownloadAttachmentRequestData,
    ) : DownloadAttachmentMetadata {
        override fun invoke(request: DownloadAttachmentRequest): IO<DownloadAttachmentRequestData> = {
            metadata
        }
    }

    private companion object {
        val request = AttachmentPreviewRequest(
            localCipherId = "local-cipher",
            remoteCipherId = "remote-cipher",
            attachmentId = "attachment",
            fileName = "preview.txt",
        )
    }
}
