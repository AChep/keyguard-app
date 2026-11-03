package com.artemchep.keyguard.util.agentipc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/** Both bindings, JNI and cinterop, reject the same arguments with the same codes. */
class AgentIpcArgumentTest {
    @Test
    fun nativeCodesMapToFailureKinds() {
        val expected = mapOf(
            -1L to AgentIpcFailureKind.INVALID_ARGUMENT,
            -2L to AgentIpcFailureKind.IO,
            -3L to AgentIpcFailureKind.CLOSED,
            -4L to AgentIpcFailureKind.PEER_CLOSED,
            // A handle consumed by close reads as closed.
            -5L to AgentIpcFailureKind.CLOSED,
            -7L to AgentIpcFailureKind.PERMISSION,
            -8L to AgentIpcFailureKind.INTERNAL,
            -9L to AgentIpcFailureKind.INTERNAL,
        )
        expected.forEach { (code, kind) ->
            assertEquals(kind, assertIs<AgentIpcException>(agentIpcFailure(code)).kind, "code $code")
        }
    }

    @Test
    fun busyIsACallerBug() {
        val error = assertIs<IllegalStateException>(agentIpcFailure(-6L))
        assertEquals("agent_ipc_busy", error.message)
    }

    @Test
    fun messagesAreFixedPerKind() {
        AgentIpcFailureKind.entries.forEach { kind ->
            val error = AgentIpcException(kind, diagnostic = "read: recv (os 104)")
            assertEquals("agent_ipc_" + kind.name.lowercase(), error.message)
        }
        assertEquals("agent_ipc_peer_closed", AgentIpcException(AgentIpcFailureKind.PEER_CLOSED).message)
    }

    @Test
    fun negativeHandlesAreInvalidArguments() {
        val buffer = ByteArray(4)
        assertEquals(-1L, NativeAgentIpc.accept(-1))
        assertEquals(-1L, NativeAgentIpc.closeListener(-1))
        assertEquals(-1L, NativeAgentIpc.peerPid(-1))
        assertEquals(-1L, NativeAgentIpc.awaitInput(-1))
        assertEquals(-1L, NativeAgentIpc.close(-1))
        assertEquals(-1L, NativeAgentIpc.read(-1, buffer, 0, 4))
        assertEquals(-1L, NativeAgentIpc.write(-1, buffer, 0, 4))
        assertEquals(-1L, NativeAgentIpc.accept(Long.MIN_VALUE))
        assertNull(NativeAgentIpc.endpoint(-1))
    }

    @Test
    fun unknownHandlesReadAsConsumed() {
        val buffer = ByteArray(4)
        assertEquals(-5L, NativeAgentIpc.accept(0))
        assertEquals("accept: invalid_handle", NativeAgentIpc.lastError())
        assertEquals(-5L, NativeAgentIpc.closeListener(0))
        assertEquals(-5L, NativeAgentIpc.peerPid(0))
        assertEquals(-5L, NativeAgentIpc.awaitInput(0))
        assertEquals(-5L, NativeAgentIpc.close(Long.MAX_VALUE))
        assertEquals(-5L, NativeAgentIpc.read(0, buffer, 0, 4))
        assertEquals(-5L, NativeAgentIpc.write(0, buffer, 0, 4))
        assertNull(NativeAgentIpc.endpoint(0))
        assertEquals("endpoint: invalid_handle", NativeAgentIpc.lastError())
    }

    @Test
    fun rangesAreCheckedBeforeTheHandle() {
        val buffer = ByteArray(4)
        val ranges = listOf(-1 to 1, 0 to -1, 3 to 2, 5 to 0, Int.MAX_VALUE to 1, 1 to Int.MAX_VALUE)
        ranges.forEach { (offset, length) ->
            assertEquals(-1L, NativeAgentIpc.read(0, buffer, offset, length), "read $offset+$length")
            assertEquals(-1L, NativeAgentIpc.write(0, buffer, offset, length), "write $offset+$length")
        }
    }

    @Test
    fun zeroLengthReadIsInvalidAndZeroLengthWriteOnlyNeedsALiveHandle() {
        val buffer = ByteArray(4)
        assertEquals(-1L, NativeAgentIpc.read(0, buffer, 4, 0))
        assertEquals("read: empty_buffer", NativeAgentIpc.lastError())
        assertEquals(-1L, NativeAgentIpc.read(0, ByteArray(0), 0, 0))
        assertEquals(-5L, NativeAgentIpc.write(0, buffer, 4, 0))
        assertEquals(-5L, NativeAgentIpc.write(0, ByteArray(0), 0, 0))
    }

    @Test
    fun localRejectionsNeverReturnAnEarlierDiagnostic() {
        assertEquals(-5L, NativeAgentIpc.accept(0))
        assertEquals("accept: invalid_handle", NativeAgentIpc.lastError())
        assertEquals(-1L, NativeAgentIpc.read(0, ByteArray(4), 3, 2))
        assertNotEquals("accept: invalid_handle", NativeAgentIpc.lastError())
        assertEquals(-5L, NativeAgentIpc.accept(0))
        assertEquals(-1L, NativeAgentIpc.listen(null, "\uD800", null))
        assertNotEquals("accept: invalid_handle", NativeAgentIpc.lastError())
    }

    @Test
    fun malformedUtf16IsRejected() {
        val parent = parentOf(agentIpcTestLocation())
        assertEquals(-1L, NativeAgentIpc.listen(parent, "\uD800", null))
        assertEquals(-1L, NativeAgentIpc.listen(parent, "kg-", "kg-\uDC00"))
        parent?.let { assertEquals(-1L, NativeAgentIpc.listen("$it\uD800", "kg-", null)) }
    }

    @Test
    fun namesAreValidatedBeforeAnythingIsCreated() {
        val parent = parentOf(agentIpcTestLocation())
        assertEquals(-1L, NativeAgentIpc.listen(parent, "kg/", null))
        assertEquals("listen: invalid_prefix", NativeAgentIpc.lastError())
        assertEquals(-1L, NativeAgentIpc.listen(parent, "kg-", ".."))
        assertEquals("listen: invalid_name", NativeAgentIpc.lastError())
        assertEquals(-1L, NativeAgentIpc.listen(parent, "k".repeat(33), null))
        assertEquals(-1L, NativeAgentIpc.listen(parent, "kg-", "k".repeat(65)))
    }

    @Test
    fun bindRejectsInvalidNamesAndTheOtherPlatform() {
        val invalid = when (val location = agentIpcTestLocation()) {
            is AgentIpcLocation.UnixDirectory -> AgentIpcLocation.UnixDirectory(location.parent, exactName = ".")
            is AgentIpcLocation.WindowsPipe -> AgentIpcLocation.WindowsPipe("kg-", exactName = ".")
        }
        val error = assertFailsWith<AgentIpcException> { AgentIpcListener.bind(invalid) }
        assertEquals(AgentIpcFailureKind.INVALID_ARGUMENT, error.kind)
        assertEquals("listen: invalid_name", error.diagnostic)

        val foreign = assertFailsWith<AgentIpcException> { AgentIpcListener.bind(agentIpcForeignLocation()) }
        assertEquals(AgentIpcFailureKind.INVALID_ARGUMENT, foreign.kind)
        assertEquals("listen: invalid_parent", foreign.diagnostic)
    }

    @Test
    fun aTakenExactNameIsPermission() {
        val name = agentIpcTestName()
        AgentIpcListener.bind(agentIpcTestLocation(name)).use {
            val error = assertFailsWith<AgentIpcException> {
                AgentIpcListener.bind(agentIpcTestLocation(name))
            }
            assertEquals(AgentIpcFailureKind.PERMISSION, error.kind)
            assertEquals("listen", error.diagnostic?.substringBefore(':'))
        }
    }

    private fun parentOf(location: AgentIpcLocation): String? =
        (location as? AgentIpcLocation.UnixDirectory)?.parent
}
