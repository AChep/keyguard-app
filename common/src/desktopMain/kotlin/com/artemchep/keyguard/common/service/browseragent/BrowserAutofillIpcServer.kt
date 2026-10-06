package com.artemchep.keyguard.common.service.browseragent

import com.artemchep.keyguard.common.service.agent.AgentIpcEndpoint
import com.artemchep.keyguard.common.service.agent.AgentIpcPeerVerificationPolicy
import com.artemchep.keyguard.common.service.agent.AgentIpcServer
import com.artemchep.keyguard.common.service.agent.AgentPacketChannel
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.util.hexToByteArray
import java.lang.Process
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.serialization.json.Json

class BrowserAutofillIpcServer private constructor(
    private val logRepository: LogRepository,
    private val authToken: ByteArray,
    private val scope: CoroutineScope,
    private val backend: BrowserAutofillBackend,
    private val maxConcurrentConnections: Int = 8,
    peerVerificationPolicy: AgentIpcPeerVerificationPolicy,
) {
    private val json = Json {
        ignoreUnknownKeys = true
    }

    private val agentIpcServer = AgentIpcServer(
        logRepository = logRepository,
        scope = scope,
        tag = TAG,
        maxConcurrentConnections = maxConcurrentConnections,
        peerVerificationPolicy = peerVerificationPolicy,
        session = ::runSession,
    )

    /**
     * WS-mode constructor: verifies the IPC peer process identity.
     */
    constructor(
        logRepository: LogRepository,
        authToken: ByteArray,
        scope: CoroutineScope,
        backend: BrowserAutofillBackend,
        maxConcurrentConnections: Int = 8,
        expectedPeerProcess: Deferred<Process>,
    ) : this(
        logRepository = logRepository,
        authToken = authToken,
        scope = scope,
        backend = backend,
        maxConcurrentConnections = maxConcurrentConnections,
        peerVerificationPolicy = AgentIpcPeerVerificationPolicy.ExactProcess(expectedPeerProcess),
    )

    /**
     * NM-mode constructor: skips process identity verification (auth token only).
     */
    internal constructor(
        logRepository: LogRepository,
        authToken: ByteArray,
        scope: CoroutineScope,
        backend: BrowserAutofillBackend,
        maxConcurrentConnections: Int,
        tokenOnly: Boolean,
    ) : this(
        logRepository = logRepository,
        authToken = authToken,
        scope = scope,
        backend = backend,
        maxConcurrentConnections = maxConcurrentConnections,
        peerVerificationPolicy = AgentIpcPeerVerificationPolicy.TokenOnly,
    )

    suspend fun start(
        endpoint: AgentIpcEndpoint,
        onReady: CompletableDeferred<Unit>? = null,
    ) {
        agentIpcServer.start(endpoint, onReady)
    }

    fun stop() {
        agentIpcServer.stop()
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun runSession(
        channel: AgentPacketChannel,
    ) {
        var authenticated = false
        try {
            var running = true
            while (running) {
                val packet = channel.readPacket()
                val req = packet
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { json.decodeFromString<IpcRequest>(String(it, UTF_8)) }
                if (req == null) {
                    running = false
                    continue
                }
                val outcome = handleRequest(channel, req, authenticated)
                authenticated = outcome.authenticated
                running = outcome.running
            }
        } catch (e: Exception) {
            logRepository.post(TAG, "Session error: ${e.message}", LogLevel.ERROR)
        }
    }

    /** Returns the new session state after [req] has been served. */
    private suspend fun handleRequest(
        channel: AgentPacketChannel,
        req: IpcRequest,
        authenticated: Boolean,
    ): SessionOutcome = when (req) {
        is IpcRequest.Authenticate -> handleAuthenticate(channel, req)

        is IpcRequest.Query -> {
            if (!authenticated) {
                SessionOutcome(authenticated = false, running = false)
            } else {
                val result = backend.query(req.domain, req.uri)
                write(
                    channel,
                    IpcResponse.Query(
                        locked = result.locked,
                        items = result.items,
                    ),
                )
                SessionOutcome(authenticated = true, running = true)
            }
        }

        is IpcRequest.Secret -> {
            if (!authenticated) {
                SessionOutcome(authenticated = false, running = false)
            } else {
                val result = backend.getSecret(req.itemId)
                write(
                    channel,
                    IpcResponse.Secret(
                        locked = result.locked,
                        username = result.username,
                        password = result.password,
                        totp = result.totp,
                    ),
                )
                SessionOutcome(authenticated = true, running = true)
            }
        }

        is IpcRequest.RequestForeground -> {
            if (!authenticated) {
                SessionOutcome(authenticated = false, running = false)
            } else {
                val success = bringWindowToFront(req.token)
                write(channel, IpcResponse.RequestForeground(success))
                SessionOutcome(authenticated = true, running = true)
            }
        }
    }

    private fun handleAuthenticate(
        channel: AgentPacketChannel,
        req: IpcRequest.Authenticate,
    ): SessionOutcome {
        val success = try {
            MessageDigest.isEqual(authToken, req.token.hexToByteArray())
        } catch (_: Exception) {
            false
        }
        write(channel, IpcResponse.Authenticate(success))
        if (!success) {
            logRepository.post(TAG, "Authentication failed", LogLevel.WARNING)
        }
        return SessionOutcome(authenticated = success, running = success)
    }

    private suspend fun bringWindowToFront(token: String?): Boolean = try {
        if (!token.isNullOrEmpty()) {
            WindowBringToFront.withToken(token)
        } else {
            WindowBringToFront()
        }
    } catch (_: Exception) {
        false
    }

    private class SessionOutcome(
        val authenticated: Boolean,
        val running: Boolean,
    )

    private fun write(
        channel: AgentPacketChannel,
        resp: IpcResponse,
    ) {
        val bytes = json.encodeToString(resp).toByteArray(UTF_8)
        channel.writePacket(bytes)
    }

    companion object {
        private const val TAG = "BrowserAutofillIpcServer"
    }
}
