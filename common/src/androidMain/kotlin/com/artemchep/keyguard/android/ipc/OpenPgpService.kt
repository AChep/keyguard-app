package com.artemchep.keyguard.android.ipc

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.AddGpgUsageHistoryRequest
import com.artemchep.keyguard.common.model.GpgUsageHistoryRequestType
import com.artemchep.keyguard.common.model.GpgUsageHistoryResponseType
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.service.gpgagent.GpgPublicKeyRepository
import com.artemchep.keyguard.common.service.androidipc.GpgOpenPgpVaultLoader
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpExportSelection
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpOperationKind
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpReadFileResult
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpRecipientSelection
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpRing
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpRingOperations
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpService
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpSignerSelection
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpVault
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyParser
import com.artemchep.keyguard.common.service.crypto.OpenPgpRecipientResolution
import com.artemchep.keyguard.common.service.crypto.gpgOpenPgpApprovalCandidates
import com.artemchep.keyguard.common.service.crypto.hasOpenPgpKeyIdCollision
import com.artemchep.keyguard.common.service.crypto.openPgpRecipientLookupLogMessage
import com.artemchep.keyguard.common.service.crypto.resolveGpgOpenPgpAutomaticSelection
import com.artemchep.keyguard.common.service.crypto.resolveOpenPgpRecipients
import com.artemchep.keyguard.common.service.crypto.selectGpgOpenPgpEncryptionRecipients
import com.artemchep.keyguard.common.service.crypto.selectGpgOpenPgpExportKey
import com.artemchep.keyguard.common.service.crypto.selectGpgOpenPgpSigner
import com.artemchep.keyguard.common.service.crypto.selectedRingsCoverOpenPgpRecipients
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.logging.postDebug
import com.artemchep.keyguard.common.service.pendinghistory.PendingUsageHistory
import com.artemchep.keyguard.common.service.pendinghistory.PendingUsageHistoryQueue
import com.artemchep.keyguard.common.usecase.AddGpgUsageHistory
import com.artemchep.keyguard.common.usecase.GetGpgAgentFilter
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.nativecrypto.NativeCryptoErrorCode
import com.artemchep.keyguard.nativecrypto.NativeCryptoException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.io.Sink
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.io.discardingSink
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.ipc_protocol_openpgp
import org.jetbrains.compose.resources.StringResource
import org.kodein.di.DIAware
import org.kodein.di.android.closestDI
import org.kodein.di.direct
import org.kodein.di.instance
import org.kodein.di.instanceOrNull
import org.openintents.openpgp.IOpenPgpService2
import org.openintents.openpgp.OpenPgpError
import org.openintents.openpgp.OpenPgpMetadata
import org.openintents.openpgp.util.OpenPgpApi
import kotlin.getValue
import kotlin.time.Instant

@Suppress("LargeClass", "TooManyFunctions")
class OpenPgpService : Service(), DIAware {
    override val di by closestDI { this }

    private val getGpgAgentFilter by instance<GetGpgAgentFilter>()
    private val getVaultSession by instance<GetVaultSession>()
    private val openPgpService by instance<GpgOpenPgpService>()
    private val registrationRepository by instance<AndroidIpcRegistrationRepository>()
    private val publicKeyRepository by instance<GpgPublicKeyRepository>()
    private val historyQueue by instance<PendingUsageHistoryQueue>()
    private val json by instance<Json>()
    private val logRepository by instance<LogRepository>()
    private val publicKeyParser by instance<GpgPublicKeyParser>()
    private val outputPipes = OpenPgpOutputPipeRegistry()

    private val vaultLoader by lazy {
        GpgOpenPgpVaultLoader(
            getVaultSession = getVaultSession,
            getGpgAgentFilter = getGpgAgentFilter,
            publicKeyParser = publicKeyParser,
            publicKeyRepository = publicKeyRepository,
            logRepository = logRepository,
        )
    }

    private val ringOperations by lazy {
        GpgOpenPgpRingOperations(openPgpService)
    }

    private val binder = object : IOpenPgpService2.Stub() {
        override fun createOutputPipe(pipeId: Int): ParcelFileDescriptor? {
            val caller = captureAndroidIpcCaller(this@OpenPgpService)
                ?: return null
            // Binder threads exist to block; awaiting the gate here keeps a
            // pipe from being handed out for a provider that is being turned
            // off, and costs nothing once the preference has been read.
            val enabled = runBlocking {
                AndroidIpcProviderGate.isOpenPgpEnabled()
            }
            return caller
                .takeIf { enabled }
                ?.let { outputPipes.create(it, pipeId) }
        }

        @Suppress("TooGenericExceptionCaught")
        override fun execute(
            request: Intent?,
            input: ParcelFileDescriptor?,
            outputPipeId: Int,
        ): Intent {
            val callingUid = Binder.getCallingUid()
            val callingPid = Binder.getCallingPid()
            val caller = captureAndroidIpcCaller(this@OpenPgpService)
                ?: run {
                    outputPipes.discard(
                        uid = callingUid,
                        pid = callingPid,
                        pipeId = outputPipeId,
                    )
                    input.closeQuietly()
                    return openPgpError(
                        OpenPgpError.GENERIC_ERROR,
                        "The calling application could not be attributed uniquely.",
                    )
                }
            val output = if (outputPipeId > 0) {
                outputPipes.take(caller, outputPipeId)
            } else {
                null
            }
            return try {
                runBlocking(Dispatchers.IO) {
                    executeInternal(
                        caller = caller,
                        request = request,
                        input = input,
                        output = output,
                        outputPipeId = outputPipeId,
                    )
                }
            } catch (e: Exception) {
                logRepository.postDebug(TAG) {
                    "request=unhandled caller=${caller.packageName} " +
                            "action=${request?.action?.substringAfterLast('.')} " +
                            "failure=${e::class.simpleName}"
                }
                openPgpError(
                    OpenPgpError.GENERIC_ERROR,
                    e.message ?: "The OpenPGP operation failed.",
                )
            } finally {
                input.closeQuietly()
                output.closeQuietly()
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
        .takeIf { intent?.action == OpenPgpApi.SERVICE_INTENT_2 }

    override fun onDestroy() {
        outputPipes.close()
        super.onDestroy()
    }

    /**
     * Outcome of the request prologue: shape validation, extras
     * normalization, and caller admission.
     */
    private sealed interface AdmittedRequest {
        class Rejected(val result: Intent) : AdmittedRequest

        class Admitted(
            val action: String,
            val kind: GpgOpenPgpOperationKind,
            val outputPolicy: OpenPgpOutputPolicy,
            val normalized: NormalizedOpenPgpRequest,
            val requestDigest: String,
            val requestReference: String,
            val authorization: AndroidIpcApprovalCoordinator.Authorization?,
        ) : AdmittedRequest
    }

    /** Keys the caller may operate on, once approval and selection settled. */
    private sealed interface KeySelection {
        class Rejected(val result: Intent) : KeySelection

        class Resolved(
            val vault: GpgOpenPgpVault,
            val selectedRings: List<GpgOpenPgpRing>,
            val privateAuthorized: Boolean,
        ) : KeySelection
    }

    @Suppress("ReturnCount")
    private suspend fun executeInternal(
        caller: AndroidIpcCaller,
        request: Intent?,
        input: ParcelFileDescriptor?,
        output: ParcelFileDescriptor?,
        outputPipeId: Int,
    ): Intent {
        val admitted = when (
            val admission = admitRequest(
                caller = caller,
                request = request,
                input = input,
                output = output,
                outputPipeId = outputPipeId,
            )
        ) {
            is AdmittedRequest.Rejected -> return admission.result
            is AdmittedRequest.Admitted -> admission
        }
        if (admitted.kind == GpgOpenPgpOperationKind.CHECK_PERMISSION) {
            return openPgpSuccess()
        }

        val publicVault = vaultLoader.load(admitted.requestReference)
        val recipientResolution = resolveRecipients(
            kind = admitted.kind,
            normalized = admitted.normalized,
            vault = publicVault,
            requestReference = admitted.requestReference,
        )
        val approvedKeyIds = admitted.authorization
            ?.approvedKeyIds
            .orEmpty()
        if (admitted.kind == GpgOpenPgpOperationKind.AUTOCRYPT_STATUS) {
            return queryAutocryptStatus(
                vault = publicVault,
                normalized = admitted.normalized,
                resolution = requireNotNull(recipientResolution),
                approvedKeyIds = approvedKeyIds,
                caller = caller,
                requestDigest = admitted.requestDigest,
                requestReference = admitted.requestReference,
            )
        }

        val selection = selectKeys(
            caller = caller,
            admitted = admitted,
            publicVault = publicVault,
            recipientResolution = recipientResolution,
            approvedKeyIds = approvedKeyIds,
        )
        val resolved = when (selection) {
            is KeySelection.Rejected -> return selection.result
            is KeySelection.Resolved -> selection
        }
        return dispatchAction(
            caller = caller,
            admitted = admitted,
            resolved = resolved,
            input = input,
            output = output,
        )
    }

    /**
     * Validates the request shape, normalizes its extras, and runs the
     * shared caller admission gate.
     */
    @Suppress("LongMethod", "ReturnCount")
    private suspend fun admitRequest(
        caller: AndroidIpcCaller,
        request: Intent?,
        input: ParcelFileDescriptor?,
        output: ParcelFileDescriptor?,
        outputPipeId: Int,
    ): AdmittedRequest {
        if (!AndroidIpcProviderGate.isOpenPgpEnabled()) {
            return rejected(
                OpenPgpError.GENERIC_ERROR,
                "The OpenPGP provider is disabled.",
            )
        }
        val action = request?.action
            ?: return rejected(
                OpenPgpError.GENERIC_ERROR,
                "A supported action is required.",
            )
        val apiVersion = request.getIntExtra(
            OpenPgpApi.EXTRA_API_VERSION,
            Int.MIN_VALUE,
        )
        validateRequestShape(
            action = action,
            apiVersion = apiVersion,
            input = input,
            output = output,
            outputPipeId = outputPipeId,
        )?.let { rejection -> return AdmittedRequest.Rejected(rejection) }
        val kind = openPgpOperationKind(action)

        val normalized = normalizeRequest(request, action, apiVersion)
            ?: return rejected(
                OpenPgpError.GENERIC_ERROR,
                "The request contains invalid or unsupported extras.",
            )
        if (!hasValidOpenPgpActionExtras(action, normalized)) {
            return rejected(
                OpenPgpError.GENERIC_ERROR,
                "The request is missing required extras or contains extras for another action.",
            )
        }
        val requestDigest = androidIpcRequestDigest(action, normalized.extras.digestParts)
        val requestReference = requestDigest.take(REQUEST_REFERENCE_LENGTH)
        logRepository.postDebug(TAG) {
            "request=$requestReference caller=${caller.packageName} " +
                    "action=${action.substringAfterLast('.')} api=$apiVersion " +
                    "recipients=${normalized.extras.userIds.size} " +
                    "direct_keys=${normalized.extras.keyIds.size}"
        }
        val admission = admitAndroidIpcCaller(
            context = this,
            registrationRepository = registrationRepository,
            caller = caller,
            protocol = PROTOCOL_OPENPGP,
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
                OpenPgpError.GENERIC_ERROR,
                ANDROID_IPC_INVALID_TOKEN_MESSAGE,
            )

            is AndroidIpcAdmission.SignerMismatch -> return rejected(
                OpenPgpError.GENERIC_ERROR,
                ANDROID_IPC_SIGNER_MISMATCH_MESSAGE,
            )

            is AndroidIpcAdmission.NeedsRegistration -> return AdmittedRequest.Rejected(
                approvalRequiredResult(
                    caller = caller,
                    action = action,
                    operation = openPgpOperationName(action),
                    normalized = normalized,
                    requestDigest = requestDigest,
                    registerApp = true,
                    requiresAuthentication = kind.requiresPrivateKeyAuthorization,
                ),
            )

            is AndroidIpcAdmission.Admitted -> admission.authorization
        }
        return AdmittedRequest.Admitted(
            action = action,
            kind = kind,
            outputPolicy = openPgpOutputPolicy(kind),
            normalized = normalized,
            requestDigest = requestDigest,
            requestReference = requestReference,
            authorization = authorization,
        )
    }

    /** Returns an error result when the action's streams are not usable. */
    @Suppress("ReturnCount")
    private fun validateRequestShape(
        action: String,
        apiVersion: Int,
        input: ParcelFileDescriptor?,
        output: ParcelFileDescriptor?,
        outputPipeId: Int,
    ): Intent? {
        if (!isSupportedOpenPgpApiVersion(apiVersion)) {
            return openPgpError(
                OpenPgpError.INCOMPATIBLE_API_VERSIONS,
                "Supported OpenPGP API versions: $MIN_API_VERSION–$MAX_API_VERSION.",
            )
        }
        if (action !in SUPPORTED_ACTIONS) {
            return openPgpError(
                OpenPgpError.GENERIC_ERROR,
                "Unsupported OpenPGP action.",
            )
        }
        val kind = openPgpOperationKind(action)
        val outputPolicy = openPgpOutputPolicy(kind)
        val requiresOutput = outputPolicy == OpenPgpOutputPolicy.REQUIRED
        return when {
            kind.consumesInputStream && input == null -> openPgpError(
                OpenPgpError.GENERIC_ERROR,
                "This action requires a fresh input stream.",
            )

            requiresOutput && (outputPipeId <= 0 || output == null) -> openPgpError(
                OpenPgpError.GENERIC_ERROR,
                "This action requires a valid output pipe.",
            )

            !requiresOutput && outputPipeId > 0 && output == null -> openPgpError(
                OpenPgpError.GENERIC_ERROR,
                "The output pipe is not owned by this caller or has expired.",
            )

            else -> null
        }
    }

    private fun resolveRecipients(
        kind: GpgOpenPgpOperationKind,
        normalized: NormalizedOpenPgpRequest,
        vault: GpgOpenPgpVault,
        requestReference: String,
    ): OpenPgpRecipientResolution<GpgOpenPgpRing>? {
        if (!kind.usesRecipientLookup) {
            return null
        }
        return resolveOpenPgpRecipients(
            userIds = normalized.extras.userIds,
            keyIds = normalized.extras.keyIds.toList(),
            candidates = vault.rings,
            candidateEmails = { it.info.emails },
            candidateKeyIds = GpgOpenPgpRing::allKeyIds,
            canEncrypt = GpgOpenPgpRing::canEncrypt,
        ).also { resolution ->
            resolution.details.forEach { detail ->
                logRepository.postDebug(TAG) {
                    openPgpRecipientLookupLogMessage(
                        requestReference = requestReference,
                        detail = detail,
                    )
                }
            }
        }
    }

    /**
     * Turns the approved or automatically selected key IDs into rings,
     * re-authorizing private-key access and re-checking that the selection
     * is still unambiguous against the current vault.
     */
    @Suppress("LongMethod", "ReturnCount")
    private suspend fun selectKeys(
        caller: AndroidIpcCaller,
        admitted: AdmittedRequest.Admitted,
        publicVault: GpgOpenPgpVault,
        recipientResolution: OpenPgpRecipientResolution<GpgOpenPgpRing>?,
        approvedKeyIds: Set<String>,
    ): KeySelection {
        val action = admitted.action
        val automaticallySelected = resolveGpgOpenPgpAutomaticSelection(
            kind = admitted.kind,
            vault = publicVault,
            signKeyId = admitted.normalized.extras.signKeyId,
            exportKeyId = admitted.normalized.extras.keyId,
            recipientResolution = recipientResolution,
        )
        if (approvedKeyIds.isEmpty() && automaticallySelected == null) {
            return KeySelection.Rejected(
                approvalRequiredResult(
                    caller = caller,
                    action = action,
                    operation = openPgpOperationName(action),
                    normalized = admitted.normalized,
                    requestDigest = admitted.requestDigest,
                    registerApp = false,
                    requiresAuthentication = false,
                ),
            )
        }
        val selectedCipherIds = approvedKeyIds.ifEmpty {
            automaticallySelected
                .orEmpty()
                .map(GpgOpenPgpRing::cipherId)
                .toSet()
        }
        val session = getVaultSession.valueOrNull as? MasterSession.Key
        val privateAuthorized =
            session?.origin is MasterSession.Key.Authenticated ||
                    admitted.authorization?.requiresAuthentication == true
        if (admitted.kind.requiresPrivateKeyAuthorization && !privateAuthorized) {
            return KeySelection.Rejected(
                approvalRequiredResult(
                    caller = caller,
                    action = action,
                    operation = openPgpOperationName(action),
                    normalized = admitted.normalized,
                    requestDigest = admitted.requestDigest,
                    registerApp = false,
                    requiresAuthentication = true,
                ),
            )
        }
        val vault = if (privateAuthorized) {
            vaultLoader.withPrivateKeys(publicVault)
        } else {
            publicVault
        }
        val selectedRings = vault.rings
            .filter { it.cipherId in selectedCipherIds }
        if (
            selectedRings.size != selectedCipherIds.size ||
            (selectedRings.isEmpty() && !admitted.kind.allowsEmptyKeySelection)
        ) {
            return KeySelection.Rejected(
                openPgpError(
                    OpenPgpError.GENERIC_ERROR,
                    "An approved key is no longer available.",
                ),
            )
        }
        if (
            hasOpenPgpKeyIdCollision(
                selected = selectedRings,
                candidates = vault.rings,
                candidateKeyIds = GpgOpenPgpRing::allKeyIds,
            )
        ) {
            return KeySelection.Rejected(
                openPgpError(
                    OpenPgpError.GENERIC_ERROR,
                    "An approved OpenPGP key ID collides with another stored key.",
                ),
            )
        }
        return KeySelection.Resolved(
            vault = vault,
            selectedRings = selectedRings,
            privateAuthorized = privateAuthorized,
        )
    }

    @Suppress("LongMethod")
    private suspend fun dispatchAction(
        caller: AndroidIpcCaller,
        admitted: AdmittedRequest.Admitted,
        resolved: KeySelection.Resolved,
        input: ParcelFileDescriptor?,
        output: ParcelFileDescriptor?,
    ): Intent {
        val vault = resolved.vault
        val selectedRings = resolved.selectedRings
        val normalized = admitted.normalized
        return when (val kind = admitted.kind) {
            GpgOpenPgpOperationKind.GET_SIGN_KEY_ID -> getSignKeyId(
                vault = vault,
                selectedRings = selectedRings,
                normalized = normalized,
                caller = caller,
            )

            GpgOpenPgpOperationKind.GET_KEY_IDS -> getKeyIds(
                vault = vault,
                selectedRings = selectedRings,
                caller = caller,
            )

            GpgOpenPgpOperationKind.GET_KEY -> exportKey(
                vault = vault,
                selectedRings = selectedRings,
                normalized = normalized,
                caller = caller,
                output = requireNotNull(output),
            )

            GpgOpenPgpOperationKind.CLEAR_SIGN -> clearSign(
                vault = vault,
                selectedRings = selectedRings,
                caller = caller,
                input = requireNotNull(input),
                output = requireNotNull(output),
            )

            GpgOpenPgpOperationKind.DETACHED_SIGN -> detachedSign(
                vault = vault,
                selectedRings = selectedRings,
                normalized = normalized,
                caller = caller,
                input = requireNotNull(input),
            )

            GpgOpenPgpOperationKind.ENCRYPT,
            GpgOpenPgpOperationKind.SIGN_AND_ENCRYPT,
            -> encrypt(
                vault = vault,
                selectedRings = selectedRings,
                normalized = normalized,
                caller = caller,
                input = requireNotNull(input),
                output = requireNotNull(output),
                sign = kind == GpgOpenPgpOperationKind.SIGN_AND_ENCRYPT,
            )

            GpgOpenPgpOperationKind.DECRYPT_VERIFY,
            GpgOpenPgpOperationKind.DECRYPT_METADATA,
            -> decryptVerifyOrRequestAuthentication(
                caller = caller,
                admitted = admitted,
                resolved = resolved,
                input = requireNotNull(input),
                output = output,
            )

            // Both return from executeInternal before key selection.
            GpgOpenPgpOperationKind.CHECK_PERMISSION,
            GpgOpenPgpOperationKind.AUTOCRYPT_STATUS,
            -> openPgpError(
                OpenPgpError.GENERIC_ERROR,
                "Unsupported OpenPGP action.",
            )
        }
    }

    /**
     * A locked vault cannot tell whether a message is addressed to one of
     * its keys until it tries, so a missing-key failure is turned into an
     * authentication prompt rather than an error.
     */
    private suspend fun decryptVerifyOrRequestAuthentication(
        caller: AndroidIpcCaller,
        admitted: AdmittedRequest.Admitted,
        resolved: KeySelection.Resolved,
        input: ParcelFileDescriptor,
        output: ParcelFileDescriptor?,
    ): Intent = try {
        decryptVerify(
            vault = resolved.vault,
            selectedRings = resolved.selectedRings,
            normalized = admitted.normalized,
            caller = caller,
            input = input,
            output = output,
            outputPolicy = admitted.outputPolicy,
        )
    } catch (e: NativeCryptoException) {
        if (e.code == NativeCryptoErrorCode.NO_USABLE_KEY && !resolved.privateAuthorized) {
            approvalRequiredResult(
                caller = caller,
                action = admitted.action,
                operation = openPgpOperationName(admitted.action),
                normalized = admitted.normalized,
                requestDigest = admitted.requestDigest,
                registerApp = false,
                requiresAuthentication = true,
            )
        } else {
            throw e
        }
    }

    private fun rejected(
        errorId: Int,
        message: String,
    ): AdmittedRequest.Rejected = AdmittedRequest.Rejected(
        openPgpError(errorId, message),
    )

    @Suppress("LongMethod", "ReturnCount")
    private suspend fun queryAutocryptStatus(
        vault: GpgOpenPgpVault,
        normalized: NormalizedOpenPgpRequest,
        resolution: OpenPgpRecipientResolution<GpgOpenPgpRing>,
        approvedKeyIds: Set<String>,
        caller: AndroidIpcCaller,
        requestDigest: String,
        requestReference: String,
    ): Intent {
        val approvedRings = if (approvedKeyIds.isNotEmpty()) {
            vault.rings.filter { it.cipherId in approvedKeyIds }
        } else {
            emptyList()
        }
        if (
            approvedKeyIds.isNotEmpty() &&
            (
                approvedRings.size != approvedKeyIds.size ||
                        !selectedRingsCoverOpenPgpRecipients(
                            userIds = normalized.extras.userIds,
                            keyIds = normalized.extras.keyIds.toList(),
                            selected = approvedRings,
                            candidateEmails = { it.info.emails },
                            candidateKeyIds = GpgOpenPgpRing::allKeyIds,
                            canEncrypt = GpgOpenPgpRing::canEncrypt,
                        )
                )
        ) {
            logRepository.postDebug(TAG) {
                "request=$requestReference autocrypt=invalid_approval"
            }
            return openPgpError(
                OpenPgpError.GENERIC_ERROR,
                "The approved recipient selection no longer satisfies the request.",
            )
        }

        val resolvedRings = approvedRings.ifEmpty {
            resolution.selected.orEmpty()
        }
        if (resolvedRings.isNotEmpty()) {
            recordUsage(
                vault = vault,
                rings = resolvedRings,
                caller = caller,
                type = GpgUsageHistoryRequestType.AGENT_LIST_KEYS,
                response = GpgUsageHistoryResponseType.SUCCESS,
            )
            logRepository.postDebug(TAG) {
                "request=$requestReference autocrypt=discourage " +
                        "resolved_rings=${resolvedRings.distinct().size} confirmed=false"
            }
            return openPgpAutocryptResult(
                status = OpenPgpApi.AUTOCRYPT_STATUS_DISCOURAGE,
            )
        }

        if (resolution.isAmbiguousOnly) {
            val pendingIntent = createApproval(
                caller = caller,
                action = OpenPgpApi.ACTION_QUERY_AUTOCRYPT_STATUS,
                operation = openPgpOperationName(
                    OpenPgpApi.ACTION_QUERY_AUTOCRYPT_STATUS,
                ),
                normalized = normalized,
                requestDigest = requestDigest,
                registerApp = false,
                requiresAuthentication = false,
                allowTokenlessRetry = true,
            ) ?: return openPgpError(
                OpenPgpError.GENERIC_ERROR,
                ANDROID_IPC_TOO_MANY_APPROVALS_MESSAGE,
            )
            logRepository.postDebug(TAG) {
                "request=$requestReference autocrypt=discourage " +
                        "selection_required=true confirmed=false"
            }
            return openPgpAutocryptResult(
                status = OpenPgpApi.AUTOCRYPT_STATUS_DISCOURAGE,
                pendingIntent = pendingIntent,
            )
        }

        logRepository.postDebug(TAG) {
            "request=$requestReference autocrypt=unavailable confirmed=false"
        }
        return openPgpAutocryptResult(
            status = OpenPgpApi.AUTOCRYPT_STATUS_UNAVAILABLE,
        )
    }

    private fun createApproval(
        caller: AndroidIpcCaller,
        action: String,
        operation: StringResource,
        normalized: NormalizedOpenPgpRequest,
        requestDigest: String,
        registerApp: Boolean,
        requiresAuthentication: Boolean,
        allowTokenlessRetry: Boolean = false,
    ): PendingIntent? {
        val kind = openPgpOperationKind(action)
        val approvalContext = applicationContext
        val approvalRegistrationRepository = registrationRepository
        val approvalSession = getVaultSession
        val approvalVaultLoader = vaultLoader
        return AndroidIpcApprovalCoordinator.createPendingIntent(
            context = approvalContext,
            request = AndroidIpcApprovalCoordinator.Request(
                caller = caller,
                protocol = PROTOCOL_OPENPGP,
                protocolLabel = Res.string.ipc_protocol_openpgp,
                action = action,
                operation = operation,
                requestDigest = requestDigest,
                retryIntent = normalized.retryIntent,
                allowMultiple = kind.allowsMultipleKeys,
                allowEmpty = kind.allowsEmptyKeySelection ||
                        kind == GpgOpenPgpOperationKind.CHECK_PERMISSION,
                registerApp = registerApp,
                requiresAuthentication = requiresAuthentication,
                allowTokenlessRetry = allowTokenlessRetry,
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
                    val vault = approvalVaultLoader.load(requestReference = "approval")
                    gpgOpenPgpApprovalCandidates(
                        kind = kind,
                        vault = vault,
                        userIds = normalized.extras.userIds,
                        keyIds = normalized.extras.keyIds.toList() +
                                listOfNotNull(
                                    normalized.extras.keyId,
                                    normalized.extras.signKeyId,
                                ),
                    ).map { ring ->
                        AndroidIpcApprovalCoordinator.Candidate(
                            id = ring.cipherId,
                            name = ring.name,
                            description = ring.info.fingerprint,
                        )
                    }
                },
            ),
        )
    }

    private fun approvalRequiredResult(
        caller: AndroidIpcCaller,
        action: String,
        operation: StringResource,
        normalized: NormalizedOpenPgpRequest,
        requestDigest: String,
        registerApp: Boolean,
        requiresAuthentication: Boolean,
    ): Intent = createApproval(
        caller = caller,
        action = action,
        operation = operation,
        normalized = normalized,
        requestDigest = requestDigest,
        registerApp = registerApp,
        requiresAuthentication = requiresAuthentication,
    )
        ?.let(::openPgpInteractionRequired)
        ?: openPgpError(
            OpenPgpError.GENERIC_ERROR,
            ANDROID_IPC_TOO_MANY_APPROVALS_MESSAGE,
        )

    /**
     * Maps a failed signer selection to the OpenPGP API error contract.
     * [missingMaterialMessage] differs between the plain signing actions
     * and the sign-and-encrypt action.
     */
    private fun signerSelectionError(
        selection: GpgOpenPgpSignerSelection,
        missingMaterialMessage: String = "The selected key has no private signing material.",
    ): Intent = when (selection) {
        is GpgOpenPgpSignerSelection.Resolved ->
            error("A resolved signer selection is not an error.")

        GpgOpenPgpSignerSelection.RequestedKeyUnavailable -> openPgpError(
            OpenPgpError.GENERIC_ERROR,
            "The requested signing key is missing or ambiguous.",
        )

        GpgOpenPgpSignerSelection.NoSingleSigner -> openPgpError(
            OpenPgpError.GENERIC_ERROR,
            "A single signing-capable key is required.",
        )

        GpgOpenPgpSignerSelection.MissingPrivateMaterial -> openPgpError(
            OpenPgpError.GENERIC_ERROR,
            missingMaterialMessage,
        )
    }

    private suspend fun getSignKeyId(
        vault: GpgOpenPgpVault,
        selectedRings: List<GpgOpenPgpRing>,
        normalized: NormalizedOpenPgpRequest,
        caller: AndroidIpcCaller,
    ): Intent {
        val ring = when (
            val selection = selectGpgOpenPgpSigner(
                vault = vault,
                selectedRings = selectedRings,
                signKeyId = normalized.extras.signKeyId,
                requirePrivateMaterial = false,
            )
        ) {
            is GpgOpenPgpSignerSelection.Resolved -> selection.ring
            else -> return signerSelectionError(selection)
        }
        recordUsage(
            vault = vault,
            rings = listOf(ring),
            caller = caller,
            type = GpgUsageHistoryRequestType.AGENT_LIST_KEYS,
            response = GpgUsageHistoryResponseType.SUCCESS,
        )
        return openPgpSuccess {
            putExtra(OpenPgpApi.RESULT_SIGN_KEY_ID, ring.primaryKeyId)
            ring.info.userIds.firstOrNull()?.let {
                putExtra(OpenPgpApi.RESULT_PRIMARY_USER_ID, it)
            }
            ring.info.createdAt?.let {
                putExtra(
                    OpenPgpApi.RESULT_KEY_CREATION_TIME,
                    it.toEpochMilliseconds(),
                )
            }
        }
    }

    private suspend fun getKeyIds(
        vault: GpgOpenPgpVault,
        selectedRings: List<GpgOpenPgpRing>,
        caller: AndroidIpcCaller,
    ): Intent {
        val rings = when (
            val selection = selectGpgOpenPgpEncryptionRecipients(
                vault = vault,
                selectedRings = selectedRings,
                keyIds = emptyList(),
            )
        ) {
            is GpgOpenPgpRecipientSelection.Resolved -> selection.recipients
            else -> return openPgpError(
                OpenPgpError.NO_USER_IDS,
                "No encryption-capable keys were selected.",
            )
        }
        recordUsage(
            vault = vault,
            rings = rings,
            caller = caller,
            type = GpgUsageHistoryRequestType.AGENT_LIST_KEYS,
            response = GpgUsageHistoryResponseType.SUCCESS,
        )
        return openPgpSuccess {
            putExtra(
                OpenPgpApi.RESULT_KEY_IDS,
                rings.map(GpgOpenPgpRing::primaryKeyId).distinct().toLongArray(),
            )
        }
    }

    private suspend fun exportKey(
        vault: GpgOpenPgpVault,
        selectedRings: List<GpgOpenPgpRing>,
        normalized: NormalizedOpenPgpRequest,
        caller: AndroidIpcCaller,
        output: ParcelFileDescriptor,
    ): Intent {
        val target = when (
            val selection = selectGpgOpenPgpExportKey(
                vault = vault,
                selectedRings = selectedRings,
                keyId = normalized.extras.keyId,
            )
        ) {
            is GpgOpenPgpExportSelection.Resolved -> selection.ring
            GpgOpenPgpExportSelection.RequestedKeyUnavailable -> return openPgpError(
                OpenPgpError.GENERIC_ERROR,
                "The requested key is missing or ambiguous.",
            )
        }
        ringOperations.exportPublicKey(
            ring = target,
            output = ParcelFileDescriptor
                .AutoCloseOutputStream(output)
                .asSink()
                .buffered(),
            armored = normalized.extras.asciiArmor,
        )
        recordUsage(
            vault = vault,
            rings = listOf(target),
            caller = caller,
            type = GpgUsageHistoryRequestType.AGENT_LIST_KEYS,
            response = GpgUsageHistoryResponseType.SUCCESS,
        )
        return openPgpSuccess()
    }

    private suspend fun clearSign(
        vault: GpgOpenPgpVault,
        selectedRings: List<GpgOpenPgpRing>,
        caller: AndroidIpcCaller,
        input: ParcelFileDescriptor,
        output: ParcelFileDescriptor,
    ): Intent {
        val selection = selectGpgOpenPgpSigner(
            vault = vault,
            selectedRings = selectedRings,
            signKeyId = null,
            requirePrivateMaterial = true,
        )
        if (selection !is GpgOpenPgpSignerSelection.Resolved) {
            return signerSelectionError(selection)
        }
        val signer = selection.ring
        ringOperations.clearSign(
            privateKey = requireNotNull(selection.privateKey),
            candidateRevocationKeys = vault.revocationKeyCandidates(),
            input = ParcelFileDescriptor
                .AutoCloseInputStream(input)
                .asSource()
                .buffered(),
            output = ParcelFileDescriptor
                .AutoCloseOutputStream(output)
                .asSink()
                .buffered(),
        )
        recordUsage(
            vault = vault,
            rings = listOf(signer),
            caller = caller,
            type = GpgUsageHistoryRequestType.AGENT_SIGN_HASH,
            response = GpgUsageHistoryResponseType.SUCCESS,
        )
        return openPgpSuccess {
            putExtra(OpenPgpApi.RESULT_SIGN_KEY_ID, signer.primaryKeyId)
        }
    }

    private suspend fun detachedSign(
        vault: GpgOpenPgpVault,
        selectedRings: List<GpgOpenPgpRing>,
        normalized: NormalizedOpenPgpRequest,
        caller: AndroidIpcCaller,
        input: ParcelFileDescriptor,
    ): Intent {
        val selection = selectGpgOpenPgpSigner(
            vault = vault,
            selectedRings = selectedRings,
            signKeyId = null,
            requirePrivateMaterial = true,
        )
        if (selection !is GpgOpenPgpSignerSelection.Resolved) {
            return signerSelectionError(selection)
        }
        val signer = selection.ring
        val signature = ringOperations.detachedSign(
            privateKey = requireNotNull(selection.privateKey),
            candidateRevocationKeys = vault.revocationKeyCandidates(),
            input = ParcelFileDescriptor
                .AutoCloseInputStream(input)
                .asSource()
                .buffered(),
            armored = normalized.extras.asciiArmor,
        )
        recordUsage(
            vault = vault,
            rings = listOf(signer),
            caller = caller,
            type = GpgUsageHistoryRequestType.AGENT_SIGN_HASH,
            response = GpgUsageHistoryResponseType.SUCCESS,
        )
        return openPgpSuccess {
            putExtra(OpenPgpApi.RESULT_SIGN_KEY_ID, signer.primaryKeyId)
            putExtra(OpenPgpApi.RESULT_DETACHED_SIGNATURE, signature)
            putExtra(OpenPgpApi.RESULT_SIGNATURE_MICALG, "pgp-sha256")
        }
    }

    @Suppress("LongMethod", "ReturnCount")
    private suspend fun encrypt(
        vault: GpgOpenPgpVault,
        selectedRings: List<GpgOpenPgpRing>,
        normalized: NormalizedOpenPgpRequest,
        caller: AndroidIpcCaller,
        input: ParcelFileDescriptor,
        output: ParcelFileDescriptor,
        sign: Boolean,
    ): Intent {
        var recipients = when (
            val selection = selectGpgOpenPgpEncryptionRecipients(
                vault = vault,
                selectedRings = selectedRings,
                keyIds = normalized.extras.keyIds.toList(),
            )
        ) {
            is GpgOpenPgpRecipientSelection.Resolved -> selection.recipients
            GpgOpenPgpRecipientSelection.RequestedKeyUnavailable -> return openPgpError(
                OpenPgpError.GENERIC_ERROR,
                "A requested recipient key is missing or ambiguous.",
            )

            GpgOpenPgpRecipientSelection.NoEncryptionCapableRecipient -> return openPgpError(
                if (normalized.extras.opportunistic) {
                    OpenPgpError.OPPORTUNISTIC_MISSING_KEYS
                } else {
                    OpenPgpError.NO_USER_IDS
                },
                "No encryption-capable recipient key is available.",
            )
        }
        val signerSelection = if (sign) {
            val selection = selectGpgOpenPgpSigner(
                vault = vault,
                selectedRings = selectedRings,
                signKeyId = normalized.extras.signKeyId,
                requirePrivateMaterial = true,
            )
            if (selection !is GpgOpenPgpSignerSelection.Resolved) {
                return signerSelectionError(
                    selection = selection,
                    missingMaterialMessage =
                    "The selected signing key has no private signing material.",
                )
            }
            selection
        } else {
            null
        }
        val signer = signerSelection?.ring
        if (signer?.canEncrypt == true) {
            recipients = (recipients + signer).distinct()
        }
        ringOperations.encrypt(
            recipients = recipients,
            candidateRevocationKeys = vault.revocationKeyCandidates(),
            signingPrivateKey = signerSelection
                ?.let { requireNotNull(it.privateKey) },
            input = ParcelFileDescriptor
                .AutoCloseInputStream(input)
                .asSource()
                .buffered(),
            output = ParcelFileDescriptor
                .AutoCloseOutputStream(output)
                .asSink()
                .buffered(),
            fileName = normalized.extras.originalFilename,
            armored = normalized.extras.asciiArmor,
            enableCompression = normalized.extras.compression,
        )
        val recipientOnly = recipients.filter { it != signer }
        if (recipientOnly.isNotEmpty()) {
            recordUsage(
                vault = vault,
                rings = recipientOnly,
                caller = caller,
                type = GpgUsageHistoryRequestType.AGENT_LIST_KEYS,
                response = GpgUsageHistoryResponseType.SUCCESS,
            )
        }
        signer?.let {
            recordUsage(
                vault = vault,
                rings = listOf(it),
                caller = caller,
                type = GpgUsageHistoryRequestType.AGENT_SIGN_HASH,
                response = GpgUsageHistoryResponseType.SUCCESS,
            )
        }
        return openPgpSuccess {
            signer?.let { putExtra(OpenPgpApi.RESULT_SIGN_KEY_ID, it.primaryKeyId) }
        }
    }

    @Suppress("LongMethod")
    private suspend fun decryptVerify(
        vault: GpgOpenPgpVault,
        selectedRings: List<GpgOpenPgpRing>,
        normalized: NormalizedOpenPgpRequest,
        caller: AndroidIpcCaller,
        input: ParcelFileDescriptor,
        output: ParcelFileDescriptor?,
        outputPolicy: OpenPgpOutputPolicy,
    ): Intent {
        val outputSink = createOpenPgpOutputSink(output, outputPolicy)
        if (normalized.extras.detachedSignature != null) {
            return verifyDetached(
                selectedRings = selectedRings,
                input = input,
                output = outputSink,
                signature = normalized.extras.detachedSignature,
                apiVersion = normalized.apiVersion,
            )
        }
        val result = ringOperations.read(
            rings = selectedRings,
            input = ParcelFileDescriptor
                .AutoCloseInputStream(input)
                .asSource()
                .buffered(),
            output = outputSink,
        )
        resolveOpenPgpDecryptionUsageIdentity(
            rings = selectedRings,
            result = result,
        )?.let { identity ->
            recordUsage(
                vault = vault,
                identity = identity,
                caller = caller,
                type = GpgUsageHistoryRequestType.AGENT_DECRYPT,
                response = GpgUsageHistoryResponseType.SUCCESS,
            )
        }
        return when (result) {
            is GpgOpenPgpReadFileResult.Message -> openPgpSuccess {
                putOpenPgpVerificationResults(
                    apiVersion = normalized.apiVersion,
                    encrypted = result.encrypted,
                    verification = result.verification,
                    metadata = result.metadata?.toOpenPgpMetadata(result.declaredCharset),
                )
            }

            is GpgOpenPgpReadFileResult.ClearSigned -> {
                val charset = "UTF-8".takeIf { result.bodyValidUtf8 }
                openPgpSuccess {
                    putOpenPgpVerificationResults(
                        apiVersion = normalized.apiVersion,
                        encrypted = false,
                        verification = result.verification,
                        metadata = OpenPgpMetadata(
                            "",
                            "text/plain",
                            0L,
                            result.bodySize,
                            charset,
                        ),
                    )
                }
            }
        }
    }

    private suspend fun verifyDetached(
        selectedRings: List<GpgOpenPgpRing>,
        input: ParcelFileDescriptor,
        output: Sink,
        signature: ByteArray,
        apiVersion: Int,
    ): Intent {
        val result = ParcelFileDescriptor.AutoCloseInputStream(input).use { inputStream ->
            inputStream.asSource().buffered().use { inputSource ->
                output.use { outputSink ->
                    ringOperations.verifyDetached(
                        rings = selectedRings,
                        input = inputSource,
                        output = outputSink,
                        signature = signature,
                    )
                }
            }
        }
        return openPgpSuccess {
            putOpenPgpVerificationResults(
                apiVersion = apiVersion,
                encrypted = false,
                verification = result.verification,
                metadata = OpenPgpMetadata(
                    "",
                    "application/octet-stream",
                    0L,
                    result.bodySize,
                    null,
                ),
            )
        }
    }

    private suspend fun recordUsage(
        vault: GpgOpenPgpVault,
        rings: List<GpgOpenPgpRing>,
        caller: AndroidIpcCaller,
        type: GpgUsageHistoryRequestType,
        response: GpgUsageHistoryResponseType,
    ) {
        val addHistory = vault.session
            ?.di
            ?.direct
            ?.instanceOrNull<AddGpgUsageHistory>()
        rings.distinct().forEach { ring ->
            recordUsage(
                vault = vault,
                identity = OpenPgpUsageIdentity(
                    ring = ring,
                    fingerprint = ring.info.fingerprint,
                    keygrip = ring.info.keygrip,
                ),
                caller = caller,
                type = type,
                response = response,
                addHistory = addHistory,
            )
        }
    }

    private suspend fun recordUsage(
        vault: GpgOpenPgpVault,
        identity: OpenPgpUsageIdentity,
        caller: AndroidIpcCaller,
        type: GpgUsageHistoryRequestType,
        response: GpgUsageHistoryResponseType,
        addHistory: AddGpgUsageHistory? = vault.session
            ?.di
            ?.direct
            ?.instanceOrNull<AddGpgUsageHistory>(),
    ) {
        recordAndroidIpcUsage(
            directRecorder = addHistory,
            historyQueue = historyQueue,
            protocol = PendingUsageHistory.Protocol.OPENPGP,
            caller = caller,
            json = json,
            requestType = type.name,
            responseType = response.name,
            cipherId = identity.ring.cipherId,
            fingerprint = identity.fingerprint,
            keygrip = identity.keygrip,
        ) { recorder, event ->
            recorder(
                AddGpgUsageHistoryRequest(
                    cipherId = identity.ring.cipherId,
                    sessionId = ANDROID_IPC_HISTORY_SESSION_ID,
                    caller = event.caller,
                    request = type,
                    response = response,
                    fingerprint = identity.fingerprint,
                    keygrip = identity.keygrip,
                    instant = Instant.fromEpochMilliseconds(event.timestampEpochMilliseconds),
                    eventId = event.id,
                ),
            ).bind()
        }
    }

    companion object {
        // Enough digest prefix to correlate one request's log lines without
        // making the digest itself reconstructible from the log.
        private const val REQUEST_REFERENCE_LENGTH = 12
        internal val SUPPORTED_ACTIONS = setOf(
            OpenPgpApi.ACTION_CHECK_PERMISSION,
            OpenPgpApi.ACTION_SIGN,
            OpenPgpApi.ACTION_CLEARTEXT_SIGN,
            OpenPgpApi.ACTION_DETACHED_SIGN,
            OpenPgpApi.ACTION_ENCRYPT,
            OpenPgpApi.ACTION_SIGN_AND_ENCRYPT,
            OpenPgpApi.ACTION_DECRYPT_VERIFY,
            OpenPgpApi.ACTION_DECRYPT_METADATA,
            OpenPgpApi.ACTION_GET_SIGN_KEY_ID,
            OpenPgpApi.ACTION_GET_SIGN_KEY_ID_LEGACY,
            OpenPgpApi.ACTION_GET_KEY_IDS,
            OpenPgpApi.ACTION_GET_KEY,
            OpenPgpApi.ACTION_QUERY_AUTOCRYPT_STATUS,
        )

        private const val TAG = "OpenPgpRecipientLookup"
    }
}

private fun createOpenPgpOutputSink(
    output: ParcelFileDescriptor?,
    policy: OpenPgpOutputPolicy,
): Sink = when (policy) {
    OpenPgpOutputPolicy.REQUIRED -> requireNotNull(output).asOpenPgpOutputSink()
    OpenPgpOutputPolicy.OPTIONAL -> output?.asOpenPgpOutputSink()
        ?: discardingSink().buffered()

    OpenPgpOutputPolicy.DISCARD -> discardingSink().buffered()
    OpenPgpOutputPolicy.NONE -> error("This OpenPGP operation does not produce stream output.")
}

private fun ParcelFileDescriptor.asOpenPgpOutputSink(): Sink =
    ParcelFileDescriptor
        .AutoCloseOutputStream(this)
        .asSink()
        .buffered()
