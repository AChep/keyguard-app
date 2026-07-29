package com.artemchep.keyguard.common.service.download

import arrow.core.Either
import com.artemchep.keyguard.common.exception.HttpException
import com.artemchep.keyguard.common.service.crypto.FileEncryptionCodec
import com.artemchep.keyguard.crypto.CryptoGeneratorApple
import com.artemchep.keyguard.crypto.FileEncryptionCodecApple
import com.artemchep.keyguard.platform.LocalPath
import com.artemchep.keyguard.util.io.atomic.AtomicFileDestination
import com.artemchep.keyguard.util.io.atomic.AtomicPathComponent
import com.artemchep.keyguard.util.io.atomic.AtomicRelativePath
import com.artemchep.keyguard.util.io.resolve
import com.artemchep.keyguard.util.io.toFileUriString
import com.artemchep.keyguard.util.io.toKotlinxIoPath
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.buffered
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class IosDownloadTaskImplTest {
    @Test
    fun `url loader writes unencrypted local path download`() = runTest {
        val root = tempDir()
        val output = root.resolve("payload.bin")
        val data = "payload".encodeToByteArray()
        val task = downloadTask(
            responseBody = data,
        )

        val complete = task
            .fileLoader(
                url = "https://example.com/payload.bin",
                key = null,
                writer = output.localPathWriter(),
            )
            .filterIsInstance<DownloadProgress.Complete>()
            .single()

        val result = assertIs<Either.Right<String?>>(complete.result)
        assertEquals(output.toFileUriString(), result.value)
        assertContentEquals(data, output.readBytes())
    }

    @Test
    fun `url loader decrypts local path download`() = runTest {
        val root = tempDir()
        val output = root.resolve("payload.bin")
        val plain = "plain payload".encodeToByteArray()
        val key = ByteArray(64) { index ->
            index.toByte()
        }
        val fileEncryptionCodec = FileEncryptionCodecApple(CryptoGeneratorApple())
        val encrypted = fileEncryptionCodec.encrypt(
            data = plain,
            key = key,
        )
        val task = downloadTask(
            responseBody = encrypted,
            fileEncryptionCodec = fileEncryptionCodec,
        )

        val complete = task
            .fileLoader(
                url = "https://example.com/payload.bin",
                key = key,
                writer = output.localPathWriter(),
            )
            .filterIsInstance<DownloadProgress.Complete>()
            .single()

        val result = assertIs<Either.Right<String?>>(complete.result)
        assertEquals(output.toFileUriString(), result.value)
        assertContentEquals(plain, output.readBytes())
    }

    @Test
    fun `url loader emits download progress`() = runTest {
        val root = tempDir()
        val output = root.resolve("payload.bin")
        val data = ByteArray(64 * 1024) { index ->
            (index % 251).toByte()
        }
        val task = downloadTask(
            responseBody = data,
        )

        val events = task
            .fileLoader(
                url = "https://example.com/payload.bin",
                key = null,
                writer = output.localPathWriter(),
            )
            .toList()
        val progress = events
            .filterIsInstance<DownloadProgress.Loading>()
            .filter { it.downloaded != null }

        assertEquals(data.size.toLong(), progress.last().downloaded)
        assertEquals(data.size.toLong(), progress.last().total)
    }

    @Test
    fun `url loader decrypts sink download after verification`() = runTest {
        val plain = "plain payload".encodeToByteArray()
        val key = ByteArray(64) { index ->
            index.toByte()
        }
        val fileEncryptionCodec = FileEncryptionCodecApple(CryptoGeneratorApple())
        val encrypted = fileEncryptionCodec.encrypt(
            data = plain,
            key = key,
        )
        val sink = Buffer()
        val task = downloadTask(
            responseBody = encrypted,
            fileEncryptionCodec = fileEncryptionCodec,
        )

        val complete = task
            .fileLoader(
                url = "https://example.com/payload.bin",
                key = key,
                writer = DownloadWriter.SinkWriter(sink),
            )
            .filterIsInstance<DownloadProgress.Complete>()
            .single()

        val result = assertIs<Either.Right<String?>>(complete.result)
        assertEquals(null, result.value)
        assertContentEquals(plain, sink.readByteArray())
    }

    @Test
    fun `url loader does not publish local path after failed decrypt`() = runTest {
        val root = tempDir()
        val output = root.resolve("payload.bin")
        val key = ByteArray(64) { index ->
            index.toByte()
        }
        val wrongKey = ByteArray(64) { index ->
            (index + 1).toByte()
        }
        val fileEncryptionCodec = FileEncryptionCodecApple(CryptoGeneratorApple())
        val encrypted = fileEncryptionCodec.encrypt(
            data = "plain payload".encodeToByteArray(),
            key = key,
        )
        val task = downloadTask(
            responseBody = encrypted,
            fileEncryptionCodec = fileEncryptionCodec,
        )

        val complete = task
            .fileLoader(
                url = "https://example.com/payload.bin",
                key = wrongKey,
                writer = output.localPathWriter(),
            )
            .filterIsInstance<DownloadProgress.Complete>()
            .single()

        assertIs<Either.Left<Throwable>>(complete.result)
        assertFalse(output.exists())
    }

    @Test
    fun `url loader preserves http status failure`() = runTest {
        val root = tempDir()
        val output = root.resolve("payload.bin")
        val task = downloadTask(
            responseBody = "missing".encodeToByteArray(),
            responseStatus = HttpStatusCode.NotFound,
        )

        val complete = task
            .fileLoader(
                url = "https://example.com/payload.bin",
                key = null,
                writer = output.localPathWriter(),
            )
            .filterIsInstance<DownloadProgress.Complete>()
            .single()

        val result = assertIs<Either.Left<Throwable>>(complete.result)
        val error = assertIs<HttpException>(result.value)
        assertEquals(HttpStatusCode.NotFound, error.statusCode)
        assertFalse(output.exists())
    }

    private fun downloadTask(
        responseBody: ByteArray,
        responseStatus: HttpStatusCode = HttpStatusCode.OK,
        fileEncryptionCodec: FileEncryptionCodec =
            FileEncryptionCodecApple(CryptoGeneratorApple()),
    ) = DownloadTaskImpl(
        httpClient = httpClient(responseBody, responseStatus),
        fileEncryptionCodec = fileEncryptionCodec,
    )
}

private fun httpClient(
    responseBody: ByteArray,
    responseStatus: HttpStatusCode,
) = HttpClient(
    MockEngine {
        respond(
            content = ByteReadChannel(responseBody),
            status = responseStatus,
            headers = headersOf(
                HttpHeaders.ContentLength,
                responseBody.size.toString(),
            ),
        )
    },
)

private fun tempDir(): LocalPath {
    val dir = LocalPath(NSTemporaryDirectory())
        .resolve("keyguard-ios-download-task-tests", NSUUID().UUIDString)
    SystemFileSystem.createDirectories(dir.toKotlinxIoPath())
    return dir
}

private fun LocalPath.readBytes(): ByteArray =
    SystemFileSystem.source(toKotlinxIoPath())
        .buffered()
        .use { source ->
            source.readByteArray()
        }

private fun LocalPath.exists(): Boolean =
    SystemFileSystem.exists(toKotlinxIoPath())

private fun LocalPath.localPathWriter(): DownloadWriter.LocalPathWriter =
    DownloadWriter.LocalPathWriter(
        destination = AtomicFileDestination(
            root = LocalPath(value.substringBeforeLast('/')),
            relativePath = AtomicRelativePath.fromComponents(
                AtomicPathComponent.parse(value.substringAfterLast('/')),
            ),
        ),
    )
