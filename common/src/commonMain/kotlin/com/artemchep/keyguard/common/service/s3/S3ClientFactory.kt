package com.artemchep.keyguard.common.service.s3

import com.artemchep.keyguard.util.s3.KtorS3Client
import com.artemchep.keyguard.util.s3.S3Client
import com.artemchep.keyguard.util.s3.S3ClientConfig
import io.ktor.client.HttpClient

fun interface S3ClientFactory {
    suspend fun create(
        config: S3ClientConfig,
    ): S3Client
}

class KtorS3ClientFactory(
    httpClient: HttpClient,
) : S3ClientFactory {
    // All S3 clients share one HTTP client without the plugins of the app's
    // client: they cache responses, follow redirects, retry requests and
    // send the Bitwarden user agent.
    private val s3HttpClient by lazy {
        createS3HttpClient(httpClient)
    }

    override suspend fun create(
        config: S3ClientConfig,
    ): S3Client = KtorS3Client(
        httpClient = s3HttpClient,
        config = config,
    )
}
