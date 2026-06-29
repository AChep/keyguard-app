package com.artemchep.keyguard.common.service.download

import arrow.core.left
import arrow.core.right
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.file.FileService
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.util.CodeException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

class DownloadInfoRepositoryController(
    private val downloadRepository: DownloadRepository,
    private val base64Service: Base64Service,
    private val cryptoGenerator: CryptoGenerator,
) {
    private val mutex = Mutex()

    suspend fun getOrPutDownloadFileEntity(
        url: String,
        urlIsOneTime: Boolean,
        name: String,
        tag: DownloadInfoEntity.AttachmentDownloadTag,
        encryptionKey: ByteArray?,
        error: DownloadInfoEntity.Error?,
    ): DownloadInfoEntity = mutex.withLock {
        val now = Clock.System.now()
        val encryptionKeyBase64 = encryptionKey?.let(base64Service::encodeToString)
        val existing = downloadRepository.getByTag(tag)
            .bind()
        if (existing != null) {
            if (
                existing.url == url &&
                existing.urlIsOneTime == urlIsOneTime &&
                existing.name == name &&
                existing.encryptionKeyBase64 == encryptionKeyBase64 &&
                existing.error == error
            ) {
                return@withLock existing
            }

            val updated = existing.copy(
                revisionDate = now,
                url = url,
                urlIsOneTime = urlIsOneTime,
                name = name,
                encryptionKeyBase64 = encryptionKeyBase64,
                error = error,
            )
            downloadRepository.put(updated)
                .bind()
            return@withLock updated
        }

        val created = DownloadInfoEntity(
            id = cryptoGenerator.uuid(),
            url = url,
            urlIsOneTime = urlIsOneTime,
            name = name,
            localCipherId = tag.localCipherId,
            remoteCipherId = tag.remoteCipherId,
            attachmentId = tag.attachmentId,
            createdDate = now,
            encryptionKeyBase64 = encryptionKeyBase64,
            error = error,
        )
        downloadRepository.put(created)
            .bind()
        created
    }

    suspend fun replaceDownloadFileEntity(
        id: String,
        error: DownloadInfoEntity.Error?,
    ): DownloadInfoEntity? = mutex.withLock {
        val existing = downloadRepository.getById(id)
            .bind()
            ?: return@withLock null
        if (existing.error == error) {
            return@withLock existing
        }

        val updated = existing.copy(
            revisionDate = Clock.System.now(),
            error = error,
        )
        downloadRepository.put(updated)
            .bind()
        updated
    }

    suspend fun removeByDownloadId(
        id: String,
        beforeRemove: suspend (DownloadInfoEntity) -> Unit = {},
    ) = mutex.withLock {
        downloadRepository.getById(id)
            .bind()
            ?.let { beforeRemove(it) }
        downloadRepository.removeById(id)
            .bind()
    }

    suspend fun removeByTag(
        tag: DownloadInfoEntity.AttachmentDownloadTag,
        beforeRemove: suspend (DownloadInfoEntity) -> Unit = {},
    ) = mutex.withLock {
        downloadRepository.getByTag(tag)
            .bind()
            ?.let { beforeRemove(it) }
        downloadRepository.removeByTag(tag)
            .bind()
    }
}

fun DownloadInfoEntity.downloadTag(): DownloadInfoEntity.AttachmentDownloadTag =
    DownloadInfoEntity.AttachmentDownloadTag(
        localCipherId = localCipherId,
        remoteCipherId = remoteCipherId,
        attachmentId = attachmentId,
    )

fun DownloadInfoEntity.toStoredDownloadProgress(
    uri: String,
    fileService: FileService,
): DownloadProgress = toStoredDownloadProgress(
    uri = uri,
    fileExists = fileService.exists(uri),
)

fun DownloadInfoEntity.toStoredDownloadProgress(
    uri: String,
    fileExists: Boolean,
): DownloadProgress {
    val error = error
    return when {
        fileExists -> DownloadProgress.Complete(uri.right())
        error != null -> DownloadProgress.Complete(
            CodeException(
                code = error.code,
                description = error.message,
            ).left(),
        )
        else -> DownloadProgress.Loading()
    }
}
