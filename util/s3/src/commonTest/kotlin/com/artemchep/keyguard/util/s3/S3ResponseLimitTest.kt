package com.artemchep.keyguard.util.s3

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

class S3ResponseLimitTest {
    @Test
    fun `oversized listing stops an unfinished response`() = runTest {
        withEndlessBody(HttpStatusCode.OK) { client ->
            assertFailsWith<S3Exception.Protocol> { client.listObjects() }
        }
    }

    @Test
    fun `oversized error preserves status classification and releases the response`() = runTest {
        withEndlessBody(HttpStatusCode.ServiceUnavailable) { client ->
            assertFailsWith<S3Exception.Transient> { client.getObject("key") }
        }
    }

    private suspend fun withEndlessBody(
        status: HttpStatusCode,
        block: suspend (KtorS3Client) -> Unit,
    ) = withContext(Dispatchers.Default) {
        supervisorScope {
            val channel = ByteChannel(autoFlush = true)
            val writer = launch {
                try {
                    val chunk = ByteArray(8192) { 'x'.code.toByte() }
                    repeat(5000) { channel.writeFully(chunk) }
                    awaitCancellation()
                } catch (_: IOException) {
                    // The bounded reader must cancel its upstream.
                } finally {
                    channel.close()
                }
            }
            try {
                withS3Client(handler = { respond(channel, status) }) { client ->
                    withTimeout(5.seconds) {
                        block(client)
                        writer.join()
                    }
                }
            } finally {
                writer.cancelAndJoin()
            }
        }
    }
}
