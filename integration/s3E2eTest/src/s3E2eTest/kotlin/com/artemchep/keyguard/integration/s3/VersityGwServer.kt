package com.artemchep.keyguard.integration.s3

import com.artemchep.keyguard.integration.process.CapturedProcess
import com.artemchep.keyguard.integration.process.freePort
import com.artemchep.keyguard.util.s3.KtorS3Client
import com.artemchep.keyguard.util.s3.S3ClientConfig
import com.artemchep.keyguard.util.s3.S3Credentials
import com.artemchep.keyguard.util.s3.S3SigV4Signer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.time.Clock

/**
 * A versitygw S3 gateway with a POSIX backend, started from PATH. Any
 * S3-compatible server works for these tests; versitygw is used because it
 * ships pinned single binaries and enforces conditional writes.
 */
internal class VersityGwServer private constructor(
    val port: Int,
    private val process: CapturedProcess,
    private val tempDir: Path,
) : AutoCloseable {
    val endpoint: String = "http://127.0.0.1:$port"

    fun client(
        bucket: String = BUCKET,
        accessKeyId: String = ACCESS_KEY_ID,
        secretAccessKey: String = SECRET_ACCESS_KEY,
        httpClient: HttpClient = HttpClient(CIO) { followRedirects = false },
        endpoint: String = this.endpoint,
        pathStyle: Boolean = true,
        clock: Clock = Clock.System,
    ): KtorS3Client = KtorS3Client(
        httpClient = httpClient,
        config = S3ClientConfig(
            endpoint = endpoint,
            region = REGION,
            bucket = bucket,
            credentials = S3Credentials(accessKeyId, secretAccessKey),
            pathStyle = pathStyle,
            userAgent = "KeyguardS3E2eTest",
        ),
        closeHttpClient = true,
        clock = clock,
    )

    /** Creates a bucket with a signed `PUT /{bucket}`. */
    fun createBucket(
        name: String,
    ) {
        val path = "/$name"
        val signer = S3SigV4Signer(
            credentials = S3Credentials(ACCESS_KEY_ID, SECRET_ACCESS_KEY),
            region = REGION,
        )
        val host = "127.0.0.1:$port"
        val headers = signer.sign(
            method = "PUT",
            canonicalUri = path,
            canonicalQuery = "",
            headers = mapOf("host" to host),
            payloadSha256Hex = S3SigV4Signer.EMPTY_PAYLOAD_SHA256,
            now = Clock.System.now(),
        )
        val connection = (URI("$endpoint$path").toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            doOutput = true
            setFixedLengthStreamingMode(0)
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }
        try {
            connection.outputStream.close()
            val status = connection.responseCode
            check(status in 200..299) {
                val body = runCatching { connection.errorStream?.readAllBytes()?.decodeToString() }.getOrNull()
                "Could not create bucket '$name': HTTP $status $body"
            }
        } finally {
            connection.disconnect()
        }
    }

    fun diagnostics(): String = buildString {
        appendLine("versitygw endpoint: $endpoint")
        append(process.diagnostics("versitygw"))
    }

    @OptIn(ExperimentalPathApi::class)
    override fun close() {
        process.close()
        tempDir.deleteRecursively()
    }

    companion object {
        const val BUCKET = "keyguard"
        const val REGION = "us-east-1"
        const val ACCESS_KEY_ID = "keyguard-e2e"
        const val SECRET_ACCESS_KEY = "keyguard-e2e-secret/+key"

        /** The base domain for virtual-hosted addressing; tests resolve it to loopback. */
        const val VIRTUAL_DOMAIN = "s3.keyguard.test"

        @Suppress("TooGenericExceptionCaught")
        fun start(): VersityGwServer {
            val tempDir = Files.createTempDirectory("keyguard-s3-e2e-")
            val root = Files.createDirectories(tempDir.resolve("data"))
            val port = freePort()
            val process = ProcessBuilder(
                "versitygw",
                "--port", "127.0.0.1:$port",
                "--access", ACCESS_KEY_ID,
                "--secret", SECRET_ACCESS_KEY,
                "--region", REGION,
                "--virtual-domain", VIRTUAL_DOMAIN,
                "posix",
                root.toString(),
            )
                .directory(tempDir.toFile())
                .start()
            val server = VersityGwServer(
                port = port,
                process = CapturedProcess(process, "versitygw-e2e"),
                tempDir = tempDir,
            )
            try {
                server.waitUntilReady()
                server.createBucket(BUCKET)
            } catch (e: Throwable) {
                val diagnostics = server.diagnostics()
                server.close()
                throw AssertionError("versitygw server did not become ready.\n$diagnostics", e)
            }
            return server
        }

        fun unusedEndpoint(): String = "http://127.0.0.1:${freePort()}"
    }

    @Suppress("TooGenericExceptionCaught")
    private fun waitUntilReady() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        var lastFailure: Throwable? = null
        while (System.nanoTime() < deadline) {
            if (!process.isAlive) {
                error("versitygw process exited while starting.")
            }
            try {
                // Any HTTP answer, even an authentication error, means the
                // gateway is accepting requests.
                val connection = (URI("$endpoint/").toURL().openConnection() as HttpURLConnection).apply {
                    connectTimeout = 250
                    readTimeout = 250
                }
                val status = connection.responseCode
                connection.disconnect()
                if (status in 200..599) {
                    return
                }
            } catch (e: Throwable) {
                lastFailure = e
            }
            Thread.sleep(50)
        }
        throw AssertionError("Timed out waiting for versitygw readiness.", lastFailure)
    }
}
