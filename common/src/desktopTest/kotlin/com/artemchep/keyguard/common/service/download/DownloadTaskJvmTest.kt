package com.artemchep.keyguard.common.service.download

import arrow.core.Either
import com.artemchep.keyguard.common.exception.HttpException
import com.artemchep.keyguard.common.service.crypto.FileEncryptor
import com.artemchep.keyguard.common.service.file.toFileUriString
import com.artemchep.keyguard.crypto.CryptoGeneratorJvm
import com.artemchep.keyguard.platform.LocalPath
import com.artemchep.keyguard.platform.toLocalPath
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.test.runTest
import kotlinx.io.Source
import kotlinx.io.readByteArray
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.File
import java.io.IOException
import java.nio.file.Path
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
            assertNoCacheFiles(cacheRoot)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `url loader writes local path download with short filename`() = runTest {
        val root = createTempDirectory("download-task")
        val cacheRoot = root.resolve("cache")
        val output = root.resolve("a")
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
            assertNoCacheFiles(cacheRoot)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `byte array loader decrypts and writes local path download`() = runTest {
        val root = createTempDirectory("download-task")
        val cacheRoot = root.resolve("cache")
        val output = root.resolve("nested").resolve("payload.bin")
        val encrypted = "encrypted".encodeToByteArray()
        val plain = "plain payload".encodeToByteArray()

        try {
            val task = downloadTask(
                cacheRoot = cacheRoot.toLocalPath(),
                responseBody = ByteArray(0),
                fileEncryptor = ByteArrayDecodingFileEncryptor(plain),
            )

            val complete = task
                .fileLoader(
                    data = encrypted,
                    key = byteArrayOf(1, 2, 3),
                    writer = DownloadWriter.LocalPathWriter(output.toLocalPath()),
                )
                .filterIsInstance<DownloadProgress.Complete>()
                .single()

            val result = assertIs<Either.Right<String?>>(complete.result)
            assertEquals(output.toLocalPath().toFileUriString(), result.value)
            assertContentEquals(plain, output.readBytes())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `url loader deletes cache and leaves no local path file after failed decrypt`() = runTest {
        val root = createTempDirectory("download-task")
        val cacheRoot = root.resolve("cache")
        val output = root.resolve("payload.bin")
        val data = "encrypted".encodeToByteArray()

        try {
            val task = downloadTask(
                cacheRoot = cacheRoot.toLocalPath(),
                responseBody = data,
                fileEncryptor = FailingFileEncryptor(),
            )

            val complete = task
                .fileLoader(
                    url = "https://example.com/payload.bin",
                    key = byteArrayOf(1),
                    writer = DownloadWriter.LocalPathWriter(output.toLocalPath()),
                )
                .filterIsInstance<DownloadProgress.Complete>()
                .single()

            assertIs<Either.Left<Throwable>>(complete.result)
            assertFalse(output.toFile().exists())
            assertNoCacheFiles(cacheRoot)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `url loader preserves http status failure`() = runTest {
        val root = createTempDirectory("download-task")
        val cacheRoot = root.resolve("cache")
        val output = root.resolve("payload.bin")

        try {
            val task = downloadTask(
                cacheRoot = cacheRoot.toLocalPath(),
                responseBody = "missing".encodeToByteArray(),
                responseStatus = HttpStatusCode.NotFound,
            )

            val complete = task
                .fileLoader(
                    url = "https://example.com/payload.bin",
                    key = null,
                    writer = DownloadWriter.LocalPathWriter(output.toLocalPath()),
                )
                .filterIsInstance<DownloadProgress.Complete>()
                .single()

            val result = assertIs<Either.Left<Throwable>>(complete.result)
            val error = assertIs<HttpException>(result.value)
            assertEquals(HttpStatusCode.NotFound, error.statusCode)
            assertFalse(output.toFile().exists())
            assertNoCacheFiles(cacheRoot)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun downloadTask(
        cacheRoot: LocalPath,
        responseBody: ByteArray,
        responseStatus: HttpStatusCode = HttpStatusCode.OK,
        fileEncryptor: FileEncryptor = CopyingFileEncryptor(),
    ) = DownloadTaskJvm(
        cacheDirProvider = StaticCacheDirProvider(cacheRoot),
        cryptoGenerator = CryptoGeneratorJvm(),
        okHttpClient = okHttpClient(
            responseBody = responseBody,
            responseStatus = responseStatus,
        ),
        fileEncryptor = fileEncryptor,
    )
}

private fun okHttpClient(
    responseBody: ByteArray,
    responseStatus: HttpStatusCode,
) = OkHttpClient.Builder()
    .addInterceptor { chain ->
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(responseStatus.value)
            .message(responseStatus.description)
            .body(responseBody.toResponseBody())
            .build()
    }
    .build()

private fun assertNoCacheFiles(
    cacheRoot: Path,
) {
    assertFalse(
        cacheRoot.resolve("download_cache")
            .toFile()
            .walkTopDown()
            .any { it.isFile },
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

private class ByteArrayDecodingFileEncryptor(
    private val plain: ByteArray,
) : FileEncryptor {
    override fun decode(
        input: ByteArray,
        key: ByteArray,
    ): ByteArray = plain

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

private class FailingFileEncryptor : FileEncryptor {
    override fun decode(
        input: ByteArray,
        key: ByteArray,
    ): ByteArray = throw IOException("Message authentication codes do not match!")

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
