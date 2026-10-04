package com.artemchep.keyguard.util.s3

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class S3ErrorRegressionTest {
    @Test
    fun `delete preserves authentication errors carried by a 404`() = runTest {
        withS3Client(handler = {
            respond("<Error><Code>XAdminUserNotFound</Code></Error>", HttpStatusCode.NotFound)
        }) { client ->
            val error = assertFailsWith<S3Exception.AuthenticationFailed> { client.deleteObject("key") }
            assertEquals("XAdminUserNotFound", error.errorCode)
            assertEquals(S3Operation.Delete, error.operation)
        }
    }

    @Test
    fun `malformed error body encoding does not hide the HTTP failure`() = runTest {
        withS3Client(handler = {
            respond(byteArrayOf(0xff.toByte()), HttpStatusCode.ServiceUnavailable)
        }) { client ->
            assertFailsWith<S3Exception.Transient> { client.getObject("key") }
            assertFailsWith<S3Exception.Transient> { client.listObjects() }
            assertFailsWith<S3Exception.Transient> { client.deleteObject("key") }
        }
    }
}
