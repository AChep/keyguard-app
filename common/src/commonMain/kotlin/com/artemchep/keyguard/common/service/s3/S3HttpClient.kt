package com.artemchep.keyguard.common.service.s3

import io.ktor.client.HttpClient

/**
 * Creates an HTTP client for S3 requests, on the engine of [appHttpClient]
 * where the engine does not cache responses, without its plugins.
 */
internal expect fun createS3HttpClient(
    appHttpClient: HttpClient,
): HttpClient
