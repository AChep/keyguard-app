package com.artemchep.keyguard.ipctestclient

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artemchep.keyguard.ipctestclient.ipc.OpenPgpClient
import com.artemchep.keyguard.ipctestclient.ipc.OpenPgpOperation
import com.artemchep.keyguard.ipctestclient.ipc.OpenPgpOutputMode
import com.artemchep.keyguard.ipctestclient.ipc.OpenPgpRequestSpec
import com.artemchep.keyguard.ipctestclient.support.KeyguardProviderRule
import com.artemchep.keyguard.ipctestclient.support.SlowIpcTest
import com.artemchep.keyguard.ipctestclient.support.assertOpenPgpError
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openintents.openpgp.OpenPgpError

/**
 * Output pipe ownership and capacity.
 *
 * Pipes are keyed by (uid, pid, pipe id) and taken on first use, so a pipe id
 * is never a capability another caller - or a second request - can reuse. Like
 * the other rejection paths, none of this needs a registration or a key.
 */
@RunWith(AndroidJUnit4::class)
class OpenPgpPipeTest {
    @get:Rule
    val provider = KeyguardProviderRule()

    @Test
    fun anActionThatStreamsOutputRejectsPipeIdZero() {
        provider
            .openPgpRunner()
            .runOnce(
                OpenPgpRequestSpec(
                    operation = OpenPgpOperation.GET_KEY,
                    keyId = SOME_KEY_ID,
                    omitOutputPipe = true,
                ),
            )
            .assertOpenPgpError(
                errorId = OpenPgpError.GENERIC_ERROR,
                messageContains = "requires a valid output pipe",
            )
    }

    @Test
    fun anActionThatStreamsNothingRejectsAPipeItDoesNotOwn() {
        provider
            .openPgpRunner()
            .runOnce(
                OpenPgpRequestSpec(
                    operation = OpenPgpOperation.CHECK_PERMISSION,
                    outputPipeIdOverride = NEVER_CREATED_PIPE_ID,
                ),
            )
            .assertOpenPgpError(
                errorId = OpenPgpError.GENERIC_ERROR,
                messageContains = "not owned by this caller or has expired",
            )
    }

    /** The provider takes the pipe on first use, so a replay finds nothing. */
    @Test
    fun aPipeCannotBeUsedTwice() {
        val client = provider.openPgpClient()
        val pipeId = client.nextPipeId()
        requireNotNull(client.createOutputPipe(pipeId)).close()
        consume(client, pipeId)
        provider
            .openPgpRunner()
            .runOnce(
                OpenPgpRequestSpec(
                    operation = OpenPgpOperation.CHECK_PERMISSION,
                    outputPipeIdOverride = pipeId,
                ),
            )
            .assertOpenPgpError(
                errorId = OpenPgpError.GENERIC_ERROR,
                messageContains = "not owned by this caller or has expired",
            )
    }

    @Test
    fun createOutputPipeRejectsNonPositiveIds() {
        val client = provider.openPgpClient()
        assertNull(client.createOutputPipe(0))
        assertNull(client.createOutputPipe(-1))
    }

    @Test
    fun theProviderCapsOutstandingPipesPerCaller() {
        val client = provider.openPgpClient()
        val pipes = mutableMapOf<Int, ParcelFileDescriptor>()
        try {
            repeat(MAX_PIPES_PER_UID) {
                val pipeId = client.nextPipeId()
                pipes[pipeId] = requireNotNull(client.createOutputPipe(pipeId)) {
                    "The provider refused pipe ${it + 1} of $MAX_PIPES_PER_UID"
                }
            }
            assertNull(
                "The provider handed out more than $MAX_PIPES_PER_UID pipes",
                client.createOutputPipe(client.nextPipeId()),
            )
        } finally {
            pipes.forEach { (pipeId, descriptor) ->
                descriptor.close()
                consume(client, pipeId)
            }
        }
    }

    @Test
    fun anActionThatReadsAStreamRejectsAMissingOne() {
        provider
            .openPgpRunner()
            .runOnce(
                OpenPgpRequestSpec(
                    operation = OpenPgpOperation.DETACHED_SIGN,
                    omitInput = true,
                ),
            )
            .assertOpenPgpError(
                errorId = OpenPgpError.GENERIC_ERROR,
                messageContains = "requires a fresh input stream",
            )
    }

    @SlowIpcTest
    @Test
    fun aPipeExpiresWhenItIsNeverUsed() {
        val client = provider.openPgpClient()
        val pipeId = client.nextPipeId()
        requireNotNull(client.createOutputPipe(pipeId)).close()
        Thread.sleep(PIPE_LIFETIME_MS + PIPE_SWEEP_SLACK_MS)
        provider
            .openPgpRunner()
            .runOnce(
                OpenPgpRequestSpec(
                    operation = OpenPgpOperation.CHECK_PERMISSION,
                    outputPipeIdOverride = pipeId,
                ),
            )
            .assertOpenPgpError(
                errorId = OpenPgpError.GENERIC_ERROR,
                messageContains = "not owned by this caller or has expired",
            )
    }

    /** Sends anything that makes the provider take (and close) the pipe. */
    private fun consume(client: OpenPgpClient, pipeId: Int) {
        runCatching {
            client.execute(
                request = OpenPgpRequestSpec(OpenPgpOperation.CHECK_PERMISSION).toIntent(),
                input = null,
                outputMode = OpenPgpOutputMode.NONE,
                outputPipeIdOverride = pipeId,
            )
        }
    }

    private companion object {
        const val NEVER_CREATED_PIPE_ID = 987_654
        const val MAX_PIPES_PER_UID = 8
        const val PIPE_LIFETIME_MS = 60_000L
        const val PIPE_SWEEP_SLACK_MS = 3_000L
        const val SOME_KEY_ID = 0x0123_4567_89AB_CDEFL
    }
}
