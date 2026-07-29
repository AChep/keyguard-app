package com.artemchep.keyguard.android.credentialexchange

import com.artemchep.keyguard.common.service.logging.LogLevel
import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [CredentialExportActivity] is exported, so the request it parses is reachable by
 * any installed app, and the parse runs before the credential id — the check that
 * actually gates the activity — has been read.
 *
 * A parse failure therefore has to stay a local log line. Reporting it as a
 * crash-report non-fatal would hand any installed app one non-fatal per launch, for
 * free, by starting the activity with a request that cannot parse.
 */
class ParseUntrustedRequestTest {
    companion object {
        private const val TAG = "CredentialExportActivity"
    }

    @Test
    fun `a request that fails to parse degrades to null and a local warning`() {
        val logRepository = RecordingLogRepository()

        val result = parseUntrustedRequest<String>(logRepository, TAG) {
            // What androidx raises for an ImportCredentialsRequest carrying no
            // credential types: an `init` requirement its own parser, which only
            // guards against JSONException, lets out.
            throw IllegalArgumentException("Failed requirement.")
        }

        assertNull(result)
        val entry = logRepository.entries.single()
        assertEquals(TAG, entry.tag)
        assertEquals(LogLevel.WARNING, entry.level)
        assertTrue(
            entry.message.contains("IllegalArgumentException"),
            "The failure type has to survive into the log: ${entry.message}",
        )
    }

    @Test
    fun `the crafted payload is not echoed into the log`() {
        val logRepository = RecordingLogRepository()

        parseUntrustedRequest<String>(logRepository, TAG) {
            throw IllegalArgumentException("""{"credentialTypes":["marker"]}""")
        }

        assertFalse(
            logRepository.entries.single().message.contains("marker"),
            "A parse failure message can quote the crafted payload back.",
        )
    }

    @Test
    fun `a parsed request is returned as is and logs nothing`() {
        val logRepository = RecordingLogRepository()

        val result = parseUntrustedRequest(logRepository, TAG) { "request" }

        assertEquals("request", result)
        assertTrue(logRepository.entries.isEmpty())
    }

    @Test
    fun `an out of memory error is not folded into nothing to answer`() {
        val logRepository = RecordingLogRepository()

        assertFailsWith<OutOfMemoryError> {
            parseUntrustedRequest<String>(logRepository, TAG) {
                throw OutOfMemoryError("oversized payload")
            }
        }

        assertTrue(logRepository.entries.isEmpty())
    }

    @Test
    fun `cancellation is rethrown rather than logged`() {
        val logRepository = RecordingLogRepository()

        assertFailsWith<CancellationException> {
            parseUntrustedRequest<String>(logRepository, TAG) {
                throw CancellationException("activity finished")
            }
        }

        assertTrue(logRepository.entries.isEmpty())
    }
}
