package com.artemchep.keyguard.feature.auth.companion

import android.app.Application
import androidx.core.net.toUri
import com.artemchep.keyguard.android.CompanionAuthActivity
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.runCatchingNonFatal
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.di.resolveOrCancel
import com.artemchep.keyguard.platform.recordException
import com.artemchep.keyguard.platform.recordLog
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.ImportCompanionBitwardenAccount
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.ImportCompanionKeePassAccount
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.ImportCompanionKeePassAccountUseCase
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.wearable.ChannelClient
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * The watch side of companion auth: asks a paired phone to log in, then
 * imports the account the phone sends back.
 */
internal class CompanionAuthInitiatorAndroid(
    private val application: Application,
    private val json: Json,
    private val transport: CompanionAuthTransportAndroid,
    private val security: CompanionAuthSecurityAndroid,
    private val getVaultSession: GetVaultSession,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val requestStore = CompanionAuthRequestStore()
    private val pendingKeePassTransfers = mutableMapOf<String, PendingKeePassTransfer>()
    private val pendingKeePassTransfersMutex = Mutex()

    fun phoneDiscoveryFlow(): Flow<CompanionAuthPhoneDiscovery> =
        transport.phoneDiscoveryFlow()

    fun getRequestStateFlow(
        requestId: String,
    ): Flow<CompanionAuthRequestState?> = requestStore.getRequestStateFlow(requestId)

    suspend fun startOnPhone(
        provider: CompanionAuthProvider,
        nodeId: String? = null,
    ): String? {
        val requestId = kotlin.uuid.Uuid.random().toString()
        val phoneNode = when (val selection = selectPhoneNodeForRequest(
            requestId = requestId,
            nodeId = nodeId,
        )) {
            is CompanionAuthPhoneSelection.Available -> selection.node
            is CompanionAuthPhoneSelection.Failed -> return requestId
            CompanionAuthPhoneSelection.NoneAvailable -> return null
        }

        requestStore.dispatch(
            requestId = requestId,
            event = CompanionAuthRequestEvent.RequestStarted,
        )
        runCatchingNonFatal {
            val localNodeId = transport.getLocalNodeId()
            val keyPair = security.createEphemeralKeyPair()
            val request = CompanionAuthRequest(
                requestId = requestId,
                provider = provider,
                protocolVersion = CompanionAuthProtocol.VERSION,
                watchPublicKey = keyPair.publicKeyBase64,
            )
            val session = CompanionAuthPendingSession(
                requestId = requestId,
                provider = provider,
                role = CompanionAuthPendingSession.Role.Initiator,
                localNodeId = localNodeId,
                expectedNodeId = phoneNode.id,
                protocolVersion = CompanionAuthProtocol.VERSION,
                createdAtEpochMillis = System.currentTimeMillis(),
                localPrivateKeyBase64 = keyPair.privateKeyBase64,
                localPublicKeyBase64 = keyPair.publicKeyBase64,
            )
            security.putPendingSession(session)
            transport.sendMessage(
                nodeId = phoneNode.id,
                path = CompanionAuthProtocol.REQUEST_PATH,
                payload = json.encodeToString(request).encodeToByteArray(),
            )

            val intent = CompanionAuthActivity.getIntent(
                context = application,
                requestId = requestId,
                provider = provider,
            )
            transport.startRemoteActivity(
                intent = intent,
                nodeId = phoneNode.id,
            )
        }.onFailure { error ->
            recordException(error)
            deleteRequestArtifacts(requestId)
            requestStore.dispatch(
                requestId = requestId,
                event = CompanionAuthRequestEvent.RemoteLaunchFailed(
                    message = error.message,
                ),
            )
        }
        scope.launch {
            delay(CompanionAuthProtocol.SESSION_TIMEOUT_MS)
            val sessionStillActive = security.getPendingSession(requestId) != null
            if (!sessionStillActive) {
                return@launch
            }

            deleteRequestArtifacts(requestId)
            requestStore.dispatch(
                requestId = requestId,
                event = CompanionAuthRequestEvent.TimedOut(
                    message = "Phone login timed out.",
                ),
            )
        }
        return requestId
    }

    private suspend fun selectPhoneNodeForRequest(
        requestId: String,
        nodeId: String? = null,
    ): CompanionAuthPhoneSelection = when (val selection = selectPhoneNode(nodeId = nodeId)) {
        is CompanionAuthPhoneSelection.Available -> selection
        is CompanionAuthPhoneSelection.Failed -> {
            requestStore.dispatch(
                requestId = requestId,
                event = CompanionAuthRequestEvent.PhoneErrored(
                    error = selection.error,
                    message = selection.message,
                ),
            )
            selection
        }

        CompanionAuthPhoneSelection.NoneAvailable -> selection
    }

    private suspend fun selectPhoneNode(
        nodeId: String? = null,
    ): CompanionAuthPhoneSelection {
        val reachableNodes = try {
            transport.getReachablePhoneNodes()
        } catch (e: ApiException) {
            // Google Play services went away after the screen found the phone.
            return CompanionAuthPhoneSelection.Failed(
                error = CompanionAuthError.PHONE_UNAVAILABLE,
                message = "Wearable API is unavailable: ${e.message}",
            )
        }
        return when (val resolution = resolveCompanionPhoneNodeId(
            reachableNodeIds = reachableNodes.map { it.id },
            selectedNodeId = nodeId,
        )) {
            CompanionAuthPhoneNodeResolution.NoneAvailable -> CompanionAuthPhoneSelection.NoneAvailable
            is CompanionAuthPhoneNodeResolution.Available -> CompanionAuthPhoneSelection.Available(
                reachableNodes.first { it.id == resolution.nodeId },
            )
            is CompanionAuthPhoneNodeResolution.Failed -> CompanionAuthPhoneSelection.Failed(
                error = resolution.error,
                message = resolution.message,
            )
        }
    }

    suspend fun onWatchResponseReceived(
        nodeId: String,
        response: CompanionAuthResponse,
    ) {
        val normalizedRequestId = canonicalCompanionAuthRequestIdOrNull(response.requestId)
        if (normalizedRequestId == null) {
            return
        }
        if (!isResponsePayloadWithinLimits(response)) {
            deleteRequestArtifacts(normalizedRequestId)
            requestStore.dispatch(
                requestId = normalizedRequestId,
                event = CompanionAuthRequestEvent.PhoneErrored(
                    error = CompanionAuthError.PAYLOAD_TOO_LARGE,
                    message = "Companion auth response exceeded transport limits.",
                ),
            )
            return
        }
        val session = security.getPendingSession(normalizedRequestId)
        if (session == null) {
            requestStore.dispatch(
                requestId = normalizedRequestId,
                event = CompanionAuthRequestEvent.InvalidRequest(
                    message = "Missing companion session.",
                ),
            )
            return
        }
        if (!isValidWatchResponseSession(session, nodeId, response)) {
            deleteRequestArtifacts(normalizedRequestId)
            requestStore.dispatch(
                requestId = normalizedRequestId,
                event = CompanionAuthRequestEvent.PhoneErrored(
                    error = CompanionAuthError.SECURITY_VALIDATION_FAILED,
                    message = "Companion auth response validation failed.",
                ),
            )
            return
        }

        when (response.status) {
            CompanionAuthStatus.STARTED -> {
                val phonePublicKey = response.phonePublicKey
                if (phonePublicKey.isNullOrEmpty() || !security.isValidRemotePublicKey(phonePublicKey)) {
                    deleteRequestArtifacts(normalizedRequestId)
                    requestStore.dispatch(
                        requestId = normalizedRequestId,
                        event = CompanionAuthRequestEvent.PhoneErrored(
                            error = CompanionAuthError.SECURITY_VALIDATION_FAILED,
                            message = "Invalid phone handshake key.",
                        ),
                    )
                    return
                }
                security.putPendingSession(
                    session.copy(
                        remotePublicKeyBase64 = phonePublicKey,
                    ),
                )
                requestStore.dispatch(
                    requestId = normalizedRequestId,
                    event = CompanionAuthRequestEvent.RequestStarted,
                )
            }

            CompanionAuthStatus.CANCELLED -> {
                val payload = decryptSecurePayloadOrNull(session, response.encryptedPayload)
                    as? CompanionAuthCancelledPayload
                    ?: return handleIntegrityFailure(normalizedRequestId)
                deleteRequestArtifacts(normalizedRequestId)
                requestStore.dispatch(
                    requestId = normalizedRequestId,
                    event = CompanionAuthRequestEvent.PhoneCancelled(
                        message = payload.message,
                    ),
                )
            }

            CompanionAuthStatus.ERROR -> {
                if (response.error == CompanionAuthError.UNSUPPORTED_PROTOCOL) {
                    deleteRequestArtifacts(normalizedRequestId)
                    requestStore.dispatch(
                        requestId = normalizedRequestId,
                        event = CompanionAuthRequestEvent.PhoneErrored(
                            error = CompanionAuthError.UNSUPPORTED_PROTOCOL,
                            message = response.message,
                        ),
                    )
                    return
                }
                val payload = decryptSecurePayloadOrNull(session, response.encryptedPayload)
                    as? CompanionAuthErroredPayload
                    ?: return handleIntegrityFailure(normalizedRequestId)
                deleteRequestArtifacts(normalizedRequestId)
                requestStore.dispatch(
                    requestId = normalizedRequestId,
                    event = CompanionAuthRequestEvent.PhoneErrored(
                        error = payload.error,
                        message = payload.message,
                    ),
                )
            }

            CompanionAuthStatus.SUCCESS -> {
                when (val payload = decryptSecurePayloadOrNull(session, response.encryptedPayload)) {
                    is CompanionBitwardenPayload -> {
                        importBitwarden(normalizedRequestId, payload)
                    }

                    is CompanionKeePassPayload -> {
                        pendingKeePassTransfersMutex.withLock {
                            val transfer = pendingKeePassTransfers[normalizedRequestId]
                                ?: PendingKeePassTransfer()
                            pendingKeePassTransfers[normalizedRequestId] = transfer
                                .withPayload(payload)
                        }
                        maybeImportKeePass(normalizedRequestId)
                    }

                    else -> {
                        handleIntegrityFailure(normalizedRequestId)
                    }
                }
            }
        }
    }

    suspend fun onChannelOpened(
        channel: ChannelClient.Channel,
    ) {
        val incomingChannel = parseCompanionAuthIncomingChannelPath(channel.path)
            ?: return
        val session = security.getPendingSession(incomingChannel.requestId)
        if (session == null || !canAcceptIncomingChannel(session, channel.nodeId)) {
            deleteRequestArtifacts(incomingChannel.requestId)
            requestStore.dispatch(
                requestId = incomingChannel.requestId,
                event = CompanionAuthRequestEvent.PhoneErrored(
                    error = CompanionAuthError.SECURITY_VALIDATION_FAILED,
                    message = "Companion auth channel validation failed.",
                ),
            )
            return
        }

        val targetFile = createIncomingFile(
            requestId = incomingChannel.requestId,
            kind = incomingChannel.kind,
        )
        runCatchingNonFatal {
            when (incomingChannel.kind) {
                CompanionAuthIncomingChannel.Kind.Database -> {
                    transport.receiveIncomingChannel(
                        channel = channel,
                        targetFile = targetFile,
                        maxBytes = CompanionAuthProtocol.MAX_KEEPASS_DATABASE_BYTES,
                    )
                }

                CompanionAuthIncomingChannel.Kind.Key -> {
                    transport.receiveIncomingChannel(
                        channel = channel,
                        maxBytes = CompanionAuthProtocol.MAX_KEEPASS_KEY_FILE_BYTES,
                    ) { bytes ->
                        val blob = runCatchingNonFatal {
                            json.decodeFromString<CompanionAuthEncryptedBlob>(bytes.decodeToString())
                        }.getOrElse { error ->
                            recordException(error)
                            throw error
                        }
                        val plainText = security.decryptBlob(
                            session = session,
                            blob = blob,
                        )
                        withContext(Dispatchers.IO) {
                            targetFile.outputStream().use { sink ->
                                sink.write(plainText)
                            }
                        }
                    }
                }
            }
        }.onFailure { error ->
            recordException(error)
            withContext(Dispatchers.IO) {
                targetFile.delete()
            }
            deleteRequestArtifacts(incomingChannel.requestId)
            val companionError = if (error is CompanionAuthPayloadTooLargeException) {
                CompanionAuthError.PAYLOAD_TOO_LARGE
            } else {
                CompanionAuthError.SECURITY_VALIDATION_FAILED
            }
            val message = if (error is CompanionAuthPayloadTooLargeException) {
                "Companion auth transfer exceeded transport limits."
            } else {
                "Companion auth channel validation failed."
            }
            requestStore.dispatch(
                requestId = incomingChannel.requestId,
                event = CompanionAuthRequestEvent.PhoneErrored(
                    error = companionError,
                    message = message,
                ),
            )
            return
        }

        pendingKeePassTransfersMutex.withLock {
            val transfer = pendingKeePassTransfers[incomingChannel.requestId]
                ?: PendingKeePassTransfer()
            pendingKeePassTransfers[incomingChannel.requestId] = when (incomingChannel.kind) {
                CompanionAuthIncomingChannel.Kind.Database -> transfer.withDatabaseFile(targetFile)
                CompanionAuthIncomingChannel.Kind.Key -> transfer.withKeyFile(targetFile)
            }
        }
        maybeImportKeePass(incomingChannel.requestId)
    }

    private suspend fun importBitwarden(
        requestId: String,
        payload: CompanionBitwardenPayload,
    ) {
        requestStore.dispatch(
            requestId = requestId,
            event = CompanionAuthRequestEvent.ImportStarted,
        )
        runCatchingNonFatal {
            val importCompanionBitwardenAccount =
                resolveSessionImportUseCase<ImportCompanionBitwardenAccount>()
            importCompanionBitwardenAccount(payload).bind()
        }.onSuccess {
            deleteRequestArtifacts(requestId)
            requestStore.dispatch(
                requestId = requestId,
                event = CompanionAuthRequestEvent.ImportSucceeded,
            )
        }.onFailure { error ->
            recordException(error)
            deleteRequestArtifacts(requestId)
            requestStore.dispatch(
                requestId = requestId,
                event = CompanionAuthRequestEvent.ImportFailed(
                    message = error.message,
                ),
            )
        }
    }

    private suspend fun maybeImportKeePass(
        requestId: String,
    ) {
        val payload: CompanionKeePassPayload
        val databaseFile: File
        val keyFile: File?
        pendingKeePassTransfersMutex.withLock {
            val transfer = pendingKeePassTransfers[requestId]
                ?: return
            if (!transfer.canStartImport()) {
                return
            }

            val updatedTransfer = transfer.markImportStarted()
            pendingKeePassTransfers[requestId] = updatedTransfer
            payload = updatedTransfer.payload
                ?: return
            databaseFile = updatedTransfer.databaseFile
                ?: return
            keyFile = updatedTransfer.keyFile
        }

        requestStore.dispatch(
            requestId = requestId,
            event = CompanionAuthRequestEvent.ImportStarted,
        )

        runCatchingNonFatal {
            val managedDatabaseFile = withContext(Dispatchers.IO) {
                stageManagedCompanionKeePassDatabase(
                    filesDir = application.filesDir,
                    requestId = requestId,
                    sourceFile = databaseFile,
                )
            }
            val importCompanionKeePassAccount =
                resolveSessionImportUseCase<ImportCompanionKeePassAccountUseCase>()
            importCompanionKeePassAccount(
                ImportCompanionKeePassAccount.Params(
                    payload = payload,
                    databaseUri = managedDatabaseFile.toUri().toString(),
                    keyUri = keyFile?.toUri()?.toString(),
                ),
            ).bind()
        }.onSuccess {
            deleteRequestArtifacts(requestId)
            requestStore.dispatch(
                requestId = requestId,
                event = CompanionAuthRequestEvent.ImportSucceeded,
            )
        }.onFailure { error ->
            recordException(error)
            deleteManagedCompanionKeePassArtifacts(
                filesDir = application.filesDir,
                requestId = requestId,
            )
            deleteRequestArtifacts(requestId)
            requestStore.dispatch(
                requestId = requestId,
                event = CompanionAuthRequestEvent.ImportFailed(
                    message = error.message,
                ),
            )
        }
    }

    private suspend fun handleIntegrityFailure(
        requestId: String,
    ) {
        deleteRequestArtifacts(requestId)
        requestStore.dispatch(
            requestId = requestId,
            event = CompanionAuthRequestEvent.PhoneErrored(
                error = CompanionAuthError.INTEGRITY_CHECK_FAILED,
                message = "Companion auth payload validation failed.",
            ),
        )
    }

    private suspend fun decryptSecurePayloadOrNull(
        session: CompanionAuthPendingSession,
        encryptedPayload: CompanionAuthEncryptedPayload?,
    ): CompanionAuthSecurePayload? = runCatchingNonFatal {
        encryptedPayload
            ?: return null
        security.decryptSecurePayload(
            session = session,
            encryptedPayload = encryptedPayload,
        )
    }.getOrElse { error ->
        recordException(error)
        null
    }

    private fun createIncomingFile(
        requestId: String,
        kind: CompanionAuthIncomingChannel.Kind,
    ): File {
        val normalizedRequestId = canonicalCompanionAuthRequestIdOrNull(requestId)
            ?: error("Invalid companion auth request id: $requestId")
        val fileName = when (kind) {
            CompanionAuthIncomingChannel.Kind.Database -> "database.kdbx"
            CompanionAuthIncomingChannel.Kind.Key -> "database.key"
        }
        val dir = security.requestDir(normalizedRequestId)
        dir.mkdirs()
        return dir.resolve(fileName)
    }

    private suspend fun deleteRequestArtifacts(
        requestId: String,
    ) {
        val normalizedRequestId = canonicalCompanionAuthRequestIdOrNull(requestId)
        pendingKeePassTransfersMutex.withLock {
            pendingKeePassTransfers.remove(normalizedRequestId ?: requestId)
        }
        security.deleteRequestArtifacts(requestId)
    }

    private fun isResponsePayloadWithinLimits(
        response: CompanionAuthResponse,
    ): Boolean = isCompanionAuthResponsePayloadWithinLimits(response)

    private suspend inline fun <reified T : Any> resolveSessionImportUseCase(): T {
        val session = getVaultSession.valueOrNull ?: getVaultSession().first()
        val keySession = session as? MasterSession.Key
            ?: error("A vault session is required to import a companion account.")
        return keySession.session.resolveOrCancel { get() }
    }
}

internal class CompanionAuthRequestStore {
    private val states = MutableStateFlow<Map<String, CompanionAuthRequestState>>(emptyMap())

    fun getRequestStateFlow(
        requestId: String,
    ): Flow<CompanionAuthRequestState?> = states
        .map { it[requestId] }
        .distinctUntilChanged()

    fun dispatch(
        requestId: String,
        event: CompanionAuthRequestEvent,
    ): CompanionAuthRequestState? {
        // The screen shows a localized text per error, so the event's
        // message only survives as a diagnostic.
        recordLog("Companion auth requestId=$requestId event=$event")
        var nextState: CompanionAuthRequestState? = null
        states.update { currentStates ->
            val reducedState = reduceCompanionAuthRequestState(
                current = currentStates[requestId],
                event = event,
            )
            nextState = reducedState
            if (reducedState == null) {
                currentStates - requestId
            } else {
                currentStates + (requestId to reducedState)
            }
        }
        return nextState
    }
}

internal data class CompanionAuthIncomingChannel(
    val requestId: String,
    val kind: Kind,
) {
    enum class Kind {
        Database,
        Key,
    }
}

internal fun parseCompanionAuthIncomingChannelPath(
    path: String?,
): CompanionAuthIncomingChannel? {
    val parts = path
        ?.trim('/')
        ?.split('/')
        ?: return null
    if (parts.size != 4 || parts[0] != "companion-auth" || parts[1] != "channel") {
        return null
    }

    val kind = when (parts[3]) {
        "database" -> CompanionAuthIncomingChannel.Kind.Database
        "key" -> CompanionAuthIncomingChannel.Kind.Key
        else -> return null
    }
    val requestId = canonicalCompanionAuthRequestIdOrNull(parts[2])
        ?: return null

    return CompanionAuthIncomingChannel(
        requestId = requestId,
        kind = kind,
    )
}

private data class PendingKeePassTransfer(
    val payload: CompanionKeePassPayload? = null,
    val databaseFile: File? = null,
    val keyFile: File? = null,
    val importStarted: Boolean = false,
) {
    fun withPayload(
        payload: CompanionKeePassPayload,
    ): PendingKeePassTransfer = copy(payload = payload)

    fun withDatabaseFile(
        databaseFile: File,
    ): PendingKeePassTransfer = copy(databaseFile = databaseFile)

    fun withKeyFile(
        keyFile: File,
    ): PendingKeePassTransfer = copy(keyFile = keyFile)

    fun markImportStarted(): PendingKeePassTransfer = copy(importStarted = true)

    fun canStartImport(): Boolean {
        val payload = payload
            ?: return false
        if (databaseFile == null || importStarted) {
            return false
        }
        if (payload.keyFileName != null && keyFile == null) {
            return false
        }
        return true
    }
}

private sealed interface CompanionAuthPhoneSelection {
    data object NoneAvailable : CompanionAuthPhoneSelection

    data class Available(
        val node: com.google.android.gms.wearable.Node,
    ) : CompanionAuthPhoneSelection

    data class Failed(
        val error: CompanionAuthError,
        val message: String,
    ) : CompanionAuthPhoneSelection
}

internal sealed interface CompanionAuthPhoneNodeResolution {
    data object NoneAvailable : CompanionAuthPhoneNodeResolution

    data class Available(
        val nodeId: String,
    ) : CompanionAuthPhoneNodeResolution

    data class Failed(
        val error: CompanionAuthError,
        val message: String,
    ) : CompanionAuthPhoneNodeResolution
}

internal fun resolveCompanionPhoneNodeId(
    reachableNodeIds: Collection<String>,
    selectedNodeId: String?,
): CompanionAuthPhoneNodeResolution {
    if (reachableNodeIds.isEmpty()) {
        return CompanionAuthPhoneNodeResolution.NoneAvailable
    }

    if (selectedNodeId != null) {
        return if (selectedNodeId in reachableNodeIds) {
            CompanionAuthPhoneNodeResolution.Available(selectedNodeId)
        } else {
            CompanionAuthPhoneNodeResolution.Failed(
                error = CompanionAuthError.PHONE_UNAVAILABLE,
                message = "The selected phone is not reachable.",
            )
        }
    }

    return if (reachableNodeIds.size == 1) {
        CompanionAuthPhoneNodeResolution.Available(reachableNodeIds.first())
    } else {
        CompanionAuthPhoneNodeResolution.Failed(
            error = CompanionAuthError.MULTIPLE_REACHABLE_PHONES,
            message = "Multiple companion phones are reachable. Reset or establish trust with one phone first.",
        )
    }
}

internal fun isCompanionAuthResponsePayloadWithinLimits(
    response: CompanionAuthResponse,
): Boolean {
    val encryptedPayload = response.encryptedPayload
        ?: return true
    return encryptedPayload.cipherText
        .encodeToByteArray()
        .size <= CompanionAuthProtocol.MAX_RESPONSE_CIPHERTEXT_BYTES
}

internal fun isValidWatchResponseSession(
    session: CompanionAuthPendingSession,
    nodeId: String,
    response: CompanionAuthResponse,
): Boolean {
    val hasExpectedSession = session.role == CompanionAuthPendingSession.Role.Initiator &&
        !session.isExpired() &&
        session.expectedNodeId == nodeId &&
        session.provider == response.provider &&
        session.protocolVersion == CompanionAuthProtocol.VERSION
    if (!hasExpectedSession) {
        return false
    }

    return response.protocolVersion == CompanionAuthProtocol.VERSION ||
        response.isUnsupportedProtocolError()
}

private fun CompanionAuthResponse.isUnsupportedProtocolError(): Boolean =
    status == CompanionAuthStatus.ERROR &&
        error == CompanionAuthError.UNSUPPORTED_PROTOCOL

internal fun canAcceptIncomingChannel(
    session: CompanionAuthPendingSession,
    nodeId: String,
): Boolean = session.role == CompanionAuthPendingSession.Role.Initiator &&
    !session.isExpired() &&
    session.provider == CompanionAuthProvider.KEEPASS &&
    session.expectedNodeId == nodeId &&
    !session.remotePublicKeyBase64.isNullOrEmpty()
