package com.artemchep.keyguard.integration.s3

import com.artemchep.keyguard.util.s3.KtorS3Client
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.okhttp.OkHttp

// S3 clients must not follow redirects.
internal val s3HttpEngines: List<Pair<String, () -> HttpClient>> = listOf(
    "cio" to { HttpClient(CIO) { followRedirects = false } },
    "okhttp" to { HttpClient(OkHttp) { followRedirects = false } },
)

@Suppress("TooGenericExceptionCaught")
internal suspend fun withS3Gateway(
    block: suspend (VersityGwServer) -> Unit,
) {
    val server = VersityGwServer.start()
    try {
        block(server)
    } catch (e: Throwable) {
        throw AssertionError("S3 E2E test failed.\n${server.diagnostics()}", e)
    } finally {
        server.close()
    }
}

internal suspend fun <T> KtorS3Client.useS3Client(
    block: suspend (KtorS3Client) -> T,
): T = try {
    block(this)
} finally {
    close()
}
