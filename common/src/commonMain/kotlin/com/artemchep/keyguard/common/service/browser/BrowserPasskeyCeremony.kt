package com.artemchep.keyguard.common.service.browser

import com.artemchep.keyguard.AppMode
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.AddCipherUsedPasskeyHistoryRequest
import com.artemchep.keyguard.common.model.AddCredentialCipherRequest
import com.artemchep.keyguard.common.model.CliAgentUsageHistoryRequestType
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.passkey.availablePasskeys
import com.artemchep.keyguard.common.service.passkey.canStorePasskey
import com.artemchep.keyguard.common.service.passkey.storedPasskeyCredentials
import com.artemchep.keyguard.common.service.passkey.toAddCredentialCipherRequest
import com.artemchep.keyguard.common.service.passkey.toWebAuthnCredential
import com.artemchep.keyguard.common.service.passkey.withSessionLease
import com.artemchep.keyguard.common.service.session.BrowserExtensionSessionDependencies
import com.artemchep.keyguard.common.service.tld.TldService
import com.artemchep.keyguard.common.usecase.PasskeyTarget
import com.artemchep.keyguard.feature.browserextension.protocol.BrowserError
import com.artemchep.keyguard.feature.browserextension.protocol.BrowserJson
import com.artemchep.keyguard.feature.browserextension.protocol.BrowserReply
import com.artemchep.keyguard.feature.browserextension.protocol.BrowserRequestException
import com.artemchep.keyguard.feature.browserextension.protocol.BrowserVaultState
import com.artemchep.keyguard.feature.browserextension.protocol.PasskeyCreateRequest
import com.artemchep.keyguard.feature.browserextension.protocol.PasskeyGetRequest
import com.artemchep.keyguard.feature.browserextension.protocol.PasskeyResponse
import com.artemchep.keyguard.feature.browserextension.protocol.browserOk
import com.artemchep.keyguard.util.webauthn.PasskeyBase64
import com.artemchep.keyguard.util.webauthn.WebAuthnAllowedCredentialDescriptors
import com.artemchep.keyguard.util.webauthn.WebAuthnAssertionRequest
import com.artemchep.keyguard.util.webauthn.WebAuthnCallerContext
import com.artemchep.keyguard.util.webauthn.WebAuthnEncodingException
import com.artemchep.keyguard.util.webauthn.WebAuthnException
import com.artemchep.keyguard.util.webauthn.WebAuthnInvalidStateException
import com.artemchep.keyguard.util.webauthn.WebAuthnNotAllowedException
import com.artemchep.keyguard.util.webauthn.WebAuthnNotSupportedException
import com.artemchep.keyguard.util.webauthn.WebAuthnPrfAssertionInputs
import com.artemchep.keyguard.util.webauthn.WebAuthnSyntaxException
import com.artemchep.keyguard.util.webauthn.canAnswer
import com.artemchep.keyguard.util.webauthn.decodeExcludedCredentialIds
import com.artemchep.keyguard.util.webauthn.entity.CreatePasskey
import com.artemchep.keyguard.util.webauthn.entity.CreatePasskeyAttestation
import com.artemchep.keyguard.util.webauthn.findPasskeyAlgorithmOrNull
import com.artemchep.keyguard.util.webauthn.parseWebAuthnAllowedCredentialDescriptors
import com.artemchep.keyguard.util.webauthn.parseWebAuthnPrfAssertionInputs
import com.artemchep.keyguard.util.webauthn.requireWebAuthnPrfRegistrationInputs
import kotlin.time.Clock
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject

/**
 * The passkey requests of one session, README section 5: `passkey_get` and
 * `passkey_create`. Serves them like the Windows passkey ceremony: waits for
 * an unlocked vault, keeps it unlocked meanwhile, lets the user pick an item
 * in a vault window, verifies the user unless the page discourages it, checks
 * the vault again, and signs.
 */
@Suppress("TooManyFunctions", "LongParameterList") // One function per step of the requests, one dependency per concern.
internal class BrowserPasskeyCeremony(
    private val vaultAccess: BrowserVaultAccess,
    private val approvals: BrowserApprovals,
    private val requestLog: BrowserRequestLog,
    private val passkeys: BrowserPasskeys,
    private val cryptoGenerator: CryptoGenerator,
    private val tldService: TldService,
    private val handles: BrowserHandles,
    private val session: () -> BrowserExtensionSession,
    private val pairing: () -> BrowserPairing?,
    /** Checks the pairing against the unlocked vault before the ceremony. */
    private val requirePairing: suspend () -> Unit,
    /** Shows the unlock prompt while the vault is locked; `null` once it is unlocked. */
    private val awaitUnlock: suspend () -> BrowserApproval?,
    private val clock: Clock = Clock.System,
) {
    /** Signs in to the page with a passkey of the vault, once the user approves. */
    suspend fun get(request: PasskeyGetRequest): BrowserReply =
        recorded(CliAgentUsageHistoryRequestType.BROWSER_PASSKEY_GET) { usage ->
            val mediation = Mediation.of(request.mediation)
            // The browser handles the calls of a frame of another site, silent
            // ones, and those of a mediation that version 1 doesn't know.
            if (!request.sameOriginWithAncestors || mediation == null || mediation == Mediation.SILENT) {
                fail(BrowserError.NOT_SUPPORTED)
            }
            // The inline menu is the user interface of a conditional call.
            if (mediation == Mediation.CONDITIONAL && request.handle == null) {
                fail(BrowserError.INVALID_REQUEST)
            }
            val options = GetOptions.parse(request.options)
            val page = originPage(request.origin)
            val rpId = passkeys.validateRpId(options.rpId, page.origin)
                ?: fail(BrowserError.SECURITY)
            usage.origin = page.origin
            val site = page.site(tldService)
            val otherSiteRpId = findOtherSiteRpId(page, site, rpId)
            serve(page) { vault ->
                val target = PasskeyTarget(rpId = rpId, allowed = options.allowed)
                val pick = request.handle
                    ?.let { handle -> picked(vault, handle, target) }
                    ?: pickPasskey(vault, page, target)
                usage.cipherId = pick.cipherId
                val verified = approveGet(vault, page, site, pick, options.userVerification, otherSiteRpId)
                // The item as it is now: it may have changed while the user was asked.
                vault.requireCurrent(page)
                val (cipher, credential) = vault.answering(pick, target)
                val response = webAuthn {
                    passkeys.authenticator.getAssertion(
                        request = WebAuthnAssertionRequest(
                            challenge = options.challenge,
                            userVerification = options.userVerification,
                            allowedCredentials = options.allowed,
                            prf = options.prf,
                        ),
                        context = WebAuthnCallerContext(origin = page.origin, rpId = rpId),
                        credential = credential.toWebAuthnCredential(),
                        userVerified = verified,
                    )
                }
                val history = AddCipherUsedPasskeyHistoryRequest(
                    accountId = cipher.accountId,
                    cipherId = cipher.id,
                    credentialId = credential.credentialId,
                    instant = clock.now(),
                )
                vault.dependencies.addCipherUsedPasskeyHistory(history).attempt().bind()
                credentialReply(response)
            }
        }

    /** Saves a new passkey of the page in a login the user picks. */
    suspend fun create(request: PasskeyCreateRequest): BrowserReply =
        recorded(CliAgentUsageHistoryRequestType.BROWSER_PASSKEY_CREATE) { usage ->
            if (!request.sameOriginWithAncestors) fail(BrowserError.NOT_SUPPORTED)
            val options = parseCreateOptions(request.options)
            val page = originPage(request.origin)
            val rpId = passkeys.validateRpId(options.rp.id, page.origin)
                ?: fail(BrowserError.SECURITY)
            usage.origin = page.origin
            // The browser may support an algorithm of the page; Keyguard asks nothing then.
            if (findPasskeyAlgorithmOrNull(options, passkeys.supportedAlgorithms) == null) {
                fail(BrowserError.NOT_SUPPORTED)
            }
            val site = page.site(tldService)
            val otherSiteRpId = findOtherSiteRpId(page, site, rpId)
            serve(page) { vault ->
                val pick = pick(page, rpId, create = true) { select ->
                    AppMode.SavePasskey(rpId) { cipher -> select(Pick(cipher.accountId, cipher.id)) }
                }
                usage.cipherId = pick.cipherId
                // Before the user is asked: a login the user can't edit stores nothing.
                vault.storing(pick, vault.dependencies.getCiphers().first())
                val verify = vault.verifies(options.authenticatorSelection.userVerification)
                if (verify) verifyCreate(vault, page, site, pick, options, otherSiteRpId)
                // The item as it is now: it may have changed while the user was asked.
                vault.requireCurrent(page)
                val ciphers = vault.dependencies.getCiphers().first()
                val cipher = vault.storing(pick, ciphers)
                // After the user consented: a passkey of `excludeCredentials` fails it.
                val result = webAuthn {
                    passkeys.authenticator.createCredential(
                        data = options,
                        context = WebAuthnCallerContext(origin = page.origin, rpId = rpId),
                        userVerified = vault.freshUnlock || verify,
                        transports = listOf(TRANSPORT_INTERNAL),
                        credentials = ciphers.storedPasskeyCredentials().map { it.toWebAuthnCredential() },
                    )
                }
                // Never answer with a passkey that the vault does not store.
                vault.requireCurrent(page)
                val addition = AddCredentialCipherRequest(
                    cipherId = cipher.id,
                    data = result.credential.toAddCredentialCipherRequest(),
                )
                vault.dependencies.addCredentialCipher(addition)
                    .attempt()
                    .bind()
                    .onLeft { fail(BrowserError.NOT_ALLOWED) }
                credentialReply(result.responseJson)
            }
        }

    /** The passkey of the [handle] the user picked in the inline menu. */
    private suspend fun picked(
        vault: Vault,
        handle: String,
        target: PasskeyTarget,
    ): Pick {
        val passkey = handles.resolve(handle) as? BrowserPasskeyHandle
            ?: fail(BrowserError.INVALID_REQUEST)
        val pick = Pick(passkey.accountId, passkey.cipherId, passkey.credentialId)
        vault.answering(pick, target)
        // A handle never leaves the relying party it was issued for.
        if (passkey.rpId != target.rpId) fail(BrowserError.NOT_ALLOWED)
        return pick
    }

    /** Lets the user of the [page] pick one of the passkeys that can answer the [target]. */
    private suspend fun pickPasskey(
        vault: Vault,
        page: BrowserPage,
        target: PasskeyTarget,
    ): Pick {
        val candidates = vault.dependencies.logins()
            .availablePasskeys()
            .filter { (_, credential) -> credential.toWebAuthnCredential().canAnswer(target.rpId, target.allowed) }
        // The browser offers its own passkeys and devices.
        if (candidates.isEmpty()) fail(BrowserError.NOT_SUPPORTED)
        val pick = pick(page, target.rpId, create = false) { select ->
            AppMode.PickPasskey(target) { cipher, credential ->
                select(
                    Pick(
                        accountId = cipher.accountId,
                        cipherId = cipher.id,
                        credentialId = credential.credentialId,
                        // The window asks for the master password of an item with re-prompt before the pick.
                        identityConfirmed = cipher.reprompt,
                    ),
                )
            }
        }
        // Before the pick approves anything.
        vault.answering(pick, target)
        return pick
    }

    /**
     * Approves signing in with the [pick]: a pick in the vault window
     * approves it, a pick in the inline menu asks unless an approval
     * covers it. Asks again, and requires the user's identity, for an item
     * with master-password re-prompt or a page that doesn't discourage user
     * verification, unless the window confirmed the user's identity.
     *
     * @param otherSiteRpId The relying party, if it is of another site, see [BrowserRelease.otherSiteRpId].
     * @return Whether the user is verified.
     */
    @Suppress("LongParameterList", "ReturnCount") // Each way to approve answers on its own.
    private suspend fun approveGet(
        vault: Vault,
        page: BrowserPage,
        site: String,
        pick: Pick,
        userVerification: String?,
        otherSiteRpId: String?,
    ): Boolean {
        val pairing = pairing()
            ?: fail(BrowserError.UNPAIRED)
        val (cipher, credential) = vault.passkey(pick)
            ?: fail(BrowserError.INVALID_REQUEST)
        // The pick approved it, and verified the user; never remembered, as for re-prompt.
        if (pick.fromWindow && pick.identityConfirmed) return true
        val verify = vault.verifies(userVerification)
        val grant = BrowserGrant(
            pairingId = pairing.id,
            site = site,
            accountId = cipher.accountId,
            cipherId = cipher.id,
        )
        if (pick.fromWindow && !cipher.reprompt && !verify) {
            // The pick approved it, as approving a prompt does.
            approvals.rememberRelease(vault.key, vault.grantsEpoch, grant)
            return vault.freshUnlock
        }
        val accountName = vault.dependencies.accountNames()[cipher.accountId]
        approvals.approveRelease(
            session = session(),
            pairing = pairing,
            grant = grant,
            reprompt = cipher.reprompt,
            verify = verify,
        ) { approvalWindow ->
            val release = cipher.toRelease(
                page = page,
                site = site,
                username = credential.userName,
                accountName = accountName,
                approvalWindow = approvalWindow,
                userVerification = verify,
            ).copy(otherSiteRpId = otherSiteRpId)
            BrowserOperation.PasskeyGet(release)
        }.onLeft(::fail)
        // An approval that required the user's identity verified the user.
        return vault.freshUnlock || cipher.reprompt || verify
    }

    /**
     * Asks the user to confirm their identity to save the passkey in the [pick].
     *
     * @param otherSiteRpId The relying party, if it is of another site, see [BrowserRelease.otherSiteRpId].
     */
    private suspend fun verifyCreate(
        vault: Vault,
        page: BrowserPage,
        site: String,
        pick: Pick,
        options: CreatePasskey,
        otherSiteRpId: String?,
    ) {
        val cipher = vault.dependencies.logins().firstOrNull(pick::matches)
            ?: fail(BrowserError.INVALID_REQUEST)
        val release = cipher.toRelease(
            page = page,
            site = site,
            username = options.user.name,
            accountName = vault.dependencies.accountNames()[cipher.accountId],
            // Never remembered.
            approvalWindow = Duration.ZERO,
            userVerification = true,
        ).copy(otherSiteRpId = otherSiteRpId)
        val approval = approvals.ask(
            session = session(),
            pairingName = pairing()?.name,
            operation = BrowserOperation.PasskeyCreate(release),
            requiresIdentity = true,
        )
        if (approval is BrowserApproval.Refused) fail(approval.error)
    }

    /**
     * Opens the vault window of a request of the [page] for [rpId], in the
     * [mode] built around its selection callback, and waits for the user's pick.
     */
    private suspend fun pick(
        page: BrowserPage,
        rpId: String,
        create: Boolean,
        mode: (select: (Pick) -> Unit) -> AppMode,
    ): Pick {
        val answer = CompletableDeferred<PickAnswer>()
        val timeout = approvals.approvalTimeout()
        val picker = BrowserPasskeyPicker(
            id = cryptoGenerator.uuid(),
            session = session(),
            pairingName = pairing()?.name,
            origin = page.origin,
            rpId = rpId,
            create = create,
            expiresAt = clock.now() + timeout,
            mode = mode { answer.complete(PickAnswer.Picked(it)) },
            onAnotherDevice = { answer.complete(PickAnswer.AnotherDevice) },
            onDismiss = { answer.complete(PickAnswer.Dismissed) },
        )
        if (!passkeys.open(picker)) fail(BrowserError.BUSY)
        val result = try {
            withTimeoutOrNull(timeout) { answer.await() }
        } finally {
            // Ending the session closes the window as well.
            passkeys.close(picker.id)
        }
        return when (result) {
            is PickAnswer.Picked -> result.pick.copy(fromWindow = true)
            PickAnswer.AnotherDevice -> fail(BrowserError.NOT_SUPPORTED)
            PickAnswer.Dismissed -> fail(BrowserError.USER_DENIED)
            null -> fail(BrowserError.APPROVAL_TIMEOUT)
        }
    }

    /**
     * Serves a request of the [page] in an unlocked vault: shows the unlock
     * prompt while it is locked, and keeps it unlocked meanwhile. If it
     * locks, the request ends with `vault_locked`.
     */
    private suspend fun serve(
        page: BrowserPage,
        block: suspend (Vault) -> BrowserReply,
    ): BrowserReply {
        val locked = vaultAccess.currentState() != BrowserVaultState.UNLOCKED
        val unlock = awaitUnlock()
        if (unlock is BrowserApproval.Refused) fail(unlock.error)
        // Before anything is asked: the unlocked vault confirms the pairing.
        requirePairing()
        val key = vaultAccess.currentKey()
            ?: fail(BrowserError.VAULT_LOCKED)
        val dependencies = vaultAccess.dependencies()
            ?: fail(BrowserError.VAULT_LOCKED)
        // The browser serves the pages the user keeps Keyguard away from.
        if (dependencies.isBlocked(page)) fail(BrowserError.NOT_SUPPORTED)
        val vault = Vault(
            key = key,
            dependencies = dependencies,
            // The user typed the master password, or used biometrics, for this request.
            freshUnlock = locked && key.origin == MasterSession.Key.Authenticated,
            grantsEpoch = approvals.grantsEpoch(),
        )
        return try {
            passkeys.vaultSessionLocker.withSessionLease(key) { block(vault) }
        } catch (_: CancellationException) {
            // The session of the extension ended, or the extension cancelled the request.
            currentCoroutineContext().ensureActive()
            // The vault locked, which ended the lease.
            BrowserReply.Error(BrowserError.VAULT_LOCKED)
        } catch (e: BrowserRequestException) {
            // Locking the vault denies its prompts too, which may end the request before the lease does.
            if (vaultAccess.currentKey() !== key) BrowserReply.Error(BrowserError.VAULT_LOCKED) else throw e
        }
    }

    /** Answers with [block], records the answer, and answers a refusal with its error. */
    private suspend fun recorded(
        type: CliAgentUsageHistoryRequestType,
        block: suspend (BrowserUsage) -> BrowserReply,
    ): BrowserReply = requestLog.recorded(session, pairing, type, block)

    /**
     * Checks that the request goes on in the vault it started in, and that
     * the user didn't block the [page] while they were asked.
     */
    private suspend fun Vault.requireCurrent(page: BrowserPage) {
        // A cancelled request stores and signs nothing.
        currentCoroutineContext().ensureActive()
        if (vaultAccess.currentKey() !== key) fail(BrowserError.VAULT_LOCKED)
        // Blocked meanwhile: the browser serves the page, as for one blocked before the request.
        if (dependencies.isBlocked(page)) fail(BrowserError.NOT_SUPPORTED)
    }

    /**
     * The [rpId] if its site is not the [page]'s [site]: a relying party of
     * another site that lists the page among its related origins. Otherwise `null`.
     */
    private suspend fun findOtherSiteRpId(
        page: BrowserPage,
        site: String,
        rpId: String,
    ): String? {
        val rpSite = BrowserPage.parse("${page.scheme}://$rpId")?.site(tldService)
        return rpId.takeIf { rpSite != site }
    }

    /**
     * Whether the user confirms their identity for the page's
     * [userVerification]: unless it is `discouraged`, or the user unlocked
     * the vault for this request. Without one, WebAuthn's default is `preferred`.
     */
    private fun Vault.verifies(userVerification: String?): Boolean =
        userVerification != USER_VERIFICATION_DISCOURAGED && !freshUnlock

    /**
     * The login of the [pick] in [ciphers] that stores the new passkey. One
     * that is gone is `invalid_request`; one the user can't edit, `not_allowed`.
     */
    private suspend fun Vault.storing(
        pick: Pick,
        ciphers: List<DSecret>,
    ): DSecret {
        val cipher = ciphers.firstOrNull { pick.matches(it) && !it.deleted && !it.archived }
            ?: fail(BrowserError.INVALID_REQUEST)
        val canWrite = dependencies.getCanWrite().first()
        val collections = dependencies.getCollections().first()
        if (!cipher.canStorePasskey(canWrite, collections)) fail(BrowserError.NOT_ALLOWED)
        return cipher
    }

    /**
     * The visible login of the [pick] and its passkey, as they are now, if
     * the passkey can answer the [target]. One that is gone is
     * `invalid_request`, as for `fill`; one the page doesn't allow,
     * `not_allowed`.
     */
    private suspend fun Vault.answering(
        pick: Pick,
        target: PasskeyTarget,
    ): Pair<DSecret, DSecret.Login.Fido2Credentials> {
        val passkey = passkey(pick)
            ?: fail(BrowserError.INVALID_REQUEST)
        val (_, credential) = passkey
        if (!credential.toWebAuthnCredential().canAnswer(target.rpId, target.allowed)) {
            fail(BrowserError.NOT_ALLOWED)
        }
        return passkey
    }

    /** The vault that serves a request. */
    private class Vault(
        val key: MasterSession.Key,
        val dependencies: BrowserExtensionSessionDependencies,
        /** The user unlocked the vault during the request, which verified them. */
        val freshUnlock: Boolean,
        /** The grants before the user was asked, see [BrowserApprovals.grantsEpoch]. */
        val grantsEpoch: String,
    ) {
        /** The visible login of the [pick] and its passkey, as they are now. */
        suspend fun passkey(pick: Pick): Pair<DSecret, DSecret.Login.Fido2Credentials>? {
            val cipher = dependencies.logins().firstOrNull(pick::matches)
            val credential = cipher?.login?.fido2Credentials
                ?.firstOrNull { it.credentialId == pick.credentialId }
            return credential?.let { checkNotNull(cipher) to it }
        }
    }

    /** An item the user picked, and for signing in, its passkey. */
    private data class Pick(
        val accountId: String,
        val cipherId: String,
        val credentialId: String? = null,
        /** Picked in the vault window, which approves it; not in the inline menu. */
        val fromWindow: Boolean = false,
        /** The vault window confirmed the user's identity before the pick: an item with master-password re-prompt. */
        val identityConfirmed: Boolean = false,
    ) {
        fun matches(cipher: DSecret): Boolean = cipher.accountId == accountId && cipher.id == cipherId
    }

    private sealed interface PickAnswer {
        data class Picked(val pick: Pick) : PickAnswer

        data object AnotherDevice : PickAnswer

        data object Dismissed : PickAnswer
    }

    /** The `mediation` of a `passkey_get` in version 1. */
    private enum class Mediation(val wireName: String) {
        SILENT("silent"),
        OPTIONAL("optional"),
        CONDITIONAL("conditional"),
        REQUIRED("required"),
        ;

        companion object {
            /** The mediation of [wireName], or `null` for one version 1 doesn't know. */
            fun of(wireName: String): Mediation? = entries.firstOrNull { it.wireName == wireName }
        }
    }

    /** The `PublicKeyCredentialRequestOptionsJSON` of a `passkey_get`. */
    private class GetOptions(
        val challenge: ByteArray,
        val rpId: String?,
        val userVerification: String?,
        val allowed: WebAuthnAllowedCredentialDescriptors,
        val prf: WebAuthnPrfAssertionInputs?,
    ) {
        companion object {
            fun parse(options: JsonObject): GetOptions {
                val challenge = options.string("challenge")
                    ?.let { webAuthn { PasskeyBase64.decode(it) } }
                    ?: fail(BrowserError.INVALID_REQUEST)
                val allowed = webAuthn { parseWebAuthnAllowedCredentialDescriptors(options) }
                return GetOptions(
                    challenge = challenge,
                    rpId = options.string("rpId"),
                    userVerification = options.string("userVerification"),
                    allowed = allowed,
                    prf = webAuthn { parseWebAuthnPrfAssertionInputs(options, allowed) },
                )
            }

            /** The string member [key]; one of another type is malformed. */
            private fun JsonObject.string(key: String): String? {
                val value = get(key)
                    ?: return null
                return (value as? JsonPrimitive)
                    ?.takeIf { it.isString }
                    ?.content
                    ?: fail(BrowserError.INVALID_REQUEST)
            }
        }
    }

    private companion object {
        const val USER_VERIFICATION_DISCOURAGED = "discouraged"

        /** The authenticator lives in the browser's host computer, as a platform one does. */
        const val TRANSPORT_INTERNAL = "internal"
    }
}

/** The `PublicKeyCredentialCreationOptionsJSON` of a `passkey_create`, checked before anything is asked. */
private fun parseCreateOptions(options: JsonObject): CreatePasskey {
    val data = try {
        BrowserJson.decodeFromJsonElement<CreatePasskey>(options.withoutUnknownAttestation())
    } catch (_: SerializationException) {
        fail(BrowserError.INVALID_REQUEST)
    } catch (_: IllegalArgumentException) {
        fail(BrowserError.INVALID_REQUEST)
    }
    webAuthn {
        decodeExcludedCredentialIds(data)
        data.requireWebAuthnPrfRegistrationInputs()
    }
    // Stored once the user consents: checked before anything is asked.
    val userHandle = webAuthn {
        PasskeyBase64.decode(data.challenge)
        PasskeyBase64.decode(data.user.id)
    }
    if (userHandle.size !in 1..MAX_USER_HANDLE_SIZE) fail(BrowserError.INVALID_REQUEST)
    return data
}

/** The largest user handle, in bytes: https://www.w3.org/TR/webauthn-3/#dom-publickeycredentialuserentity-id */
private const val MAX_USER_HANDLE_SIZE = 64

/**
 * The options without an `attestation` that WebAuthn doesn't know: a client
 * ignores it, so the default, `"none"`, applies.
 * https://www.w3.org/TR/webauthn-3/#dom-publickeycredentialcreationoptions-attestation
 */
private fun JsonObject.withoutUnknownAttestation(): JsonObject {
    val attestation = get("attestation") as? JsonPrimitive
    val unknown = attestation != null && attestation.isString && attestation.content !in ATTESTATION_PREFERENCES
    return if (unknown) JsonObject(this - "attestation") else this
}

/**
 * The values of `AttestationConveyancePreference` that the options decode to:
 * https://www.w3.org/TR/webauthn-3/#enum-attestation-convey
 */
private val ATTESTATION_PREFERENCES: Set<String> = CreatePasskeyAttestation.serializer().descriptor
    .let { descriptor -> (0 until descriptor.elementsCount).mapTo(mutableSetOf(), descriptor::getElementName) }

private fun credentialReply(json: String): BrowserReply {
    val credential = BrowserJson.parseToJsonElement(json).jsonObject
    return browserOk(PasskeyResponse(credential = credential))
}

/**
 * The page of the [origin] of a request, which `clientDataJSON` names as it
 * is. It must be an origin as a browser serializes it; any other form, such
 * as one with a trailing `/`, the default port or an uppercase host, is `security`.
 */
private fun originPage(origin: String): BrowserPage = BrowserPage.parseOrigin(origin)
    // A browser writes it in lowercase ASCII, with the host in Punycode; the parser keeps the case and Unicode.
    ?.takeIf { it.origin == origin && origin == origin.lowercase() && origin.all { c -> c in '!'..'~' } }
    ?: fail(BrowserError.SECURITY)

/** Ends the request with the [error]. */
private fun fail(error: BrowserError): Nothing = throw BrowserRequestException(error)

/** Runs a step of WebAuthn; its failure ends the request with the matching error. */
private inline fun <T> webAuthn(block: () -> T): T = try {
    block()
} catch (e: WebAuthnException) {
    fail(e.browserError())
} catch (_: IllegalArgumentException) {
    // A malformed member of the options.
    fail(BrowserError.INVALID_REQUEST)
}

/** The error of README section 9 that the page learns as the DOM exception of [this]. */
private fun WebAuthnException.browserError(): BrowserError = when (this) {
    is WebAuthnInvalidStateException -> BrowserError.INVALID_STATE
    is WebAuthnNotSupportedException -> BrowserError.NOT_SUPPORTED
    is WebAuthnNotAllowedException -> BrowserError.NOT_ALLOWED
    is WebAuthnEncodingException,
    is WebAuthnSyntaxException,
    -> BrowserError.INVALID_REQUEST
}
