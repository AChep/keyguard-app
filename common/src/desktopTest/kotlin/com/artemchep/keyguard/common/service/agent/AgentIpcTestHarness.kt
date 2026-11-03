package com.artemchep.keyguard.common.service.agent

import com.artemchep.keyguard.common.service.cli.CliCallContext
import com.artemchep.keyguard.common.service.cli.CliCommand
import com.artemchep.keyguard.common.service.cli.CliCommandDispatcher
import com.artemchep.keyguard.common.service.cli.CliCommandHandler
import com.artemchep.keyguard.common.service.cli.UNSANDBOXED
import com.artemchep.keyguard.common.service.cli.agent.CliAgentIpcServer
import com.artemchep.keyguard.common.service.cli.agent.CliAgentMessages
import com.artemchep.keyguard.common.service.cli.agent.CliAgentRequestHandler
import com.artemchep.keyguard.common.service.cli.agent.runRequest
import com.artemchep.keyguard.common.service.environment.EnvironmentFixture
import com.artemchep.keyguard.common.service.environment.EnvironmentRunCliRequest
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.sshagent.SshAgentIpcServer
import com.artemchep.keyguard.common.service.sshagent.SshAgentMessages
import com.artemchep.keyguard.common.service.sshagent.SshAgentProtoCodec
import com.artemchep.keyguard.common.service.sshagent.SshAgentRequestProcessor
import com.artemchep.keyguard.util.agentipc.AgentIpcExpectedPeer
import com.artemchep.keyguard.util.agentipc.AgentIpcLocation
import com.artemchep.keyguard.util.agentipc.AgentIpcOwnProcessTestApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import java.io.FileNotFoundException
import java.io.IOException
import java.io.RandomAccessFile
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ByteChannel
import java.nio.channels.SocketChannel
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

internal val isWindowsHost: Boolean =
    System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

/**
 * Opens a JDK client to an agent endpoint: a Unix-domain `SocketChannel`, or
 * a synchronous pipe handle on Windows, which must not read and write at once.
 */
internal suspend fun openAgentIpcTestChannel(endpoint: String): ByteChannel = withTimeout(5_000L) {
    var channel: ByteChannel? = null
    while (channel == null) {
        try {
            channel = runInterruptible(Dispatchers.IO) {
                if (isWindowsHost) {
                    // Closing the FileChannel also closes its RandomAccessFile.
                    RandomAccessFile(endpoint, "rw").channel
                } else {
                    openUnixChannel(endpoint)
                }
            }
        } catch (e: FileNotFoundException) {
            if (!isWindowsHost) throw e
            // A pipe instance is busy until the server creates the next one.
            delay(10L)
        }
    }
    channel
}

private fun openUnixChannel(endpoint: String): SocketChannel {
    val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
    try {
        channel.connect(UnixDomainSocketAddress.of(endpoint))
    } catch (e: IOException) {
        channel.close()
        throw e
    }
    return channel
}

/** A framed client of an agent endpoint. */
internal class AgentIpcTestClient private constructor(
    private val channel: ByteChannel,
) : AutoCloseable {
    companion object {
        suspend fun connect(endpoint: String) = AgentIpcTestClient(openAgentIpcTestChannel(endpoint))
    }

    suspend fun write(bytes: ByteArray) = runInterruptible(Dispatchers.IO) {
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) {
            channel.write(buffer)
        }
    }

    suspend fun writeFrame(payload: ByteArray) =
        write(AgentIpcFraming.encodeHeader(payload.size, Int.MAX_VALUE) + payload)

    /** The next frame's payload, or null at the end of the stream. */
    suspend fun readFrame(): ByteArray? = withTimeout(5_000L) {
        runInterruptible(Dispatchers.IO) {
            val header = readFully(AgentIpcFraming.HEADER_SIZE)
                ?: return@runInterruptible null
            readFully(AgentIpcFraming.decodeHeader(header))
        }
    }

    /** The count of bytes until the end of the stream; a reset counts as its end. */
    suspend fun readToEnd(): Int = withTimeout(5_000L) {
        runInterruptible(Dispatchers.IO) {
            var total = 0
            val buffer = ByteBuffer.allocate(256)
            while (true) {
                buffer.clear()
                val count = try {
                    channel.read(buffer)
                } catch (_: IOException) {
                    -1
                }
                if (count < 0) break
                total += count
            }
            total
        }
    }

    /**
     * Ends the client's output. A pipe cannot be half closed, so on Windows this
     * closes the client.
     */
    fun endOutput() {
        when (channel) {
            is SocketChannel -> channel.shutdownOutput()
            else -> channel.close()
        }
    }

    override fun close() = channel.close()

    private fun readFully(size: Int): ByteArray? {
        val buffer = ByteBuffer.allocate(size)
        while (buffer.hasRemaining()) {
            val count = try {
                channel.read(buffer)
            } catch (_: IOException) {
                -1
            }
            if (count < 0) return null
        }
        return buffer.array()
    }
}

/** Authenticates to an SSH agent server with [token]; returns whether it accepted it. */
internal suspend fun AgentIpcTestClient.authenticateSsh(token: ByteArray, id: Long): Boolean {
    writeFrame(
        SshAgentProtoCodec.encodeRequest(
            SshAgentMessages.IpcRequest(
                id = id,
                authenticate = AgentIpcMessages.AuthenticateRequest(
                    token = token.copyOf(),
                    protocolRevision = AgentIpcMessages.PROTOCOL_REVISION,
                ),
            ),
        ),
    )
    val response = SshAgentProtoCodec.decodeResponse(assertNotNull(readFrame()))
    assertEquals(id, response.id)
    return response.authenticate?.success == true
}

/** Authenticates to a CLI agent server with [token]; returns whether it accepted it. */
@OptIn(ExperimentalSerializationApi::class)
internal suspend fun AgentIpcTestClient.authenticateCli(token: ByteArray): Boolean {
    val request = CliAgentMessages.Request.Kind.Authenticate(
        AgentIpcMessages.AuthenticateRequest(
            token = token.copyOf(),
            protocolRevision = AgentIpcMessages.PROTOCOL_REVISION,
        ),
    )
    writeFrame(ProtoBuf.encodeToByteArray(CliAgentMessages.Request(request)))
    val response = ProtoBuf.decodeFromByteArray<CliAgentMessages.Response>(assertNotNull(readFrame()))
    return assertIs<CliAgentMessages.Response.Kind.Authenticate>(response.response).value.success
}

/** The payload of a CLI agent's `keyguard run` request. */
@OptIn(ExperimentalSerializationApi::class)
internal fun cliRunRequestPayload(): ByteArray =
    ProtoBuf.encodeToByteArray(CliAgentMessages.Request(CliAgentMessages.Request.Kind.Cli(runRequest())))

/**
 * A CLI request handler, with environments in [scope], whose `keyguard run`
 * is answered only once cancelled: it completes [started] when the request
 * arrives, and [cancelled] when it is cancelled.
 */
internal suspend fun pendingCliRunHandler(
    scope: CoroutineScope,
    started: CompletableDeferred<Unit>,
    cancelled: CompletableDeferred<Unit>,
): CliAgentRequestHandler {
    val fixture = EnvironmentFixture(scope)
    fixture.ready()
    fixture.cli.configureRuntime(path = "/bin/keyguard", capabilities = UNSANDBOXED)
    fixture.cli.setEnabled(true)
    fixture.cli.state.first { it.enabled }
    return CliAgentRequestHandler(
        cli = CliCommandDispatcher(fixture.cli, listOf(PendingRunHandler(started, cancelled))),
        environments = fixture.broker(),
    )
}

@Serializable
private class PendingRunAnswer

private class PendingRunHandler(
    private val started: CompletableDeferred<Unit>,
    private val cancelled: CompletableDeferred<Unit>,
) : CliCommandHandler<EnvironmentRunCliRequest, PendingRunAnswer> {
    override val command = CliCommand.Run
    override val requestSerializer = EnvironmentRunCliRequest.serializer()
    override val resultSerializer = PendingRunAnswer.serializer()

    override suspend fun handle(context: CliCallContext, request: EnvironmentRunCliRequest): PendingRunAnswer {
        started.complete(Unit)
        try {
            awaitCancellation()
        } finally {
            cancelled.complete(Unit)
        }
    }
}

/** A thread-safe [LogRepository] that keeps every line. */
internal class AgentIpcTestLog : LogRepository {
    private val entries = mutableListOf<Pair<LogLevel, String>>()

    /** Each line with its level, in order. */
    val lines: List<Pair<LogLevel, String>>
        get() = synchronized(entries) { entries.toList() }

    val errors: List<String>
        get() = lines.filter { it.first == LogLevel.ERROR }.map { it.second }

    override fun post(tag: String, message: String, level: LogLevel) {
        synchronized(entries) { entries += level to message }
    }

    override suspend fun add(tag: String, message: String, level: LogLevel) = post(tag, message, level)
}

/** An SSH request processor whose vault is locked. */
internal object LockedSshAgentRequestProcessor : SshAgentRequestProcessor {
    override suspend fun listKeys(caller: CallerIdentity?) =
        SshAgentRequestProcessor.ListKeysResult.VaultLocked

    override suspend fun signData(request: SshAgentMessages.SignDataRequest) =
        SshAgentRequestProcessor.SignDataResult.UserDenied
}

/** Admits this process as the agent helper once [open]ed. */
internal class AgentIpcTestPeerGate {
    private val peer = CompletableDeferred<AgentIpcExpectedPeer>()

    @OptIn(AgentIpcOwnProcessTestApi::class)
    fun open() {
        peer.complete(AgentIpcExpectedPeer.currentProcessForTests())
    }

    suspend fun await(): AgentIpcExpectedPeer = peer.await()
}

/** A running agent server: its endpoint, and how to stop it. */
internal class AgentIpcTestServer(
    val endpoint: String,
    private val job: Job,
    private val close: () -> Unit,
) {
    /** Whether the server still serves. */
    val isServing: Boolean get() = job.isActive

    /** Stops the server; it returns once every session has ended. */
    suspend fun stop() {
        close()
        withTimeout(5_000L) { job.join() }
    }
}

internal fun serveSshForTest(
    log: LogRepository,
    authToken: ByteArray,
    gate: AgentIpcTestPeerGate,
): AgentIpcTestServer {
    val server = SshAgentIpcServer(
        logRepository = log,
        authToken = authToken,
        requestProcessor = LockedSshAgentRequestProcessor,
    )
    return serveForTest(server::bind, { server.serve(gate::await) }, server::close)
}

internal fun serveCliForTest(
    log: LogRepository,
    authToken: ByteArray,
    handler: CliAgentRequestHandler,
    gate: AgentIpcTestPeerGate,
    ioTimeoutMs: Long = CliAgentIpcServer.IO_TIMEOUT_MS,
): AgentIpcTestServer {
    val server = CliAgentIpcServer(
        logRepository = log,
        authToken = authToken,
        handler = handler,
        ioTimeoutMs = ioTimeoutMs,
    )
    return serveForTest(server::bind, { server.serve(gate::await) }, server::close)
}

private fun serveForTest(
    bind: (AgentIpcLocation) -> String,
    serve: suspend () -> Unit,
    close: () -> Unit,
): AgentIpcTestServer {
    val endpoint = bind(agentIpcLocation("kg-test"))
    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    val job = scope.launch { serve() }
    return AgentIpcTestServer(endpoint, job) {
        close()
        scope.cancel()
    }
}
