package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.throwIfFatalOrCancellation
import com.artemchep.keyguard.common.model.AttachmentPreviewException
import com.artemchep.keyguard.common.model.AttachmentPreviewLimits
import com.artemchep.keyguard.common.model.AttachmentPreviewPayload
import com.artemchep.keyguard.common.model.AttachmentPreviewRequest
import com.artemchep.keyguard.common.model.DownloadAttachmentRequest
import com.artemchep.keyguard.common.model.DownloadAttachmentRequestData
import com.artemchep.keyguard.common.service.crypto.FileEncryptionCodec
import com.artemchep.keyguard.common.service.download.DownloadAttachmentSourceLoader
import com.artemchep.keyguard.common.service.download.DownloadWriter
import com.artemchep.keyguard.common.service.download.awaitCompleteResult
import com.artemchep.keyguard.common.service.download.util.downloadToByteArray
import com.artemchep.keyguard.common.service.file.FileService
import com.artemchep.keyguard.common.usecase.DownloadAttachmentMetadata
import com.artemchep.keyguard.common.usecase.GetAttachmentPreview
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import org.kodein.di.DirectDI
import org.kodein.di.instance

private const val PREVIEW_BUFFER_SIZE = 8 * 1024

class GetAttachmentPreviewImpl(
    private val downloadAttachmentMetadata: DownloadAttachmentMetadata,
    private val fileEncryptionCodec: FileEncryptionCodec,
    private val fileService: FileService,
    private val httpClient: HttpClient,
    private val downloadAttachmentSourceLoader: DownloadAttachmentSourceLoader,
) : GetAttachmentPreview {
    constructor(directDI: DirectDI) : this(
        downloadAttachmentMetadata = directDI.instance(),
        fileEncryptionCodec = directDI.instance(),
        fileService = directDI.instance(),
        httpClient = directDI.instance(),
        downloadAttachmentSourceLoader = directDI.instance(),
    )

    override fun invoke(
        request: AttachmentPreviewRequest,
    ): IO<AttachmentPreviewPayload> = ioEffect(Dispatchers.IO) {
        request.encryptedSize?.let(::ensureWithinLimit)

        request.localUrl
            ?.let { localUrl ->
                val payload = readLocalFileToPayloadOrNull(
                    fileName = request.fileName,
                    localUrl = localUrl,
                )
                if (payload != null) {
                    return@ioEffect payload
                }
            }

        val downloadRequest = DownloadAttachmentRequest.ByLocalCipherAttachment(
            localCipherId = request.localCipherId,
            remoteCipherId = request.remoteCipherId,
            attachmentId = request.attachmentId,
        )
        val metadata = downloadAttachmentMetadata(downloadRequest).bind()
        val encryptedBytes = when (val source = metadata.source) {
            is DownloadAttachmentRequestData.DirectSource -> source.data.also {
                ensureWithinLimit(size = it.size.toLong())
            }

            is DownloadAttachmentRequestData.UrlSource -> downloadUrlToByteArray(source.url)

            is DownloadAttachmentRequestData.KeePassSource -> {
                source.expectedSize?.let(::ensureWithinLimit)
                downloadKeePassToByteArray(metadata)
            }
        }
        val plainBytes = metadata.encryptionKey
            ?.let { key ->
                try {
                    fileEncryptionCodec.decrypt(encryptedBytes, key)
                } catch (e: Throwable) {
                    e.throwIfFatalOrCancellation()
                    throw AttachmentPreviewException.DecryptionFailed(e)
                }
            }
            ?: encryptedBytes

        AttachmentPreviewPayload(
            fileName = metadata.name,
            encryptedSize = encryptedBytes.size.toLong(),
            bytes = plainBytes,
        )
    }

    private fun readLocalFileToPayloadOrNull(
        fileName: String,
        localUrl: String,
    ): AttachmentPreviewPayload? {
        if (!fileService.exists(localUrl)) {
            return null
        }

        val bytes = try {
            readLocalFileToByteArray(localUrl)
        } catch (e: AttachmentPreviewException) {
            throw e
        } catch (e: Throwable) {
            e.throwIfFatalOrCancellation()
            return null
        }
        return AttachmentPreviewPayload(
            fileName = fileName,
            encryptedSize = bytes.size.toLong(),
            bytes = bytes,
        )
    }

    private fun readLocalFileToByteArray(
        localUrl: String,
    ): ByteArray {
        val output = Buffer()
        val buffer = ByteArray(PREVIEW_BUFFER_SIZE)
        var total = 0L
        fileService.readFromFile(localUrl).use { source ->
            while (true) {
                val read = source.readAtMostTo(buffer)
                if (read == -1) {
                    break
                }
                if (read == 0) {
                    continue
                }

                total += read
                ensureWithinLimit(size = total)
                output.write(buffer, 0, read)
            }
        }
        return output.readByteArray()
    }

    private suspend fun downloadUrlToByteArray(
        url: String,
    ): ByteArray = try {
        httpClient.downloadToByteArray(
            url = url,
            bufferSize = PREVIEW_BUFFER_SIZE,
            validateSize = ::ensureWithinLimit,
        )
    } catch (e: AttachmentPreviewException) {
        throw e
    } catch (e: Throwable) {
        e.throwIfFatalOrCancellation()
        throw AttachmentPreviewException.NetworkFailed(e)
    }

    private suspend fun downloadKeePassToByteArray(
        metadata: DownloadAttachmentRequestData,
    ): ByteArray {
        val output = Buffer()
        var size = 0L
        val limitedOutput = object : RawSink {
            override fun write(
                source: Buffer,
                byteCount: Long,
            ) {
                val nextSize = size + byteCount
                ensureWithinLimit(nextSize)
                output.write(source, byteCount)
                size = nextSize
            }

            override fun flush() = Unit

            override fun close() = Unit
        }.buffered()
        downloadAttachmentSourceLoader
            .fileLoader(
                request = metadata,
                writer = DownloadWriter.SinkWriter(limitedOutput),
            )
            .awaitCompleteResult()
            .onLeft { error -> throw error }
        return output.readByteArray()
    }

    private fun ensureWithinLimit(
        size: Long,
    ) {
        if (size > AttachmentPreviewLimits.MAX_ENCRYPTED_BYTES) {
            throw AttachmentPreviewException.TooLarge(
                maxBytes = AttachmentPreviewLimits.MAX_ENCRYPTED_BYTES,
                actualBytes = size,
            )
        }
    }
}
