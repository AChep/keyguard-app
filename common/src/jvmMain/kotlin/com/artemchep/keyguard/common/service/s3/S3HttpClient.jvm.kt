package com.artemchep.keyguard.common.service.s3

import io.ktor.client.HttpClient

internal actual fun createS3HttpClient(
    appHttpClient: HttpClient,
): HttpClient = HttpClient(appHttpClient.engine) {
    followRedirects = false
}
