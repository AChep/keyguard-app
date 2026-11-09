package com.artemchep.keyguard.provider.keyguard.session

import com.artemchep.keyguard.common.io.runCatchingUnlessCancelled
import com.artemchep.keyguard.common.util.ReconnectBackoff
import com.artemchep.keyguard.feature.p2psync.mailbox.MailboxPolicy
import com.artemchep.keyguard.provider.keyguard.transport.RelayMailboxResult
import kotlinx.coroutines.delay

/**
 * The retries of one worker of relay storage, see [KeyguardMailbox]: a failed request backs
 * the worker off, and a refusal that names a time pauses every worker of the group.
 */
internal class KeyguardMailboxRetry(
    private val mailbox: KeyguardMailbox,
) {
    /** No request goes out before this time, by the clock of the mailbox, after a failure. */
    private var retryAt = 0L

    private val backoff = ReconnectBackoff(maxDelayMs = mailbox.timings.maxBackoffMs)

    /**
     * Runs [step] until cancelled. Before each step it waits out the back-off of this worker,
     * then the pause of the group, which another worker may have set meanwhile. A step that
     * throws counts as a failure. [onFirstStep] runs once the first step is over;
     * [awaitWork] runs after each step that did not fail, see [failed].
     */
    suspend fun loop(
        step: suspend () -> Boolean,
        onFirstStep: () -> Unit,
        awaitWork: suspend () -> Unit,
    ): Nothing {
        var first = true
        while (true) {
            delay((retryAt - mailbox.clock()).coerceAtLeast(0L))
            mailbox.awaitUnpaused()
            val done = runCatchingUnlessCancelled { step() }
                .getOrElse {
                    backOff()
                    false
                }
            if (first) {
                first = false
                onFirstStep()
            }
            // A failed request is tried again at its time.
            if (done) {
                backoff.reset()
                awaitWork()
            }
        }
    }

    /**
     * Handles a [result] that is no success: a refusal that names a time pauses every
     * worker, anything else backs this one off. Returns `false`.
     */
    fun failed(result: RelayMailboxResult): Boolean {
        if (result is RelayMailboxResult.RetryLater) {
            mailbox.pause(MailboxPolicy.retryAfterMs(result.retryAfterSeconds))
        } else {
            backOff()
        }
        return false
    }

    private fun backOff() {
        retryAt = mailbox.clock() + backoff.nextDelayMs()
    }
}
