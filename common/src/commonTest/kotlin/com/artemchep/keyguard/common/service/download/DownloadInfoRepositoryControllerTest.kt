package com.artemchep.keyguard.common.service.download

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.service.text.impl.Base64ServiceImpl
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DownloadInfoRepositoryControllerTest {
    @Test
    fun `creates updates and removes download entity by tag`() = runTest {
        val base64Service = Base64ServiceImpl()
        val repository = DownloadRepositoryInMemory()
        val controller = DownloadInfoRepositoryController(
            downloadRepository = repository,
            base64Service = base64Service,
            cryptoGenerator = FixedUuidCryptoGenerator("download-1"),
        )
        val tag = downloadTag()
        val key = byteArrayOf(1, 2, 3)

        val created = controller.getOrPutDownloadFileEntity(
            url = "https://example.com/one",
            urlIsOneTime = true,
            name = "attachment.txt",
            tag = tag,
            encryptionKey = key,
            error = null,
        )

        assertEquals("download-1", created.id)
        assertEquals("https://example.com/one", created.url)
        assertEquals(true, created.urlIsOneTime)
        assertEquals(base64Service.encodeToString(key), created.encryptionKeyBase64)
        assertEquals(listOf(created), repository.get().first())

        val same = controller.getOrPutDownloadFileEntity(
            url = "https://example.com/one",
            urlIsOneTime = true,
            name = "attachment.txt",
            tag = tag,
            encryptionKey = key,
            error = null,
        )
        assertEquals(created, same)
        assertEquals(1, repository.get().first().size)

        val error = DownloadInfoEntity.Error(
            code = 500,
            attempt = 2,
            message = "server error",
        )
        val updated = controller.getOrPutDownloadFileEntity(
            url = "https://example.com/two",
            urlIsOneTime = false,
            name = "attachment-2.txt",
            tag = tag,
            encryptionKey = null,
            error = error,
        )

        assertEquals(created.id, updated.id)
        assertEquals("https://example.com/two", updated.url)
        assertFalse(updated.urlIsOneTime)
        assertEquals("attachment-2.txt", updated.name)
        assertEquals(null, updated.encryptionKeyBase64)
        assertEquals(error, updated.error)
        assertEquals(listOf(updated), repository.get().first())

        var removed: DownloadInfoEntity? = null
        controller.removeByTag(tag) { info ->
            removed = info
        }

        assertEquals(updated, removed)
        assertEquals(emptyList(), repository.get().first())
    }

    @Test
    fun `replace updates and clears completion error`() = runTest {
        val repository = DownloadRepositoryInMemory()
        val controller = DownloadInfoRepositoryController(
            downloadRepository = repository,
            base64Service = Base64ServiceImpl(),
            cryptoGenerator = FixedUuidCryptoGenerator("download-1"),
        )
        val created = controller.getOrPutDownloadFileEntity(
            url = "https://example.com/one",
            urlIsOneTime = false,
            name = "attachment.txt",
            tag = downloadTag(),
            encryptionKey = null,
            error = null,
        )
        val error = DownloadInfoEntity.Error(
            code = 404,
            message = "not found",
        )

        val failed = controller.replaceDownloadFileEntity(
            id = created.id,
            error = error,
        )
        val cleared = controller.replaceDownloadFileEntity(
            id = created.id,
            error = null,
        )

        assertEquals(error, failed?.error)
        assertEquals(null, cleared?.error)
        assertEquals(null, repository.getById(created.id).bind()?.error)
    }

    @Test
    fun `create preserves initial error`() = runTest {
        val repository = DownloadRepositoryInMemory()
        val controller = DownloadInfoRepositoryController(
            downloadRepository = repository,
            base64Service = Base64ServiceImpl(),
            cryptoGenerator = FixedUuidCryptoGenerator("download-1"),
        )
        val error = DownloadInfoEntity.Error(
            code = 503,
            message = "unavailable",
        )

        val created = controller.getOrPutDownloadFileEntity(
            url = "https://example.com/one",
            urlIsOneTime = false,
            name = "attachment.txt",
            tag = downloadTag(),
            encryptionKey = null,
            error = error,
        )

        assertEquals(error, created.error)
        assertEquals(error, repository.getById(created.id).bind()?.error)
    }
}

private fun downloadTag() = DownloadInfoEntity.AttachmentDownloadTag(
    localCipherId = "local-cipher",
    remoteCipherId = "remote-cipher",
    attachmentId = "attachment",
)
