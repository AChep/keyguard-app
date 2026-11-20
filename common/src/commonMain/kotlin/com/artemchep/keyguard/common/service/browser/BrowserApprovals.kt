package com.artemchep.keyguard.common.service.browser

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.service.agent.AgentApprovalBroker
import com.artemchep.keyguard.common.service.agent.AgentApprovalCacheConfigProvider
import com.artemchep.keyguard.common.service.agent.AgentApprovalCachePolicy
import com.artemchep.keyguard.common.service.agent.AgentApprovalWindowMemory
import com.artemchep.keyguard.common.service.agent.denyWhenSessionEnds
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.feature.browserextension.protocol.BrowserError
import kotlin.time.Duration
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The prompts of the sessions of the extensions, and the releases a pairing
 * may repeat without asking. App-scoped.
 */
@Suppress("LongParameterList") // One dependency per concern of the prompts.
internal class BrowserApprovals(
    getVaultSession: GetVaultSession,
    private val vaultAccess: BrowserVaultAccess,
    private val cryptoGenerator: CryptoGenerator,
    /** How long a paired browser keeps an approval to fill an item on a site. */
    private val approvalCacheConfig: AgentApprovalCacheConfigProvider<AgentApprovalCachePolicy>,
    /** How long a prompt waits for the user. */
    val approvalTimeout: suspend () -> Duration,
    scope: CoroutineScope,
    timeSource: TimeSource,
) {
    private val broker = AgentApprovalBroker<BrowserApprovalRequest>(
        idOf = { it.id },
        promptTimeout = approvalTimeout,
    )

    /** The prompts waiting for the user. */
    val prompts = broker.pending

    /** What the user gave each prompt, by prompt id, from when it is asked until it ends. */
    private val inputs = MutableStateFlow<Map<String, PromptInput>>(emptyMap())

    /**
     * The releases a pairing may repeat without asking, for the approval
     * window. The cache policy of the configuration is unused: the grants
     * are keyed by the pairing, the site and the item.
     */
    private val grants = AgentApprovalWindowMemory<BrowserGrant, AgentApprovalCachePolicy>(
        approvalCacheConfig = approvalCacheConfig,
        getVaultSession = getVaultSession,
        scope = scope,
        timeSource = timeSource,
    )

    /** Replaced whenever the grants are revoked; an approval given meanwhile is never remembered. */
    private val generation = MutableStateFlow(cryptoGenerator.uuid())

    init {
        // Prompts need an unlocked vault, except the unlock prompt;
        // locking it denies them.
        scope.launch { broker.denyWhenSessionEnds(getVaultSession()) }
    }

    /** See [BrowserExtensionService.respondToApproval]. */
    fun respond(id: String, approved: Boolean): Boolean = broker.respond(id, approved)

    /** See [BrowserExtensionService.namePairing]. */
    fun namePairing(id: String, name: String): Boolean {
        val pending = prompts.value.any { it.id == id && it.operation is BrowserOperation.Pair }
        if (pending) updateInput(id) { copy(name = name) }
        return pending
    }

    /** See [BrowserExtensionService.setAddUri]. */
    fun setAddUri(id: String, add: Boolean): Boolean {
        val pending = prompts.value.any { prompt ->
            prompt.id == id && (prompt.operation as? BrowserOperation.Release)?.release?.addUri != null
        }
        if (pending) updateInput(id) { copy(addUri = add) }
        return pending
    }

    /** See [BrowserExtensionService.confirmIdentity]. */
    suspend fun confirmIdentity(id: String, verify: suspend () -> Boolean): Boolean = when {
        prompts.value.none { it.id == id } -> false
        inputs.value[id]?.requiresIdentity != true -> true
        else -> {
            // The prompt may end while the user confirms their identity.
            val verified = verify() && prompts.value.any { it.id == id }
            if (verified) updateInput(id) { copy(verified = true) }
            verified
        }
    }

    /** Changes the input of the prompt [id], unless it ended. */
    private fun updateInput(id: String, transform: PromptInput.() -> PromptInput) {
        inputs.update { map ->
            val input = map[id]
                ?: return@update map
            map + (id to input.transform())
        }
    }

    /**
     * Shows the prompt of [operation] for [session] and waits for the user.
     *
     * @param requiresIdentity Whether the user must [confirm their identity][confirmIdentity]
     *   to approve it; approving without that denies it.
     */
    suspend fun ask(
        session: BrowserExtensionSession,
        pairingName: String?,
        operation: BrowserOperation,
        requiresIdentity: Boolean = false,
    ): BrowserApproval {
        val id = cryptoGenerator.uuid()
        var input = PromptInput(requiresIdentity = requiresIdentity)
        inputs.update { it + (id to input) }
        val result = try {
            broker.request { expiresAt ->
                BrowserApprovalRequest(
                    id = id,
                    session = session,
                    pairingName = pairingName,
                    expiresAt = expiresAt,
                    operation = operation,
                )
            }
        } finally {
            inputs.update { map ->
                map[id]?.let { input = it }
                map - id
            }
        }
        return when (result) {
            AgentApprovalBroker.Result.Approved -> if (requiresIdentity && !input.verified) {
                BrowserApproval.Denied
            } else {
                BrowserApproval.Approved(input.name, input.addUri)
            }

            AgentApprovalBroker.Result.Denied -> BrowserApproval.Denied
            AgentApprovalBroker.Result.Expired -> BrowserApproval.Expired
        }
    }

    /**
     * Confirms the release of an item to a page, prompting the user unless
     * the pairing has a remembered approval of an equal [grant], or the
     * approval window is "Never ask". An item with master-password
     * [reprompt] always prompts, requires the user to confirm their
     * identity, and is never remembered.
     *
     * @param verify The page wants user verification: the release always
     *   prompts and requires the user to confirm their identity, but is
     *   remembered for later releases that don't.
     * @param matched Whether the item matched the page. An unmatched release
     *   always prompts, with an approval window of zero, and is never
     *   remembered; no grant covers it.
     * @param crossSite The page is a frame of another site's page, see
     *   [BrowserRelease.topSite]. The release always prompts, as an unmatched one does.
     * @param operation What the prompt shows, given the approval window;
     *   built only when prompting.
     * @return The approval, otherwise why not.
     */
    @Suppress("ReturnCount", "LongParameterList") // Each refusal has its own error code.
    suspend fun approveRelease(
        session: BrowserExtensionSession,
        pairing: BrowserPairing,
        grant: BrowserGrant,
        reprompt: Boolean,
        verify: Boolean = false,
        matched: Boolean = true,
        crossSite: Boolean = false,
        operation: (approvalWindow: Duration) -> BrowserOperation,
    ): Either<BrowserError, BrowserApproval.Approved> {
        val key = vaultAccess.currentKey()
            ?: return BrowserError.VAULT_LOCKED.left()
        val epoch = generation.value
        // Neither "Never ask" nor a grant covers these, and approving them grants nothing.
        val coverable = matched && !crossSite
        val access = if (coverable) grants.getOrGenerateSession(key).access { grant } else null
        if (access?.isRemembered == true && !reprompt && !verify) return BrowserApproval.Approved(name = null).right()
        val approvalWindow = if (coverable) approvalCacheConfig.get().approvalWindow else Duration.ZERO
        val approval = ask(
            session = session,
            pairingName = pairing.name,
            operation = operation(approvalWindow),
            requiresIdentity = reprompt || verify,
        )
        when (approval) {
            is BrowserApproval.Approved -> Unit
            is BrowserApproval.Refused -> return approval.error.left()
        }
        currentCoroutineContext().ensureActive()
        // The vault is unlocked, but the approval belongs to a session or
        // to grants that were replaced while the user was asked.
        if (vaultAccess.currentKey() !== key || generation.value != epoch) return BrowserError.VAULT_LOCKED.left()
        if (!reprompt) access?.remember()
        return approval.right()
    }

    /** Identifies the remembered releases; another value means they were revoked meanwhile. */
    fun grantsEpoch(): String = generation.value

    /**
     * Remembers the release of an item to a page that the user approved
     * without a prompt, by picking the item, as approving a prompt does.
     * Nothing is remembered once the vault [key] or the grants of [epoch]
     * were replaced.
     */
    suspend fun rememberRelease(
        key: MasterSession.Key,
        epoch: String,
        grant: BrowserGrant,
    ) {
        if (vaultAccess.currentKey() !== key || generation.value != epoch) return
        grants.getOrGenerateSession(key)
            .access { grant }
            .remember()
    }

    /** Revokes every remembered release. */
    suspend fun invalidate() {
        grants.invalidateAll()
        generation.value = cryptoGenerator.uuid()
    }
}

/** An approval to release one item on one site to one pairing. */
internal data class BrowserGrant(
    val pairingId: String,
    /** See [BrowserPage.site]. */
    val site: String,
    val accountId: String,
    val cipherId: String,
)

/** The user's answer to a prompt of a browser extension. */
internal sealed interface BrowserApproval {
    /**
     * @param name The name the user gave a pairing, if any.
     * @param addUri The user asked to add the page to the item of the release.
     */
    data class Approved(
        val name: String?,
        val addUri: Boolean = false,
    ) : BrowserApproval

    /** The user didn't approve the prompt: its request ends with the [error]. */
    sealed interface Refused : BrowserApproval {
        val error: BrowserError
    }

    data object Denied : Refused {
        override val error: BrowserError get() = BrowserError.USER_DENIED
    }

    data object Expired : Refused {
        override val error: BrowserError get() = BrowserError.APPROVAL_TIMEOUT
    }
}

/** What the user gave a prompt before answering it. */
private data class PromptInput(
    /** Approving requires the user to [confirm their identity][BrowserApprovals.confirmIdentity]. */
    val requiresIdentity: Boolean,
    /** The user confirmed their identity. */
    val verified: Boolean = false,
    /** The name the user gave a pairing, see [BrowserExtensionService.namePairing]. */
    val name: String? = null,
    /** Approving adds the page to the item, see [BrowserExtensionService.setAddUri]. */
    val addUri: Boolean = false,
)

/** The release of this item to the [page] on [site]; the prompt shows the [username], if not empty. */
internal fun DSecret.toRelease(
    page: BrowserPage,
    site: String,
    username: String?,
    accountName: String?,
    approvalWindow: Duration,
    topSite: String? = null,
    userVerification: Boolean = false,
    matched: Boolean = true,
    addUri: String? = null,
): BrowserRelease = BrowserRelease(
    origin = page.origin,
    site = site,
    topSite = topSite,
    accountId = accountId,
    cipherId = id,
    name = name,
    username = username?.takeIf { it.isNotEmpty() },
    accountName = accountName,
    reprompt = reprompt,
    approvalWindow = approvalWindow,
    userVerification = userVerification,
    matched = matched,
    addUri = addUri,
)
