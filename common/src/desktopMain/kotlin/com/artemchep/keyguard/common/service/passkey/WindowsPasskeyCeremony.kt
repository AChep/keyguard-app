package com.artemchep.keyguard.common.service.passkey

import com.artemchep.keyguard.AppMode
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.runCatchingNonFatal
import com.artemchep.keyguard.common.model.AddCipherUsedPasskeyHistoryRequest
import com.artemchep.keyguard.common.model.AddCredentialCipherRequest
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.service.session.VaultSessionLocker
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.common.usecase.PasskeyTarget
import com.artemchep.keyguard.provider.bitwarden.usecase.util.canEdit
import com.artemchep.keyguard.util.webauthn.PasskeyBase64
import com.artemchep.keyguard.util.webauthn.WebAuthnAssertionHashRequest
import com.artemchep.keyguard.util.webauthn.WebAuthnAuthenticator
import com.artemchep.keyguard.util.webauthn.WebAuthnInvalidStateException
import com.artemchep.keyguard.util.webauthn.WebAuthnRegistrationRequest
import com.artemchep.keyguard.util.webauthn.canAnswer
import com.artemchep.keyguard.util.webauthn.entity.CreatePasskeyPubKeyCredParams
import com.artemchep.keyguard.util.windowspasskeys.WindowsPasskeyBridge
import com.artemchep.keyguard.util.windowspasskeys.WindowsPasskeyException
import com.artemchep.keyguard.util.windowspasskeys.WindowsPasskeyFailure
import com.artemchep.keyguard.util.windowspasskeys.WindowsPasskeyLimits
import com.artemchep.keyguard.util.windowspasskeys.WindowsPasskeyRequest
import com.artemchep.keyguard.util.windowspasskeys.WindowsPasskeyResponse
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class WindowsPasskeyUiRequest(
    val request: WindowsPasskeyRequest,
    val expiresAt: Instant,
    val mode: AppMode? = null,
)

/**
 * Serves a Windows request from its delivery until Windows has its answer: waits for an
 * unlocked vault, lets the user pick a vault item, verifies the user, checks the vault
 * again, and signs. A failure tells Windows its reason when the site should learn it,
 * and otherwise cancels.
 */
internal class WindowsPasskeyCeremony(
    private val bridge: WindowsPasskeyBridge,
    private val getVaultSession: GetVaultSession,
    private val vaultAccess: WindowsPasskeyVaultAccess,
    private val vaultSessionLocker: VaultSessionLocker,
    private val authenticator: WebAuthnAuthenticator,
    private val onFailure: suspend () -> Unit,
    private val clock: Clock = Clock.System,
) {
    private val mutableUi = MutableStateFlow<WindowsPasskeyUiRequest?>(null)
    val ui = mutableUi.asStateFlow()

    // The UI is hidden during Windows Hello, so it cannot tell whether a request is served.
    private val activeRequests = MutableStateFlow(0)

    /** A request is being served: from its delivery until its usage is recorded. */
    val busy: Flow<Boolean> = activeRequests.map { it > 0 }.distinctUntilChanged()

    // A request boundary always resolves the waiting OS call, including storage/crypto failures.
    @Suppress("TooGenericExceptionCaught")
    suspend fun handle(request: WindowsPasskeyRequest, operation: WindowsPasskeyOperation) {
        // Windows may have started its clock before this process ran: count down what is left.
        val expiresAt = clock.now() + request.expiresInMs.milliseconds
        activeRequests.update { it + 1 }
        var failure: WindowsPasskeyFailure? = null
        try {
            val initiallyLocked = getVaultSession().first() !is MasterSession.Key
            if (initiallyLocked) {
                mutableUi.value = WindowsPasskeyUiRequest(request, expiresAt)
            }
            val session = getVaultSession().filterIsInstance<MasterSession.Key>().first()
            // Observe an unlock during this request. Wall-clock timestamps can move backwards.
            val freshUnlock = initiallyLocked && session.origin == MasterSession.Key.Authenticated
            coroutineScope {
                val operationScope = this
                // Keep the vault alive even when the picker closes for Windows Hello, or
                // a single match needs no window. Cancellation releases this lease at once.
                val keepAlive = launch(start = CoroutineStart.UNDISPATCHED) {
                    vaultSessionLocker.keepAlive()
                }
                val retired = launch {
                    session.session.active.first { !it }
                    // Invalidate native verification immediately, even when Hello is still open.
                    attempt { bridge.complete(request.id, null) }
                    operationScope.cancel("Vault session retired")
                }
                try {
                    val vault = vaultAccess.get(session)
                    val serving = Serving(request, expiresAt, session, vault, freshUnlock)
                    val result = if (request.create) serving.create() else serving.get()
                    requireSession(session)
                    operation.complete {
                        bridge.complete(request.id, result.response)
                        result.usage?.let { usage -> attempt { vault.recordUsage(usage).bind() } }
                    }
                } finally {
                    keepAlive.cancel()
                    retired.cancel()
                }
            }
        } catch (e: Exception) {
            failure = e.windowsFailure()
            // A reason the site learns, a closed Hello prompt, or an ended request is no error.
            if (failure == null && e !is CancellationException && !e.isEndedRequest()) onFailure()
        } finally {
            if (mutableUi.value?.request?.id == request.id) mutableUi.value = null
            withContext(NonCancellable) { attempt { bridge.complete(request.id, null, failure) } }
            activeRequests.update { it - 1 }
        }
    }

    private fun Exception.windowsFailure() = when {
        this is WebAuthnInvalidStateException -> WindowsPasskeyFailure.EXCLUDED
        this is NoPasskeyException -> WindowsPasskeyFailure.NOT_FOUND
        this is WindowsPasskeyException && code == WindowsPasskeyException.DISMISSED -> WindowsPasskeyFailure.DISMISSED
        else -> null
    }

    /**
     * Windows already has its answer: the request was cancelled, expired, or answered. The
     * native calls that cannot be interrupted, such as Hello, report it once they return.
     */
    private fun Exception.isEndedRequest() =
        this is WindowsPasskeyException && code == WindowsPasskeyException.CANCELLED

    private suspend fun Serving.create(): PreparedResponse {
        val user = requireNotNull(request.user)
        val selection = pick { select ->
            AppMode.SavePasskey(request.rpId) { cipher -> select(Selection(cipher.accountId, cipher.id)) }
        }
        val username = user.name.ifBlank { user.displayName.ifBlank { request.rpId } }
        verify(username)
        val ciphers = vault.getCiphers().first()
        val cipher = selection.find(ciphers)
        require(cipher.type == DSecret.Type.Login && cipher.service.canEdit())
        require(vault.getCanWrite().first())
        require(vault.getCollections().first().none {
            it.accountId == cipher.accountId && it.id in cipher.collectionIds && it.readOnly
        })
        requireSession(session)
        val result = authenticator.createCredential(
            request = WebAuthnRegistrationRequest(
                rpId = request.rpId,
                rpName = request.rpName,
                userHandle = PasskeyBase64.encodeToString(user.id),
                userName = user.name,
                userDisplayName = user.displayName,
                pubKeyCredParams = request.algorithms.map {
                    CreatePasskeyPubKeyCredParams(type = "public-key", alg = it.toDouble())
                },
                discoverable = request.discoverable,
                userVerification = request.userVerification,
                excludedCredentialIds = request.allowed().descriptors
                    .filter { it.type == "public-key" }
                    .mapTo(mutableSetOf()) { it.credentialId },
            ),
            userVerified = true,
            credentials = ciphers.storedPasskeyCredentials().map { it.toWebAuthnCredential() },
        )
        // Never return a credential that has not been durably added to the selected vault item:
        // adding a passkey returns Stored or fails.
        requireSession(session)
        val addition = AddCredentialCipherRequest(cipher.id, result.credential.toAddCredentialCipherRequest())
        vault.addCredential(addition).bind()
        return PreparedResponse(
            response = WindowsPasskeyResponse(result.authenticatorData),
        )
    }

    private suspend fun Serving.get(): PreparedResponse {
        val allowed = request.allowed()
        val matches = vault.getVisibleCiphers().first().availablePasskeys()
            .filter { (_, credential) -> credential.toWebAuthnCredential().canAnswer(request.rpId, allowed) }
            .map { (cipher, credential) ->
                Selection(cipher.accountId, cipher.id, credential.credentialId, cipher.reprompt)
            }
        if (matches.isEmpty()) throw NoPasskeyException()
        val selection = matches.singleOrNull()?.takeUnless { it.reprompt } ?: pick { select ->
            AppMode.PickPasskey(
                target = PasskeyTarget(
                    rpId = request.rpId,
                    allowed = allowed,
                ),
                onComplete = { cipher, credential ->
                    select(Selection(cipher.accountId, cipher.id, credential.credentialId, cipher.reprompt))
                },
            )
        }
        val before = selection.find(vault.getVisibleCiphers().first())
        val username = selection.credential(before)?.windowsUserName(before) ?: request.rpId
        verify(username)
        // Re-read after any UI/Hello delay. Never sign a deleted/replaced credential snapshot.
        val cipher = selection.find(vault.getVisibleCiphers().first())
        require(!cipher.reprompt || selection.reprompt)
        val credential = requireNotNull(selection.credential(cipher))
        requireSession(session)
        // Enforces the RP ID, the allow list, and a signable key.
        val result = authenticator.getAssertion(
            request = WebAuthnAssertionHashRequest(
                request.rpId,
                request.clientDataHash,
                request.userVerification,
                allowed,
            ),
            credential = credential.toWebAuthnCredential(),
            userVerified = true,
        )
        return try {
            PreparedResponse(
                response = WindowsPasskeyResponse(
                    authenticatorData = result.authenticatorData,
                    credentialId = result.credentialId,
                    // The result's own buffer is wiped below.
                    signature = result.signature.copyOf(),
                    userHandle = result.userHandle,
                ),
                usage = AddCipherUsedPasskeyHistoryRequest(cipher.accountId, cipher.id, credential.credentialId),
            )
        } finally {
            result.signature.fill(0)
        }
    }

    /** Shows the picker that [mode] builds around its selection callback, and awaits a pick. */
    private suspend fun Serving.pick(mode: (select: (Selection) -> Unit) -> AppMode): Selection {
        val selected = CompletableDeferred<Selection>()
        mutableUi.value = WindowsPasskeyUiRequest(request, expiresAt, mode { selected.complete(it) })
        return selected.await()
    }

    private suspend fun Serving.verify(username: String) {
        // Closing the picker before Hello prevents its focus effect from hiding the OS prompt.
        mutableUi.value = null
        requireSession(session)
        // Vault text may not fit the native prompt; the RP ID always does.
        val name = username.takeIf(WindowsPasskeyLimits::acceptsText) ?: request.rpId
        // The bridge answers Windows only for a verified user: by Windows Hello, or by the
        // unlock that just happened during this request.
        bridge.verify(request.id, request.window, name, request.rpId, unlocked = freshUnlock)
        requireSession(session)
    }

    private suspend fun requireSession(session: MasterSession.Key) {
        currentCoroutineContext().ensureActive()
        if (!session.session.active.value || getVaultSession.valueOrNull != session) {
            throw CancellationException("Vault session changed")
        }
    }

    /** The request being served, with what [handle] learned before serving it. */
    private class Serving(
        val request: WindowsPasskeyRequest,
        val expiresAt: Instant,
        val session: MasterSession.Key,
        val vault: WindowsPasskeyVault,
        val freshUnlock: Boolean,
    )

    private data class PreparedResponse(
        val response: WindowsPasskeyResponse,
        val usage: AddCipherUsedPasskeyHistoryRequest? = null,
    )

    private data class Selection(
        val accountId: String,
        val cipherId: String,
        val credentialId: String? = null,
        val reprompt: Boolean = false,
    ) {
        fun find(ciphers: List<DSecret>): DSecret = requireNotNull(ciphers.firstOrNull {
            it.accountId == accountId && it.id == cipherId && !it.deleted && !it.archived
        })

        fun credential(cipher: DSecret) = cipher.login?.fido2Credentials
            ?.firstOrNull { it.credentialId == credentialId }
    }

    private class NoPasskeyException : IllegalStateException("The vault holds none of the requested passkeys")
}

// Integration failures are isolated; cancellation still propagates to request/session cleanup.
internal inline fun <T> attempt(block: () -> T): T? = runCatchingNonFatal(block).getOrNull()
