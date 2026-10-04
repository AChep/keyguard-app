package com.artemchep.keyguard.common.service.s3

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin

// The session of the app's engine stores responses in the shared URLCache on
// disk, even though requests ignore cached data. S3 responses carry vault
// data, so they get a session without a cache.
@Suppress("UnusedParameter")
internal actual fun createS3HttpClient(
    appHttpClient: HttpClient,
): HttpClient = HttpClient(Darwin) {
    followRedirects = false
    engine {
        configureSession {
            setURLCache(null)
        }
    }
}
