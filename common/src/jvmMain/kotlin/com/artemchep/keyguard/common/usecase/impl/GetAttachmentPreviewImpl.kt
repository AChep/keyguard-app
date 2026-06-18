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
import com.artemchep.keyguard.common.service.crypto.FileEncryptor
import com.artemchep.keyguard.common.service.file.FileService
import com.artemchep.keyguard.common.usecase.DownloadAttachmentMetadata
import com.artemchep.keyguard.common.usecase.GetAttachmentPreview
import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import org.kodein.di.DirectDI
import org.kodein.di.instance
import java.io.ByteArrayOutputStream

class GetAttachmentPreviewImpl(
    private val downloadAttachmentMetadata: DownloadAttachmentMetadata,
    private val fileEncryptor: FileEncryptor,
    private val fileService: FileService,
    private val httpClient: HttpClient,
) : GetAttachmentPreview {
    constructor(directDI: DirectDI) : this(
        downloadAttachmentMetadata = directDI.instance(),
        fileEncryptor = directDI.instance(),
        fileService = directDI.instance(),
        httpClient = directDI.instance(),
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
        }
        val plainBytes = metadata.encryptionKey
            ?.let { key ->
                try {
                    fileEncryptor.decode(encryptedBytes, key)
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
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
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
        return output.toByteArray()
    }

    private suspend fun downloadUrlToByteArray(
        url: String,
    ): ByteArray = try {
        httpClient.prepareGet(url).execute { response ->
            if (!response.status.isSuccess()) {
                throw AttachmentPreviewException.NetworkFailed()
            }

            val contentLength = response.headers[HttpHeaders.ContentLength]
                ?.toLongOrNull()
            if (contentLength != null) {
                ensureWithinLimit(size = contentLength)
            }

            val channel = response.bodyAsChannel()
            val output = ByteArrayOutputStream(
                contentLength
                    ?.coerceAtMost(AttachmentPreviewLimits.MAX_ENCRYPTED_BYTES)
                    ?.toInt()
                    ?: DEFAULT_BUFFER_SIZE,
            )
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val read = channel.readAvailable(buffer, 0, buffer.size)
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

            output.toByteArray()
        }
    } catch (e: AttachmentPreviewException) {
        throw e
    } catch (e: Throwable) {
        e.throwIfFatalOrCancellation()
        throw AttachmentPreviewException.NetworkFailed(e)
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
