package com.artemchep.keyguard.common.service.download

import arrow.core.Either
import com.artemchep.keyguard.common.exception.HttpException
import com.artemchep.keyguard.common.service.crypto.FileEncryptionCodec
import com.artemchep.keyguard.crypto.CryptoGeneratorJvm
import com.artemchep.keyguard.crypto.FileEncryptionCodecJvm
import com.artemchep.keyguard.util.io.atomic.AtomicFileDestination
import com.artemchep.keyguard.util.io.atomic.AtomicPathComponent
import com.artemchep.keyguard.util.io.atomic.AtomicRelativePath
import com.artemchep.keyguard.util.io.toLocalPath
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import okhttp3.Call
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import okio.buffer
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import okio.Buffer as OkioBuffer

private const val TEST_BYTE_PATTERN_MODULUS = 251
private const val FILE_ENCRYPTION_KEY_SIZE_BYTES = 64
private const val MULTI_BYTE_KEY_LAST_BYTE: Byte = 3
private const val CALL_CANCELLATION_TIMEOUT_SECONDS = 5L

@Suppress("FunctionNaming")
class DownloadTaskJvmSuccessfulOutputTest {
    @Test
    fun `url loader writes local path download`() = runTest {
        val root = createTempDirectory("download-task")
        val output = root.resolve("payload.bin")
        val data = "payload".encodeToByteArray()

        try {
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
            assertEquals(output.toFile().toURI().toString(), result.value)
            assertContentEquals(data, output.readBytes())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `url loader writes local path download with short filename`() = runTest {
        val root = createTempDirectory("download-task")
        val output = root.resolve("a")
        val data = "payload".encodeToByteArray()

        try {
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
            assertEquals(output.toFile().toURI().toString(), result.value)
            assertContentEquals(data, output.readBytes())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `url loader writes unencrypted download to sink`() = runTest {
        val data = "payload".encodeToByteArray()
        val sink = Buffer()
        val task = downloadTask(responseBody = data)

        val complete = task
            .fileLoader(
                url = "https://example.com/payload.bin",
                key = null,
                writer = DownloadWriter.SinkWriter(sink),
            )
            .filterIsInstance<DownloadProgress.Complete>()
            .single()

        val result = assertIs<Either.Right<String?>>(complete.result)
        assertEquals(null, result.value)
        assertContentEquals(data, sink.readByteArray())
    }

    @Test
    fun `url loader flushes but does not close caller sink`() = runTest {
        val data = "payload".encodeToByteArray()
        val trackingSink = TrackingRawSink()
        val task = downloadTask(responseBody = data)

        val complete = task
            .fileLoader(
                url = "https://example.com/payload.bin",
                key = null,
                writer = DownloadWriter.SinkWriter(trackingSink.buffered()),
            )
            .filterIsInstance<DownloadProgress.Complete>()
            .single()

        assertIs<Either.Right<String?>>(complete.result)
        assertContentEquals(data, trackingSink.data.readByteArray())
        assertTrue(trackingSink.flushCount > 0)
        assertEquals(0, trackingSink.closeCount)
    }

    @Test
    fun `url loader spills large plaintext before publishing to sink`() = runTest {
        val data = ByteArray(
            DOWNLOAD_PLAINTEXT_MEMORY_LIMIT_BYTES.toInt() + 1,
        ) { index ->
            (index % TEST_BYTE_PATTERN_MODULUS).toByte()
        }
        val sink = Buffer()
        val task = downloadTask(responseBody = data)

        val complete = task
            .fileLoader(
                url = "https://example.com/payload.bin",
                key = null,
                writer = DownloadWriter.SinkWriter(sink),
            )
            .filterIsInstance<DownloadProgress.Complete>()
            .single()

        assertIs<Either.Right<String?>>(complete.result)
        assertContentEquals(data, sink.readByteArray())
    }

    @Test
    fun `url loader decrypts download to sink with production encryptor`() = runTest {
        val plain = "plain payload".encodeToByteArray()
        val key = ByteArray(FILE_ENCRYPTION_KEY_SIZE_BYTES) { index -> index.toByte() }
        val fileEncryptionCodec = FileEncryptionCodecJvm(CryptoGeneratorJvm())
        val encrypted = fileEncryptionCodec.encrypt(plain, key)
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

        assertIs<Either.Right<String?>>(complete.result)
        assertContentEquals(plain, sink.readByteArray())
    }
}

@Suppress("FunctionNaming")
class DownloadTaskJvmFailureAndEdgeCaseTest {
    @Test
    fun `url loader leaves sink untouched after failed decrypt`() = runTest {
        val original = "original".encodeToByteArray()
        val sink = Buffer().apply {
            write(original)
        }
        val task = downloadTask(
            responseBody = "encrypted".encodeToByteArray(),
            fileEncryptionCodec = FailingFileEncryptionCodec(),
        )

        val complete = task
            .fileLoader(
                url = "https://example.com/payload.bin",
                key = byteArrayOf(1),
                writer = DownloadWriter.SinkWriter(sink),
            )
            .filterIsInstance<DownloadProgress.Complete>()
            .single()

        assertIs<Either.Left<Throwable>>(complete.result)
        assertContentEquals(original, sink.readByteArray())
    }

    @Test
    fun `url loader creates empty local path file for empty response`() = runTest {
        val root = createTempDirectory("download-task")
        val output = root.resolve("payload.bin")

        try {
            output.toFile().writeText("original")
            val task = downloadTask(responseBody = ByteArray(0))

            val complete = task
                .fileLoader(
                    url = "https://example.com/payload.bin",
                    key = null,
                    writer = output.localPathWriter(),
                )
                .filterIsInstance<DownloadProgress.Complete>()
                .single()

            assertIs<Either.Right<String?>>(complete.result)
            assertContentEquals(ByteArray(0), output.readBytes())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `byte array loader decrypts and writes local path download`() = runTest {
        val root = createTempDirectory("download-task")
        val output = root.resolve("nested").resolve("payload.bin")
        val encrypted = "encrypted".encodeToByteArray()
        val plain = "plain payload".encodeToByteArray()

        try {
            val task = downloadTask(
                responseBody = ByteArray(0),
                fileEncryptionCodec = ByteArrayDecryptingFileEncryptionCodec(plain),
            )

            val complete = task
                .fileLoader(
                    data = encrypted,
                    key = byteArrayOf(1, 2, MULTI_BYTE_KEY_LAST_BYTE),
                    writer = output.localPathWriter(existingRoot = root),
                )
                .filterIsInstance<DownloadProgress.Complete>()
                .single()

            val result = assertIs<Either.Right<String?>>(complete.result)
            assertEquals(output.toFile().toURI().toString(), result.value)
            assertContentEquals(plain, output.readBytes())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `url loader preserves local path file after failed decrypt`() = runTest {
        val root = createTempDirectory("download-task")
        val output = root.resolve("payload.bin")
        val data = "encrypted".encodeToByteArray()
        val original = "original".encodeToByteArray()

        try {
            output.toFile().writeBytes(original)
            val task = downloadTask(
                responseBody = data,
                fileEncryptionCodec = FailingFileEncryptionCodec(),
            )

            val complete = task
                .fileLoader(
                    url = "https://example.com/payload.bin",
                    key = byteArrayOf(1),
                    writer = output.localPathWriter(),
                )
                .filterIsInstance<DownloadProgress.Complete>()
                .single()

            assertIs<Either.Left<Throwable>>(complete.result)
            assertContentEquals(original, output.readBytes())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `url loader preserves http status failure`() = runTest {
        val root = createTempDirectory("download-task")
        val output = root.resolve("payload.bin")
        val original = "original".encodeToByteArray()

        try {
            output.toFile().writeBytes(original)
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
            assertContentEquals(original, output.readBytes())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `url loader leaves sink untouched after response fails mid body`() = runTest {
        val original = "original".encodeToByteArray()
        val sink = Buffer().apply {
            write(original)
        }
        val task = downloadTask(
            responseBody = FailingResponseBody(
                partial = "partial".encodeToByteArray(),
            ),
        )

        val complete = task
            .fileLoader(
                url = "https://example.com/payload.bin",
                key = null,
                writer = DownloadWriter.SinkWriter(sink),
            )
            .filterIsInstance<DownloadProgress.Complete>()
            .single()

        assertIs<Either.Left<Throwable>>(complete.result)
        assertContentEquals(original, sink.readByteArray())
    }

    @Test
    fun `cancelling url loader cancels the call without publishing staged bytes`() = runBlocking {
        val original = "original".encodeToByteArray()
        val sink = Buffer().apply {
            write(original)
        }
        val responseBody = CancellableResponseBody()
        val task = downloadTask(responseBody = responseBody)
        val completed = AtomicBoolean(false)

        val collection = launch(
            context = Dispatchers.Default,
            start = CoroutineStart.UNDISPATCHED,
        ) {
            task
                .fileLoader(
                    url = "https://example.com/payload.bin",
                    key = null,
                    writer = DownloadWriter.SinkWriter(sink),
                )
                .collect { progress ->
                    if (progress is DownloadProgress.Complete) {
                        completed.set(true)
                    }
                }
        }

        assertTrue(
            responseBody.readStarted.await(
                CALL_CANCELLATION_TIMEOUT_SECONDS,
                TimeUnit.SECONDS,
            ),
        )
        collection.cancelAndJoin()

        assertTrue(responseBody.call.isCanceled())
        assertTrue(responseBody.closed.get())
        assertFalse(completed.get())
        assertContentEquals(original, sink.readByteArray())
    }
}

private fun downloadTask(
    responseBody: ByteArray,
    responseStatus: HttpStatusCode = HttpStatusCode.OK,
    fileEncryptionCodec: FileEncryptionCodec = CopyingFileEncryptionCodec(),
) = downloadTask(
    responseBody = responseBody.toResponseBody(),
    responseStatus = responseStatus,
    fileEncryptionCodec = fileEncryptionCodec,
)

private fun downloadTask(
    responseBody: ResponseBody,
    responseStatus: HttpStatusCode = HttpStatusCode.OK,
    fileEncryptionCodec: FileEncryptionCodec = CopyingFileEncryptionCodec(),
) = DownloadTaskImpl(
    httpClient = HttpClient(OkHttp) {
        engine {
            preconfigured = okHttpClient(
                responseBody = responseBody,
                responseStatus = responseStatus,
            )
        }
    },
    fileEncryptionCodec = fileEncryptionCodec,
)

private fun okHttpClient(
    responseBody: ResponseBody,
    responseStatus: HttpStatusCode,
) = OkHttpClient.Builder()
    .addInterceptor { chain ->
        if (responseBody is CancellableResponseBody) {
            responseBody.call = chain.call()
        }
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(responseStatus.value)
            .message(responseStatus.description)
            .body(responseBody)
            .build()
    }
    .build()

private class FailingResponseBody(
    partial: ByteArray,
) : ResponseBody() {
    private val responseSource = object : okio.Source {
        private val partialBuffer = OkioBuffer().apply {
            write(partial)
        }
        private var failed = false

        override fun read(
            sink: OkioBuffer,
            byteCount: Long,
        ): Long {
            if (failed) {
                throw IOException("Response body failed")
            }
            val read = partialBuffer.read(sink, byteCount)
            if (read < 0L) {
                failed = true
                throw IOException("Response body failed")
            }
            return read
        }

        override fun timeout(): Timeout = Timeout.NONE

        override fun close() = Unit
    }.buffer()

    override fun contentType(): MediaType? = null

    override fun contentLength(): Long = -1L

    override fun source() = responseSource
}

private class CancellableResponseBody : ResponseBody() {
    lateinit var call: Call
    val readStarted = CountDownLatch(1)
    val closed = AtomicBoolean(false)

    private val responseSource = object : okio.Source {
        override fun read(
            sink: OkioBuffer,
            byteCount: Long,
        ): Long {
            readStarted.countDown()
            while (!call.isCanceled()) {
                Thread.yield()
            }
            throw IOException("Canceled")
        }

        override fun timeout(): Timeout = Timeout.NONE

        override fun close() {
            closed.set(true)
        }
    }.buffer()

    override fun contentType(): MediaType? = null

    override fun contentLength(): Long = -1L

    override fun source() = responseSource
}

private class TrackingRawSink : RawSink {
    val data = Buffer()
    var flushCount = 0
    var closeCount = 0

    override fun write(
        source: Buffer,
        byteCount: Long,
    ) {
        data.write(source, byteCount)
    }

    override fun flush() {
        flushCount += 1
    }

    override fun close() {
        closeCount += 1
    }
}

private class CopyingFileEncryptionCodec : FileEncryptionCodec {
    override fun decrypt(
        input: ByteArray,
        key: ByteArray,
    ): ByteArray = input

    override fun decrypt(
        input: Source,
        output: Sink,
        key: ByteArray,
    ) {
        output.write(input.readByteArray())
    }

    override fun encrypt(
        data: ByteArray,
        key: ByteArray,
    ): ByteArray = data

    override fun encrypt(
        input: Source,
        output: Sink,
        key: ByteArray,
    ): FileEncryptionCodec.EncryptionResult {
        val data = input.readByteArray()
        output.write(data)
        return FileEncryptionCodec.EncryptionResult(
            plainSize = data.size.toLong(),
            encryptedSize = data.size.toLong(),
        )
    }
}

private class ByteArrayDecryptingFileEncryptionCodec(
    private val plain: ByteArray,
) : FileEncryptionCodec {
    override fun decrypt(
        input: ByteArray,
        key: ByteArray,
    ): ByteArray = plain

    override fun decrypt(
        input: Source,
        output: Sink,
        key: ByteArray,
    ) {
        output.write(plain)
    }

    override fun encrypt(
        data: ByteArray,
        key: ByteArray,
    ): ByteArray = data

    override fun encrypt(
        input: Source,
        output: Sink,
        key: ByteArray,
    ): FileEncryptionCodec.EncryptionResult {
        val data = input.readByteArray()
        output.write(data)
        return FileEncryptionCodec.EncryptionResult(
            plainSize = data.size.toLong(),
            encryptedSize = data.size.toLong(),
        )
    }
}

private class FailingFileEncryptionCodec : FileEncryptionCodec {
    override fun decrypt(
        input: ByteArray,
        key: ByteArray,
    ): ByteArray = throw IOException("Message authentication codes do not match!")

    override fun decrypt(
        input: Source,
        output: Sink,
        key: ByteArray,
    ) {
        output.write("partial".encodeToByteArray())
        throw IOException("Message authentication codes do not match!")
    }

    override fun encrypt(
        data: ByteArray,
        key: ByteArray,
    ): ByteArray = data

    override fun encrypt(
        input: Source,
        output: Sink,
        key: ByteArray,
    ): FileEncryptionCodec.EncryptionResult {
        val data = input.readByteArray()
        output.write(data)
        return FileEncryptionCodec.EncryptionResult(
            plainSize = data.size.toLong(),
            encryptedSize = data.size.toLong(),
        )
    }
}

private fun java.nio.file.Path.localPathWriter(
    existingRoot: java.nio.file.Path = requireNotNull(parent),
): DownloadWriter.LocalPathWriter {
    val relativeComponents = existingRoot
        .normalize()
        .relativize(normalize())
        .map { component ->
            AtomicPathComponent.parse(component.toString())
        }
    return DownloadWriter.LocalPathWriter(
        destination = AtomicFileDestination(
            root = existingRoot.toLocalPath(),
            relativePath = AtomicRelativePath.fromComponents(
                first = relativeComponents.first(),
                *relativeComponents.drop(1).toTypedArray(),
            ),
        ),
    )
}
