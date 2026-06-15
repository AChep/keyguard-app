package com.artemchep.keyguard.feature.sshagent

import com.artemchep.keyguard.common.service.sshagent.SshAgentApprovalRequest
import com.artemchep.keyguard.common.service.sshagent.SshAgentRequest
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

class SshAgentRequestUiStateTest {
    @Test
    fun `removeFirstPendingRequestOrNull skips completed requests`() {
        val completed = createApprovalRequest(keyName = "completed")
        completed.deferred.complete(false)
        val pending = createApprovalRequest(keyName = "pending")
        val queue = ArrayDeque<SshAgentRequest>()
        queue.addLast(completed)
        queue.addLast(pending)

        assertSame(pending, queue.removeFirstUnresolvedRequestOrNull())
        assertTrue(queue.isEmpty())
    }

    @Test
    fun `removeFirstPendingRequestOrNull returns null when all requests are completed`() {
        val completed = createApprovalRequest(keyName = "completed")
        completed.deferred.complete(false)
        val queue = ArrayDeque<SshAgentRequest>()
        queue.addLast(completed)

        assertNull(queue.removeFirstUnresolvedRequestOrNull())
        assertTrue(queue.isEmpty())
    }

    private fun createApprovalRequest(
        keyName: String,
    ) = SshAgentApprovalRequest(
        keyName = keyName,
        keyFingerprint = "SHA256:test",
        caller = null,
        expiresAt = Clock.System.now() + 60.seconds,
        deferred = CompletableDeferred(),
    )
}
