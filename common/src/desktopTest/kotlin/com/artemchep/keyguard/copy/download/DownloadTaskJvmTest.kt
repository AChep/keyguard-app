package com.artemchep.keyguard.copy.download

import arrow.core.Either
import com.artemchep.keyguard.common.service.crypto.FileEncryptor
import com.artemchep.keyguard.common.service.download.CacheDirProvider
import com.artemchep.keyguard.common.service.download.DownloadProgress
import com.artemchep.keyguard.common.service.download.DownloadWriter
import com.artemchep.keyguard.crypto.CryptoGeneratorJvm
import com.artemchep.keyguard.platform.LocalPath
import com.artemchep.keyguard.platform.toLocalPath
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.test.runTest
import kotlinx.io.Source
import kotlinx.io.readByteArray
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class DownloadTaskJvmTest {
    @Test
    fun `url loader deletes cache file after successful local path download`() = runTest {
        val root = createTempDirectory("download-task")
        val cacheRoot = root.resolve("cache")
        val output = root.resolve("payload.bin")
        val data = "payload".encodeToByteArray()

        try {
            val task = downloadTask(
                cacheRoot = cacheRoot.toLocalPath(),
                responseBody = data,
            )

            val complete = task
                .fileLoader(
                    url = "https://example.com/payload.bin",
                    key = null,
                    writer = DownloadWriter.LocalPathWriter(output.toLocalPath()),
                )
                .filterIsInstance<DownloadProgress.Complete>()
                .single()

            val result = assertIs<Either.Right<String?>>(complete.result)
            assertEquals(output.toFile().toURI().toString(), result.value)
            assertContentEquals(data, output.readBytes())
            assertFalse(
                cacheRoot.resolve("download_cache")
                    .toFile()
                    .walkTopDown()
                    .any { it.isFile },
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun downloadTask(
        cacheRoot: LocalPath,
        responseBody: ByteArray,
    ) = DownloadTaskJvm(
        cacheDirProvider = StaticCacheDirProvider(cacheRoot),
        cryptoGenerator = CryptoGeneratorJvm(),
        okHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(responseBody.toResponseBody())
                    .build()
            }
            .build(),
        fileEncryptor = CopyingFileEncryptor(),
    )
}

private data class StaticCacheDirProvider(
    private val path: LocalPath,
) : CacheDirProvider {
    override suspend fun get(): LocalPath = path

    override fun getBlocking(): LocalPath = path
}

private class CopyingFileEncryptor : FileEncryptor {
    override fun decode(
        input: ByteArray,
        key: ByteArray,
    ): ByteArray = input

    override fun decode(
        input: InputStream,
        key: ByteArray,
    ): InputStream = ByteArrayInputStream(input.readBytes())

    override fun encode(
        data: ByteArray,
        key: ByteArray,
    ): ByteArray = data

    override fun encode(
        input: Source,
        output: LocalPath,
        key: ByteArray,
    ): FileEncryptor.EncodeResult {
        val data = input.readByteArray()
        File(output.value).writeBytes(data)
        return FileEncryptor.EncodeResult(
            plainSize = data.size.toLong(),
            encryptedSize = data.size.toLong(),
        )
    }
}
