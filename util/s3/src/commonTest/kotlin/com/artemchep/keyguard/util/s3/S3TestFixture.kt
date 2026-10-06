package com.artemchep.keyguard.util.s3

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import kotlin.time.Clock
import kotlin.time.Instant

internal val testS3Time = Instant.parse("2024-02-29T23:59:58Z")

internal fun testS3Config(
    endpoint: String = "https://example.com",
) = S3ClientConfig(
    endpoint = endpoint,
    bucket = "bucket",
    credentials = S3Credentials("test-access", "test-secret"),
)

internal suspend fun <T> withS3Client(
    config: S3ClientConfig = testS3Config(),
    configure: HttpClientConfig<*>.() -> Unit = {},
    handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    block: suspend (KtorS3Client) -> T,
): T {
    val http = HttpClient(MockEngine(handler)) {
        followRedirects = false
        configure()
    }
    val client = KtorS3Client(http, config, clock = object : Clock {
        override fun now(): Instant = testS3Time
    })
    return try {
        block(client)
    } finally {
        client.close()
        http.close()
    }
}
