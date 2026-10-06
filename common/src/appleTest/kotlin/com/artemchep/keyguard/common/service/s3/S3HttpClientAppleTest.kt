package com.artemchep.keyguard.common.service.s3

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.engine.darwin.DarwinClientEngineConfig
import platform.Foundation.NSURLSessionConfiguration
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class S3HttpClientAppleTest {
    @Test
    fun `S3 responses are not stored in a URL cache`() {
        val appHttpClient = HttpClient(Darwin)
        val s3HttpClient = createS3HttpClient(appHttpClient)
        try {
            val configuration = NSURLSessionConfiguration.defaultSessionConfiguration()
            assertNotNull(configuration.URLCache)

            @Suppress("DEPRECATION")
            (s3HttpClient.engine.config as DarwinClientEngineConfig).sessionConfig(configuration)

            assertNull(configuration.URLCache)
        } finally {
            s3HttpClient.close()
            appHttpClient.close()
        }
    }
}
