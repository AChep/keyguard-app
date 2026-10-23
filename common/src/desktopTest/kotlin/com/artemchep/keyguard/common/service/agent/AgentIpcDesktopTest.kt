package com.artemchep.keyguard.common.service.agent

import com.artemchep.keyguard.common.exception.Readable
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.error_agent_ipc_endpoint
import com.artemchep.keyguard.res.error_agent_ipc_unavailable
import com.artemchep.keyguard.util.agentipc.AgentIpcEvent
import com.artemchep.keyguard.util.agentipc.AgentIpcException
import com.artemchep.keyguard.util.agentipc.AgentIpcFailureKind
import com.artemchep.keyguard.util.agentipc.AgentIpcListener
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AgentIpcDesktopTest {
    @Test
    fun `endpoints are named after the agent and stay private`() {
        val endpoint = AgentIpcListener.bind(agentIpcLocation("keyguard-gpg-ipc")).use { listener ->
            val endpoint = listener.endpoint
            if (isWindowsHost) {
                assertTrue(endpoint.startsWith("\\\\.\\pipe\\keyguard-gpg-ipc-"), endpoint)
            } else {
                val directory = Path.of(endpoint).parent
                // Short, for the socket path limits of Linux and macOS.
                assertTrue(endpoint.length < 90, endpoint)
                assertTrue(directory.startsWith(Path.of("/tmp")), endpoint)
                assertTrue(directory.fileName.toString().startsWith("keyguard-gpg-ipc-"), endpoint)
                assertEquals(
                    PosixFilePermissions.fromString("rwx------"),
                    Files.getPosixFilePermissions(directory),
                )
            }
            endpoint
        }
        if (!isWindowsHost) {
            assertFalse(Files.exists(Path.of(endpoint).parent), "Closing removes the endpoint")
        }
    }

    @Test
    fun `a missing library is a localized error`() {
        val cause = AgentIpcException(AgentIpcFailureKind.UNAVAILABLE)
        val error = cause.asAgentIpcAppException()
        assertEquals(TextHolder.Res(Res.string.error_agent_ipc_unavailable), assertIs<Readable>(error).title)
        assertSame(cause, error.cause)
    }

    @Test
    fun `any other failure of an endpoint is a localized error`() {
        for (kind in AgentIpcFailureKind.entries - AgentIpcFailureKind.UNAVAILABLE) {
            val error = AgentIpcException(kind, diagnostic = "listen: taken").asAgentIpcAppException()
            assertEquals(TextHolder.Res(Res.string.error_agent_ipc_endpoint), assertIs<Readable>(error).title, "$kind")
        }
    }

    @Test
    fun `other errors are kept`() {
        val error = IllegalStateException("not an IPC error")
        assertSame(error, error.asAgentIpcAppException())
    }

    @Test
    fun `events are logged as their fixed text at their severity`() {
        val log = AgentIpcTestLog()
        log.post("tag", AgentIpcEvent.Listening)
        log.post("tag", AgentIpcEvent.ClientDisconnected)
        log.post("tag", AgentIpcEvent.Rejected(AgentIpcEvent.Reason.OVER_LIMIT, limit = 8))
        log.post("tag", AgentIpcEvent.ConnectionFailed(AgentIpcFailureKind.IO, diagnostic = "read: failed (os 5)"))
        log.post("tag", AgentIpcEvent.SessionFailed(InvalidAgentFrameException()))
        log.post("tag", AgentIpcEvent.SessionFailed(IllegalArgumentException("the helper's bytes")))
        assertEquals(
            listOf(
                LogLevel.INFO to "IPC server listening",
                LogLevel.INFO to "Client disconnected",
                LogLevel.ERROR to "Connection rejected: too many concurrent IPC connections (limit=8)",
                LogLevel.ERROR to "IPC connection failed: io",
                LogLevel.ERROR to "IPC session failed: InvalidAgentFrameException",
                LogLevel.ERROR to "IPC session failed: IllegalArgumentException",
            ),
            log.lines,
        )
    }
}
