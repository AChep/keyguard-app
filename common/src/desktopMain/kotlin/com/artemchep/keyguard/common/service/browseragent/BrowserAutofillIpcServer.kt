package com.artemchep.keyguard.common.service.browseragent

import com.artemchep.keyguard.common.service.agent.AgentIpcEndpoint
import com.artemchep.keyguard.common.service.agent.AgentIpcPeerVerificationPolicy
import com.artemchep.keyguard.common.service.agent.AgentIpcServer
import com.artemchep.keyguard.common.service.agent.AgentPacketChannel
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.util.hexToByteArray
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.lang.Process

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

    private suspend fun runSession(
        channel: AgentPacketChannel,
    ) {
        var authenticated = false
        try {
            while (true) {
                val raw = channel.readPacket() ?: break
                if (raw.isEmpty()) {
                    break
                }
                val req = json.decodeFromString<IpcRequest>(String(raw, UTF_8))
                when (req) {
                    is IpcRequest.Authenticate -> {
                        authenticated = try {
                            MessageDigest.isEqual(authToken, req.token.hexToByteArray())
                        } catch (_: Exception) {
                            false
                        }
                        write(channel, IpcResponse.Authenticate(authenticated))
                        if (!authenticated) {
                            logRepository.post(TAG, "Authentication failed", LogLevel.WARNING)
                            break
                        }
                    }

                    is IpcRequest.Query -> {
                        if (!authenticated) {
                            break
                        }
                        val result = backend.query(req.domain, req.uri)
                        write(
                            channel,
                            IpcResponse.Query(
                                locked = result.locked,
                                items = result.items,
                            ),
                        )
                    }

                    is IpcRequest.Secret -> {
                        if (!authenticated) {
                            break
                        }
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
                    }

                    is IpcRequest.RequestForeground -> {
                        if (!authenticated) {
                            break
                        }
                        val success = try {
                            val token = req.token
                            if (!token.isNullOrEmpty()) {
                                WindowBringToFront.withToken(token)
                            } else {
                                WindowBringToFront()
                            }
                        } catch (_: Exception) {
                            false
                        }
                        write(channel, IpcResponse.RequestForeground(success))
                    }
                }
            }
        } catch (e: Exception) {
            logRepository.post(TAG, "Session error: ${e.message}", LogLevel.ERROR)
        }
    }

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
