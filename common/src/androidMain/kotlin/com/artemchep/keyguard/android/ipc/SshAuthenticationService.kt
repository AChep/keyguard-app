package com.artemchep.keyguard.android.ipc

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.AddSshUsageHistoryRequest
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.model.SshUsageHistoryRequestType
import com.artemchep.keyguard.common.model.SshUsageHistoryResponseType
import com.artemchep.keyguard.common.service.androidipc.SshVaultLoader
import com.artemchep.keyguard.common.service.pendinghistory.PendingUsageHistory
import com.artemchep.keyguard.common.service.pendinghistory.PendingUsageHistoryQueue
import com.artemchep.keyguard.common.service.sshagent.SshAgentPublicKeyRepository
import com.artemchep.keyguard.common.service.sshagent.SshAgentPublicKeyRow
import com.artemchep.keyguard.common.usecase.AddSshUsageHistory
import com.artemchep.keyguard.common.usecase.GetSshAgentFilter
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.nativecrypto.NativeCrypto
import com.artemchep.keyguard.nativecrypto.NativeSshPublicKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.ipc_operation_ssh_get_public_key
import com.artemchep.keyguard.res.ipc_operation_ssh_get_ssh_public_key
import com.artemchep.keyguard.res.ipc_operation_ssh_other
import com.artemchep.keyguard.res.ipc_operation_ssh_select_key
import com.artemchep.keyguard.res.ipc_operation_ssh_sign
import com.artemchep.keyguard.res.ipc_protocol_ssh
import org.jetbrains.compose.resources.StringResource
import org.kodein.di.DIAware
import org.kodein.di.android.closestDI
import org.kodein.di.direct
import org.kodein.di.instance
import org.kodein.di.instanceOrNull
import org.openintents.openpgp.util.OpenPgpApi
import org.openintents.ssh.authentication.ISshAuthenticationService
import org.openintents.ssh.authentication.SshAuthenticationApi
import org.openintents.ssh.authentication.SshAuthenticationApiError
import org.openintents.ssh.authentication.response.KeySelectionResponse
import org.openintents.ssh.authentication.response.PublicKeyResponse
import org.openintents.ssh.authentication.response.SigningResponse
import org.openintents.ssh.authentication.response.SshPublicKeyResponse
import kotlin.getValue
import kotlin.time.Instant

class SshAuthenticationService : Service(), DIAware {
    companion object {
        private const val MAX_KEY_ID_LENGTH = 512
        private const val MAX_CHALLENGE_BYTES = 1024 * 1024

        private val SUPPORTED_ACTIONS = setOf(
            SshAuthenticationApi.ACTION_SELECT_KEY,
            SshAuthenticationApi.ACTION_GET_PUBLIC_KEY,
            SshAuthenticationApi.ACTION_GET_SSH_PUBLIC_KEY,
            SshAuthenticationApi.ACTION_SIGN,
        )
    }

    override val di by closestDI { this }

    private val getSshAgentFilter by instance<GetSshAgentFilter>()
    private val getVaultSession by instance<GetVaultSession>()
    private val registrationRepository by instance<AndroidIpcRegistrationRepository>()
    private val publicKeyRepository by instance<SshAgentPublicKeyRepository>()
    private val historyQueue by instance<PendingUsageHistoryQueue>()
    private val json by instance<Json>()

    private val vaultLoader by lazy {
        SshVaultLoader(
            getVaultSession = getVaultSession,
            getSshAgentFilter = getSshAgentFilter,
        )
    }

    private val binder = object : ISshAuthenticationService.Stub() {
        @Suppress("TooGenericExceptionCaught")
        override fun execute(request: Intent?): Intent {
            val caller = captureAndroidIpcCaller(this@SshAuthenticationService)
                ?: return sshError(
                    SshAuthenticationApiError.NO_AUTH_KEY,
                    "The calling application could not be attributed uniquely.",
                )
            return try {
                runBlocking(Dispatchers.IO) {
                    executeInternal(
                        caller = caller,
                        request = request,
                    )
                }
            } catch (e: Exception) {
                sshError(
                    SshAuthenticationApiError.GENERIC_ERROR,
                    e.message ?: "The SSH Authentication request is invalid.",
                )
            }
        }
    }

    /**
     * Only the static contract is checked here. Whether the provider is
     * enabled is enforced by the manifest component state — the system does
     * not route a bind to a disabled component — and re-checked per request
     * in [executeInternal]. A null returned for transient state would be
     * cached by system_server for the life of the service, so a client that
     * binds while the preference read is still in flight could never connect
     * again without the service being destroyed first.
     */
    override fun onBind(intent: Intent?): IBinder? = binder
        .takeIf { intent?.action == SshAuthenticationApi.SERVICE_INTENT }

    /** Outcome of request parsing and caller admission. */
    private sealed interface AdmittedRequest {
        class Rejected(val result: Intent) : AdmittedRequest

        class Admitted(
            val action: String,
            /** The request stripped down to the extras the retry may carry. */
            val sanitized: Intent,
            val requestDigest: String,
            val keyId: String?,
            val challenge: ByteArray?,
            val hashAlgorithm: Int?,
            val authorization: AndroidIpcApprovalCoordinator.Authorization?,
        ) : AdmittedRequest
    }

    @Suppress("LongMethod", "ReturnCount", "TooGenericExceptionCaught")
    private suspend fun executeInternal(
        caller: AndroidIpcCaller,
        request: Intent?,
    ): Intent {
        val admitted = when (
            val admission = admitRequest(
                caller = caller,
                request = request,
            )
        ) {
            is AdmittedRequest.Rejected -> return admission.result
            is AdmittedRequest.Admitted -> admission
        }
        val action = admitted.action
        if (
            action == SshAuthenticationApi.ACTION_SELECT_KEY &&
            admitted.authorization?.approvedKeyIds?.singleOrNull() == null
        ) {
            return approvalRequiredResult(
                caller = caller,
                action = action,
                operation = operationName(action),
                requestDigest = admitted.requestDigest,
                sanitizedRequest = admitted.sanitized,
                requestedKeyId = admitted.keyId,
                registerApp = false,
                requiresAuthentication = false,
            )
        }

        val approvedId = if (action == SshAuthenticationApi.ACTION_SELECT_KEY) {
            admitted.authorization?.approvedKeyIds?.singleOrNull()
                ?: return sshError(
                    SshAuthenticationApiError.NO_AUTH_KEY,
                    "Exactly one selected key is required.",
                )
        } else {
            requireNotNull(admitted.keyId)
        }
        if (admitted.keyId != null && approvedId != admitted.keyId) {
            return sshError(
                SshAuthenticationApiError.NO_AUTH_KEY,
                "The approved key does not match this request.",
            )
        }
        val publicKey = publicKeyRepository.get()
            .bind()
            .singleOrNull { it.cipherId == approvedId }
            ?: return sshError(
                SshAuthenticationApiError.NO_SUCH_KEY,
                "The key is unavailable or ambiguous.",
            )
        val decodedPublicKey = runCatching {
            NativeCrypto.ssh.decodePublicKey(publicKey.publicKey)
        }.getOrElse {
            return sshError(
                SshAuthenticationApiError.INVALID_ALGORITHM,
                "The approved key uses an unsupported or malformed SSH algorithm.",
            )
        }

        return try {
            dispatchAction(
                caller = caller,
                admitted = admitted,
                approvedId = approvedId,
                publicKey = publicKey,
                decodedPublicKey = decodedPublicKey,
            )
        } catch (e: Exception) {
            recordUsage(
                key = publicKey,
                caller = caller,
                type = if (action == SshAuthenticationApi.ACTION_SIGN) {
                    SshUsageHistoryRequestType.AGENT_SIGN_DATA
                } else {
                    SshUsageHistoryRequestType.AGENT_LIST_KEYS
                },
                response = SshUsageHistoryResponseType.FAILURE,
            )
            sshError(
                SshAuthenticationApiError.INTERNAL_ERROR,
                e.message ?: "The SSH operation failed.",
            )
        }
    }

    /**
     * Validates the request, rebuilds the sanitized retry intent and its
     * digest, and runs the shared caller admission gate.
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
    private suspend fun admitRequest(
        caller: AndroidIpcCaller,
        request: Intent?,
    ): AdmittedRequest {
        if (!AndroidIpcProviderGate.isSshEnabled()) {
            return rejected(
                SshAuthenticationApiError.NO_AUTH_KEY,
                "The SSH Authentication provider is disabled.",
            )
        }
        val action = request?.action
            ?: return rejected(
                SshAuthenticationApiError.UNKNOWN_ACTION,
                "A supported action is required.",
            )
        val version = request.getIntExtra(
            SshAuthenticationApi.EXTRA_API_VERSION,
            Int.MIN_VALUE,
        )
        if (!isSupportedSshAuthenticationApiVersion(version)) {
            return rejected(
                SshAuthenticationApiError.INCOMPATIBLE_API_VERSIONS,
                "Supported API version: ${SshAuthenticationApi.API_VERSION}.",
            )
        }
        if (action !in SUPPORTED_ACTIONS) {
            return rejected(
                SshAuthenticationApiError.UNKNOWN_ACTION,
                "Unsupported SSH Authentication action.",
            )
        }

        val sanitized = Intent(action).apply {
            putExtra(SshAuthenticationApi.EXTRA_API_VERSION, version)
        }
        val digestParts = mutableListOf("version=$version")
        val keyId = if (action == SshAuthenticationApi.ACTION_SELECT_KEY) {
            null
        } else {
            request.getStringExtra(SshAuthenticationApi.EXTRA_KEY_ID)
                ?.takeIf { it.isNotBlank() && it.length <= MAX_KEY_ID_LENGTH }
                ?: return rejected(
                    SshAuthenticationApiError.NO_KEY_ID,
                    "A valid key ID is required.",
                )
        }
        keyId?.let {
            sanitized.putExtra(SshAuthenticationApi.EXTRA_KEY_ID, it)
            digestParts += "key_id=$it"
        }

        var challenge: ByteArray? = null
        var hashAlgorithm: Int? = null
        if (action == SshAuthenticationApi.ACTION_SIGN) {
            challenge = request.getByteArrayExtra(SshAuthenticationApi.EXTRA_CHALLENGE)
                ?.takeIf { it.isNotEmpty() && it.size <= MAX_CHALLENGE_BYTES }
                ?: return rejected(
                    SshAuthenticationApiError.GENERIC_ERROR,
                    "A challenge of at most $MAX_CHALLENGE_BYTES bytes is required.",
                )
            hashAlgorithm = request.getIntExtra(
                SshAuthenticationApi.EXTRA_HASH_ALGORITHM,
                Int.MIN_VALUE,
            )
            if (hashAlgorithm !in SSH_AUTHENTICATION_API_HASH_ALGORITHMS) {
                return rejected(
                    SshAuthenticationApiError.INVALID_HASH_ALGORITHM,
                    "A known SSH Authentication API hash algorithm is required.",
                )
            }
            sanitized.putExtra(SshAuthenticationApi.EXTRA_CHALLENGE, challenge)
            sanitized.putExtra(SshAuthenticationApi.EXTRA_HASH_ALGORITHM, hashAlgorithm)
            digestParts += "hash=$hashAlgorithm"
            digestParts += "challenge_size=${challenge.size}"
            digestParts += "challenge_sha256=${androidIpcByteDigest(challenge)}"
        }
        val requestDigest = androidIpcRequestDigest(action, digestParts)
        val admission = admitAndroidIpcCaller(
            context = this,
            registrationRepository = registrationRepository,
            caller = caller,
            protocol = PROTOCOL_SSH,
            action = action,
            requestDigest = requestDigest,
            token = request.getStringExtra(
                AndroidIpcApprovalCoordinator.AUTHORIZATION_EXTRA,
            ),
            sessionIdentity = androidIpcSessionIdentity(
                getVaultSession.valueOrNull,
            ),
        )
        val authorization = when (admission) {
            is AndroidIpcAdmission.InvalidToken -> return rejected(
                SshAuthenticationApiError.NO_AUTH_KEY,
                ANDROID_IPC_INVALID_TOKEN_MESSAGE,
            )

            is AndroidIpcAdmission.SignerMismatch -> return rejected(
                SshAuthenticationApiError.NO_AUTH_KEY,
                ANDROID_IPC_SIGNER_MISMATCH_MESSAGE,
            )

            is AndroidIpcAdmission.NeedsRegistration -> return AdmittedRequest.Rejected(
                approvalRequiredResult(
                    caller = caller,
                    action = action,
                    operation = operationName(action),
                    requestDigest = requestDigest,
                    sanitizedRequest = sanitized,
                    requestedKeyId = keyId,
                    registerApp = true,
                    requiresAuthentication = action == SshAuthenticationApi.ACTION_SIGN,
                ),
            )

            is AndroidIpcAdmission.Admitted -> admission.authorization
        }
        return AdmittedRequest.Admitted(
            action = action,
            sanitized = sanitized,
            requestDigest = requestDigest,
            keyId = keyId,
            challenge = challenge,
            hashAlgorithm = hashAlgorithm,
            authorization = authorization,
        )
    }

    private suspend fun dispatchAction(
        caller: AndroidIpcCaller,
        admitted: AdmittedRequest.Admitted,
        approvedId: String,
        publicKey: SshAgentPublicKeyRow,
        decodedPublicKey: NativeSshPublicKey,
    ): Intent = when (admitted.action) {
        SshAuthenticationApi.ACTION_SELECT_KEY -> {
            recordListKeysUsage(publicKey, caller)
            KeySelectionResponse(
                publicKey.cipherId,
                publicKey.name ?: publicKey.fingerprint,
            ).toIntent()
        }

        SshAuthenticationApi.ACTION_GET_SSH_PUBLIC_KEY -> {
            // The stored row is already the canonical "<type> <base64>" form
            // produced by the public key syncer, and the shared decode above
            // has validated it.
            recordListKeysUsage(publicKey, caller)
            SshPublicKeyResponse(publicKey.publicKey).toIntent()
        }

        SshAuthenticationApi.ACTION_GET_PUBLIC_KEY -> {
            recordListKeysUsage(publicKey, caller)
            PublicKeyResponse(
                decodedPublicKey.spkiDer,
                decodedPublicKey.toSshAuthenticationApiAlgorithm(),
            ).toIntent()
        }

        SshAuthenticationApi.ACTION_SIGN -> sign(
            caller = caller,
            admitted = admitted,
            approvedId = approvedId,
            publicKey = publicKey,
            decodedPublicKey = decodedPublicKey,
        )

        else -> sshError(
            SshAuthenticationApiError.UNKNOWN_ACTION,
            "Unsupported SSH Authentication action.",
        )
    }

    @Suppress("LongMethod", "ReturnCount")
    private suspend fun sign(
        caller: AndroidIpcCaller,
        admitted: AdmittedRequest.Admitted,
        approvedId: String,
        publicKey: SshAgentPublicKeyRow,
        decodedPublicKey: NativeSshPublicKey,
    ): Intent {
        val flags = sshAgentSignatureFlags(
            keyType = decodedPublicKey.algorithmName,
            hashAlgorithm = requireNotNull(admitted.hashAlgorithm),
        ) ?: return sshError(
            SshAuthenticationApiError.INVALID_HASH_ALGORITHM,
            "The selected key does not support the requested hash algorithm.",
        )
        val session = getVaultSession.valueOrNull as? MasterSession.Key
        val privateAuthorized =
            session?.origin is MasterSession.Key.Authenticated ||
                    admitted.authorization?.requiresAuthentication == true
        if (!privateAuthorized) {
            return approvalRequiredResult(
                caller = caller,
                action = admitted.action,
                operation = operationName(admitted.action),
                requestDigest = admitted.requestDigest,
                sanitizedRequest = admitted.sanitized,
                requestedKeyId = approvedId,
                registerApp = false,
                requiresAuthentication = true,
            )
        }
        val vault = vaultLoader.load()
            ?: return sshError(
                SshAuthenticationApiError.NO_AUTH_KEY,
                "Unlock Keyguard and retry the request.",
            )
        val sshKey = vault.keys
            .singleOrNull { it.id == approvedId }
            ?.sshKey
            ?: return sshError(
                SshAuthenticationApiError.NO_SUCH_KEY,
                "The private key is unavailable or ambiguous.",
            )
        val privateKey = sshKey.privateKey
            ?.takeIf { it.isNotBlank() }
            ?: return sshError(
                SshAuthenticationApiError.NO_AUTH_KEY,
                "The approved key has no private key material.",
            )
        val signature = NativeCrypto.ssh.sign(
            privateKeyPem = privateKey,
            publicKeyOpenSsh = publicKey.publicKey,
            data = requireNotNull(admitted.challenge),
            flags = flags,
        )
        recordUsage(
            key = publicKey,
            caller = caller,
            type = SshUsageHistoryRequestType.AGENT_SIGN_DATA,
            response = SshUsageHistoryResponseType.SUCCESS,
        )
        return SigningResponse(
            NativeCrypto.ssh.frameSignature(signature),
        ).toIntent()
    }

    private suspend fun recordListKeysUsage(
        publicKey: SshAgentPublicKeyRow,
        caller: AndroidIpcCaller,
    ) = recordUsage(
        key = publicKey,
        caller = caller,
        type = SshUsageHistoryRequestType.AGENT_LIST_KEYS,
        response = SshUsageHistoryResponseType.SUCCESS,
    )

    private fun rejected(
        errorCode: Int,
        message: String,
    ): AdmittedRequest.Rejected = AdmittedRequest.Rejected(
        sshError(errorCode, message),
    )

    @Suppress("LongParameterList")
    private fun approvalRequiredResult(
        caller: AndroidIpcCaller,
        action: String,
        operation: StringResource,
        requestDigest: String,
        sanitizedRequest: Intent,
        requestedKeyId: String?,
        registerApp: Boolean,
        requiresAuthentication: Boolean,
    ): Intent = requestApproval(
        caller = caller,
        action = action,
        operation = operation,
        requestDigest = requestDigest,
        sanitizedRequest = sanitizedRequest,
        requestedKeyId = requestedKeyId,
        registerApp = registerApp,
        requiresAuthentication = requiresAuthentication,
    )
        ?.let { pendingIntent -> sshInteractionRequired(action, pendingIntent) }
        ?: sshError(
            SshAuthenticationApiError.INTERNAL_ERROR,
            ANDROID_IPC_TOO_MANY_APPROVALS_MESSAGE,
        )

    private fun requestApproval(
        caller: AndroidIpcCaller,
        action: String,
        operation: StringResource,
        requestDigest: String,
        sanitizedRequest: Intent,
        requestedKeyId: String?,
        registerApp: Boolean,
        requiresAuthentication: Boolean,
    ): PendingIntent? {
        val approvalContext = applicationContext
        val approvalRegistrationRepository = registrationRepository
        val approvalSession = getVaultSession
        val approvalPublicKeyRepository = publicKeyRepository
        return AndroidIpcApprovalCoordinator.createPendingIntent(
            context = approvalContext,
            request = AndroidIpcApprovalCoordinator.Request(
                caller = caller,
                protocol = PROTOCOL_SSH,
                protocolLabel = Res.string.ipc_protocol_ssh,
                action = action,
                operation = operation,
                requestDigest = requestDigest,
                retryIntent = sanitizedRequest,
                allowMultiple = false,
                registerApp = registerApp,
                requiresAuthentication = requiresAuthentication,
                validateCaller = {
                    isCurrentAndroidIpcCaller(approvalContext, caller)
                },
                registerCaller = {
                    approvalRegistrationRepository.register(caller)
                },
                sessionIdentity = {
                    androidIpcSessionIdentity(approvalSession.valueOrNull)
                },
                loadCandidates = {
                    approvalPublicKeyRepository.get()
                        .bind()
                        .asSequence()
                        .filter {
                            requestedKeyId == null || it.cipherId == requestedKeyId
                        }
                        .mapNotNull { key ->
                            if (
                                runCatching {
                                    NativeCrypto.ssh.decodePublicKey(key.publicKey)
                                }.isFailure
                            ) {
                                return@mapNotNull null
                            }
                            if (
                                action == SshAuthenticationApi.ACTION_SIGN &&
                                !key.canSign
                            ) {
                                return@mapNotNull null
                            }
                            AndroidIpcApprovalCoordinator.Candidate(
                                id = key.cipherId,
                                name = key.name ?: key.fingerprint,
                                description = key.fingerprint,
                            )
                        }
                        .toList()
                },
            ),
        )
    }

    private suspend fun recordUsage(
        key: SshAgentPublicKeyRow,
        caller: AndroidIpcCaller,
        type: SshUsageHistoryRequestType,
        response: SshUsageHistoryResponseType,
    ) {
        val session = getVaultSession.valueOrNull as? MasterSession.Key
        val addHistory = session
            ?.di
            ?.direct
            ?.instanceOrNull<AddSshUsageHistory>()
        recordAndroidIpcUsage(
            directRecorder = addHistory,
            historyQueue = historyQueue,
            protocol = PendingUsageHistory.Protocol.SSH,
            caller = caller,
            json = json,
            requestType = type.name,
            responseType = response.name,
            cipherId = key.cipherId,
            fingerprint = key.fingerprint,
            keygrip = null,
        ) { recorder, event ->
            recorder(
                AddSshUsageHistoryRequest(
                    cipherId = key.cipherId,
                    sessionId = ANDROID_IPC_HISTORY_SESSION_ID,
                    caller = event.caller,
                    request = type,
                    response = response,
                    fingerprint = key.fingerprint,
                    instant = Instant.fromEpochMilliseconds(event.timestampEpochMilliseconds),
                    eventId = event.id,
                ),
            ).bind()
        }
    }
}

private fun operationName(action: String): StringResource = when (action) {
    SshAuthenticationApi.ACTION_SELECT_KEY -> Res.string.ipc_operation_ssh_select_key
    SshAuthenticationApi.ACTION_GET_PUBLIC_KEY -> Res.string.ipc_operation_ssh_get_public_key
    SshAuthenticationApi.ACTION_GET_SSH_PUBLIC_KEY ->
        Res.string.ipc_operation_ssh_get_ssh_public_key

    SshAuthenticationApi.ACTION_SIGN -> Res.string.ipc_operation_ssh_sign
    else -> Res.string.ipc_operation_ssh_other
}

private fun sshInteractionRequired(
    action: String,
    pendingIntent: PendingIntent,
): Intent = when (action) {
    SshAuthenticationApi.ACTION_SELECT_KEY -> KeySelectionResponse(pendingIntent).toIntent()
    SshAuthenticationApi.ACTION_GET_PUBLIC_KEY -> PublicKeyResponse(pendingIntent).toIntent()
    SshAuthenticationApi.ACTION_GET_SSH_PUBLIC_KEY -> SshPublicKeyResponse(pendingIntent).toIntent()
    SshAuthenticationApi.ACTION_SIGN -> SigningResponse(pendingIntent).toIntent()
    else -> sshError(
        SshAuthenticationApiError.UNKNOWN_ACTION,
        "Unsupported SSH Authentication action.",
    )
}

private fun sshError(
    errorCode: Int,
    message: String,
): Intent {
    val error = SshAuthenticationApiError(errorCode, message)
    return Intent().apply {
        putExtra(
            SshAuthenticationApi.EXTRA_RESULT_CODE,
            SshAuthenticationApi.RESULT_CODE_ERROR,
        )
        putExtra(SshAuthenticationApi.EXTRA_ERROR, error)
    }
}
