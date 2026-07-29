package com.artemchep.keyguard.common.service.download.store

import com.artemchep.keyguard.common.service.download.DownloadInfoEntity
import com.artemchep.keyguard.copy.DataDirectory
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant

class DownloadFileStoreDesktopTest {
    private val dataDirectory = DataDirectory()
    private val store = DownloadFileStoreDesktop(dataDirectory)

    @Test
    fun uriPreservesTheExistingAppDirsDownloadLocation() = runTest {
        val info = downloadInfo(name = "дані_日本語.bin")
        val expected = File(dataDirectory.downloadsBlocking())
            .resolve(info.name)
            .toURI()
            .toString()

        assertEquals(expected, store.uri(info))
    }

    @Test
    fun untrustedFileNameCannotAddOrEscapeDescendants() = runTest {
        listOf(
            "nested/payload.bin",
            "nested\\payload.bin",
            "payload:stream",
            ".",
            "..",
        ).forEach { name ->
            assertFailsWith<IllegalArgumentException>(message = name) {
                store.uri(downloadInfo(name))
            }
        }
    }

    private fun downloadInfo(name: String) = DownloadInfoEntity(
        id = "download-id",
        localCipherId = "local-cipher-id",
        remoteCipherId = "remote-cipher-id",
        attachmentId = "attachment-id",
        url = "https://example.com/file",
        urlIsOneTime = false,
        name = name,
        createdDate = Instant.DISTANT_PAST,
    )
}
