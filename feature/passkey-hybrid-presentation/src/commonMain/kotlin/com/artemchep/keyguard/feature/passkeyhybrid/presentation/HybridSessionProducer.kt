package com.artemchep.keyguard.feature.passkeyhybrid.presentation

import com.artemchep.keyguard.util.fidohybrid.HybridAssertionRequest
import com.artemchep.keyguard.util.fidohybrid.HybridAuthenticator
import com.artemchep.keyguard.util.fidohybrid.HybridQr
import com.artemchep.keyguard.util.fidohybrid.HybridRegistrationRequest
import com.artemchep.keyguard.util.fidohybrid.HybridResult
import com.artemchep.keyguard.util.webauthn.WebAuthnAssertionHashRequest
import com.artemchep.keyguard.util.webauthn.WebAuthnAssertionResult
import com.artemchep.keyguard.util.webauthn.WebAuthnAuthenticator
import com.artemchep.keyguard.util.webauthn.WebAuthnCredentialCreationResult
import com.artemchep.keyguard.util.webauthn.WebAuthnInvalidStateException
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Owns one ephemeral request. Screen disposal and vault retirement both cancel it. */
class HybridSessionProducer(
    private val scope: CoroutineScope,
    private val vault: HybridVault,
    private val authenticator: WebAuthnAuthenticator,
    private val transport: HybridSessionTransport,
) {
    private val mutableState = MutableStateFlow(HybridSessionState(onScan = ::scan))
    val state: StateFlow<HybridSessionState> = mutableState.asStateFlow()
    private val pending = atomic<HybridQr?>(null)
    private var operation: Job? = null
    private var used: HybridCandidate? = null

    init {
        scope.coroutineContext.job.invokeOnCompletion { pending.getAndSet(null)?.clear() }
    }

    private fun fail(
        error: HybridError,
        rpId: String? = null,
        operation: HybridOperation? = null,
    ) {
        mutableState.value =
            HybridSessionState(
                phase = HybridPhase.ERROR,
                error = error,
                rpId = rpId,
                operation = operation,
                onScan = ::scan,
            )
    }

    private fun scan(raw: String) {
        if (!scope.isActive || operation?.isActive == true || pending.value != null) return
        val qr = parseQrOrNull(raw)
        if (qr == null) {
            fail(HybridError.QR)
        } else {
            if (!pending.compareAndSet(null, qr)) {
                qr.clear()
                return
            }
            mutableState.value = HybridSessionState(HybridPhase.PERMISSION, onPermission = ::permission)
        }
    }

    // This boundary translates transport/crypto failures to fixed, secret-free UI messages.
    @Suppress("TooGenericExceptionCaught")
    private fun permission(granted: Boolean) {
        val qr = pending.getAndSet(null) ?: return
        if (!granted || !scope.isActive) {
            qr.clear()
            fail(HybridError.PERMISSION)
            return
        }
        mutableState.value = HybridSessionState(HybridPhase.CONNECTING)
        operation =
            scope.launch {
                try {
                    serve(qr)
                } catch (e: Exception) {
                    fail(HybridError.CONNECTION)
                    // A timeout is a connection failure; any other cancellation must propagate.
                    if (e is CancellationException && e !is TimeoutCancellationException) throw e
                }
            }
        // Runs on completion, failure and cancellation, including cancellation before the job starts.
        operation?.invokeOnCompletion { qr.clear() }
    }

    private suspend fun serve(qr: HybridQr) =
        coroutineScope {
            val session =
                vault.openSession()
                    ?: throw CancellationException("Vault locked")
            val requestScope = this
            val retired =
                launch {
                    session.active.first { !it }
                    requestScope.cancel("Vault session retired")
                }
            try {
                val success =
                    withContext(Dispatchers.Default) {
                        transport.run(
                            qr,
                            session::requireActive,
                            { mutableState.value = HybridSessionState(HybridPhase.WAITING) },
                            object : HybridAuthenticator {
                                override suspend fun getAssertion(request: HybridAssertionRequest) =
                                    sign(request, session)

                                override suspend fun makeCredential(
                                    request: HybridRegistrationRequest,
                                ) = create(request, session)
                            },
                        )
                    }
                if (success) {
                    session.requireActive()
                    // Name what was sent; a request that never reached the sending step stays generic.
                    val sent = state.value.takeIf { it.phase == HybridPhase.SENDING }
                    mutableState.value =
                        HybridSessionState(
                            phase = HybridPhase.SUCCESS,
                            rpId = sent?.rpId,
                            operation = sent?.operation,
                        )
                    used?.let { vault.recordUsage(it) }
                } else if (state.value.phase != HybridPhase.ERROR) {
                    fail(HybridError.CONNECTION)
                }
            } finally {
                retired.cancel()
                used = null
            }
        }

    private suspend fun sign(
        hybrid: HybridAssertionRequest,
        session: HybridVaultSession,
    ): HybridResult<WebAuthnAssertionResult> {
        val request = hybrid.options
        session.requireActive()
        if (!hybrid.userPresent) return probe(request)
        return choose(request)?.let { signSelected(hybrid, it, session) } ?: HybridResult.NoCredentials
    }

    /** A pre-flight names an allowed credential without UI; relying parties reject its UP=0 assertion. */
    private suspend fun probe(request: WebAuthnAssertionHashRequest): HybridResult<WebAuthnAssertionResult> {
        val credential =
            vault.candidates(request).firstNotNullOfOrNull { vault.credential(it) }
                ?: return HybridResult.NoCredentials
        return HybridResult.Success(
            authenticator.getAssertion(request, credential, userVerified = false, userPresent = false),
        )
    }

    private suspend fun signSelected(
        hybrid: HybridAssertionRequest,
        selection: HybridCandidate,
        session: HybridVaultSession,
    ): HybridResult<WebAuthnAssertionResult> {
        val request = hybrid.options
        verify(request.rpId, null, HybridOperation.SIGN_IN, session)
        val credential = vault.credential(selection)
        if (credential == null) {
            fail(HybridError.NO_CREDENTIALS, request.rpId, HybridOperation.SIGN_IN)
            return HybridResult.NoCredentials
        }
        val result =
            authenticator.getAssertion(
                request,
                credential,
                userVerified = true,
                authenticatorExtensions = hybrid::extensions,
            )
        var transferred = false
        return try {
            session.requireActive()
            used = selection
            mutableState.value =
                HybridSessionState(HybridPhase.SENDING, rpId = request.rpId, operation = HybridOperation.SIGN_IN)
            HybridResult.Success(result).also { transferred = true }
        } finally {
            if (!transferred) result.clear()
        }
    }

    private suspend fun create(
        request: HybridRegistrationRequest,
        session: HybridVaultSession,
    ): HybridResult<WebAuthnCredentialCreationResult> =
        try {
            createVerified(request, session)
        } catch (_: HybridWriteException) {
            fail(HybridError.WRITE, request.options.rpId, HybridOperation.CREATE)
            HybridResult.Denied
        } catch (_: WebAuthnInvalidStateException) {
            fail(HybridError.EXCLUDED, request.options.rpId, HybridOperation.CREATE)
            HybridResult.CredentialExcluded
        }

    private suspend fun createVerified(
        request: HybridRegistrationRequest,
        session: HybridVaultSession,
    ): HybridResult<WebAuthnCredentialCreationResult> {
        session.requireActive()
        val selected = CompletableDeferred<HybridSaveTarget>()
        val options = request.options
        val username = options.userName ?: options.userDisplayName
        mutableState.value =
            HybridSessionState(
                phase = HybridPhase.SAVE,
                rpId = options.rpId,
                username = username,
                operation = HybridOperation.CREATE,
                onSaveTarget = { selected.complete(it) },
            )
        val target = selected.await()
        verify(options.rpId, username, HybridOperation.CREATE, session)
        vault.requireWritable(target)
        val credentials = vault.credentials()
        val result =
            authenticator.createCredential(
                options,
                userVerified = true,
                credentials = credentials,
                authenticatorExtensions = request::extensions,
            )
        var transferred = false
        return try {
            session.requireActive()
            if (!vault.save(target, result.credential)) throw HybridWriteException()
            session.requireActive()
            mutableState.value =
                HybridSessionState(HybridPhase.SENDING, rpId = options.rpId, operation = HybridOperation.CREATE)
            HybridResult.Success(result).also { transferred = true }
        } finally {
            if (!transferred) result.clear()
        }
    }

    private suspend fun verify(
        rpId: String,
        username: String?,
        operation: HybridOperation,
        session: HybridVaultSession,
    ) {
        val verified = CompletableDeferred<Unit>()
        mutableState.value =
            HybridSessionState(
                phase = HybridPhase.VERIFY,
                rpId = rpId,
                username = username,
                operation = operation,
                onVerified = { verified.complete(Unit) },
            )
        verified.await()
        currentCoroutineContext().ensureActive()
        session.requireActive()
    }

    // Like the Android provider, absence is shown before verification: the vault is already
    // unlocked and, without credProtect, verification would not change which passkeys match.
    private suspend fun choose(request: WebAuthnAssertionHashRequest): HybridCandidate? {
        val matches = vault.candidates(request)
        if (matches.isEmpty()) {
            fail(HybridError.NO_CREDENTIALS, request.rpId, HybridOperation.SIGN_IN)
            return null
        }
        val selected = CompletableDeferred<HybridCandidate>()
        mutableState.value =
            HybridSessionState(
                phase = HybridPhase.PICK,
                rpId = request.rpId,
                operation = HybridOperation.SIGN_IN,
                candidates = matches,
                onPick = { if (it in matches) selected.complete(it) },
            )
        return selected.await()
    }
}

private fun parseQrOrNull(raw: String): HybridQr? =
    try {
        HybridQr.parse(raw)
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: IllegalStateException) {
        null
    }
