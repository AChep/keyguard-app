package com.artemchep.keyguard.copy.download

import arrow.core.Either
import com.artemchep.keyguard.android.downloader.journal.room.DownloadInfoEntity2
import com.artemchep.keyguard.common.service.crypto.FileEncryptor
import com.artemchep.keyguard.common.service.download.CacheDirProvider
import com.artemchep.keyguard.common.service.download.DownloadProgress
import com.artemchep.keyguard.common.service.download.DownloadWriter
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.crypto.CryptoGeneratorJvm
import com.artemchep.keyguard.platform.LocalPath
import com.artemchep.keyguard.platform.toLocalPath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.emptyFlow
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
import java.io.IOException
import java.io.InputStream
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readBytes
import kotlin.coroutines.CoroutineContext
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
    fun `attachment client deletes cache and leaves no final file after failed decrypt`() = runTest {
        val root = createTempDirectory("download-client")
        val cacheRoot = root.resolve("cache")
        val output = root.resolve("payload.bin").toFile()
        val data = "encrypted".encodeToByteArray()

        try {
            val client = TestDownloadClient(
                cacheDirProvider = StaticCacheDirProvider(cacheRoot.toLocalPath()),
                cryptoGenerator = CryptoGeneratorJvm(),
                windowCoroutineScope = TestWindowCoroutineScope(backgroundScope),
                okHttpClient = okHttpClient(responseBody = data),
                fileEncryptor = FailingFileEncryptor(),
            )
            val tag = DownloadInfoEntity2.AttachmentDownloadTag(
                localCipherId = "local-cipher",
                remoteCipherId = "remote-cipher",
                attachmentId = "attachment",
            )

            val complete = client
                .fileLoader(
                    downloadId = "download",
                    url = "https://example.com/payload.bin",
                    tag = tag,
                    file = output,
                    fileKey = byteArrayOf(1),
                    cancelFlow = emptyFlow(),
                )
                .filterIsInstance<DownloadProgress.Complete>()
                .single()

            assertIs<Either.Left<Throwable>>(complete.result)
            assertFalse(output.exists())
            assertNoCacheFiles(cacheRoot)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun downloadTask(
        cacheRoot: LocalPath,
        responseBody: ByteArray,
        fileEncryptor: FileEncryptor = CopyingFileEncryptor(),
    ) = DownloadTaskJvm(
        cacheDirProvider = StaticCacheDirProvider(cacheRoot),
        cryptoGenerator = CryptoGeneratorJvm(),
        okHttpClient = okHttpClient(responseBody = responseBody),
        fileEncryptor = fileEncryptor,
    )
}

private fun okHttpClient(
    responseBody: ByteArray,
) = OkHttpClient.Builder()
    .addInterceptor { chain ->
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
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

private class TestDownloadClient(
    cacheDirProvider: CacheDirProvider,
    cryptoGenerator: CryptoGeneratorJvm,
    windowCoroutineScope: WindowCoroutineScope,
    okHttpClient: OkHttpClient,
    fileEncryptor: FileEncryptor,
) : DownloadClientJvm(
    cacheDirProvider = cacheDirProvider,
    cryptoGenerator = cryptoGenerator,
    windowCoroutineScope = windowCoroutineScope,
    okHttpClient = okHttpClient,
    fileEncryptor = fileEncryptor,
)

private data class TestWindowCoroutineScope(
    private val scope: CoroutineScope,
) : WindowCoroutineScope {
    override val coroutineContext: CoroutineContext
        get() = scope.coroutineContext
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

private class FailingFileEncryptor : FileEncryptor {
    override fun decode(
        input: ByteArray,
        key: ByteArray,
    ): ByteArray = throw IOException("Message authentication codes do not match!")

    override fun decode(
        input: InputStream,
        key: ByteArray,
    ): InputStream {
        input.readBytes()
        return FailingDecryptInputStream()
    }

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

private class FailingDecryptInputStream : InputStream() {
    private val data = "partial plain text".encodeToByteArray()
    private var offset = 0

    override fun read(): Int {
        if (offset < data.size) {
            return data[offset++].toInt() and 0xff
        }
        throw IOException("Message authentication codes do not match!")
    }

    override fun read(
        b: ByteArray,
        off: Int,
        len: Int,
    ): Int {
        if (offset >= data.size) {
            throw IOException("Message authentication codes do not match!")
        }

        val count = minOf(len, data.size - offset)
        data.copyInto(
            destination = b,
            destinationOffset = off,
            startIndex = offset,
            endIndex = offset + count,
        )
        offset += count
        return count
    }
}
