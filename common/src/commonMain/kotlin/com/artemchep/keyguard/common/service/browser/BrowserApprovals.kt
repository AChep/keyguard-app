package com.artemchep.keyguard.common.service.browser

import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.left
import arrow.core.right
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.service.agent.AGENT_APPROVAL_WINDOW_NEVER_ASK
import com.artemchep.keyguard.common.service.agent.AgentApprovalBroker
import com.artemchep.keyguard.common.service.agent.AgentApprovalCacheConfigProvider
import com.artemchep.keyguard.common.service.agent.AgentApprovalCachePolicy
import com.artemchep.keyguard.common.service.agent.AgentApprovalSelections
import com.artemchep.keyguard.common.service.agent.AgentApprovalWindowMemory
import com.artemchep.keyguard.common.service.agent.denyWhenSessionEnds
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.feature.browserextension.protocol.BrowserError
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
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
@Suppress("LongParameterList", "TooManyFunctions") // One dependency, and one function, per concern of the prompts.
internal class BrowserApprovals(
    getVaultSession: GetVaultSession,
    private val vaultAccess: BrowserVaultAccess,
    private val cryptoGenerator: CryptoGenerator,
    /** How long a paired browser keeps an approval to fill an item on a site. */
    private val approvalCacheConfig: AgentApprovalCacheConfigProvider<AgentApprovalCachePolicy>,
    /** How long a prompt waits for the user. */
    val approvalTimeout: suspend () -> Duration,
    scope: CoroutineScope,
    /** The clock of the approval window and of the [identity checks][hasRecentIdentityCheck]. */
    private val timeSource: TimeSource,
) {
    private val broker = AgentApprovalBroker<BrowserApprovalRequest>(
        idOf = { it.id },
        promptTimeout = approvalTimeout,
    )

    /** The prompts waiting for the user. */
    val prompts = broker.pending

    /** The passkey the user selected in each prompt, and whether they confirmed their identity for it. */
    private val selections = AgentApprovalSelections(isPending = { id -> prompts.value.any { it.id == id } })

    /** What else the user gave each prompt, by prompt id, from when it is asked until it ends. */
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

    /** The last identity check of each pairing, by pairing id, see [hasRecentIdentityCheck]. */
    private val identityChecks = MutableStateFlow<Map<String, IdentityCheck>>(emptyMap())

    init {
        // Prompts need an unlocked vault, except the unlock prompt;
        // locking it denies them.
        scope.launch { broker.denyWhenSessionEnds(getVaultSession()) }
        // Locking the vault forgets the checks of its session.
        scope.launch {
            getVaultSession().collect { session ->
                identityChecks.update { checks -> checks.filterValues { it.key === session } }
            }
        }
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

    /** See [BrowserExtensionService.selectPasskey]. */
    fun selectPasskey(id: String, choice: Int): Boolean =
        // Only a pick prompt has passkeys to select.
        choicesOf(id) != null && selections.select(id, choice)

    /** See [BrowserExtensionService.useAnotherDevice]. */
    fun useAnotherDevice(id: String): Boolean {
        val pending = choicesOf(id) != null
        if (pending) updateInput(id) { copy(anotherDevice = true) }
        return pending
    }

    /** See [BrowserExtensionService.confirmIdentity]. */
    suspend fun confirmIdentity(id: String, verify: suspend () -> Boolean): Boolean =
        selections.confirmIdentity(id, verify)

    /** The choices of the pending [BrowserOperation.PasskeyPick] prompt [id], or `null`. */
    private fun choicesOf(id: String): List<BrowserPasskeyChoice>? = prompts.value
        .firstOrNull { it.id == id }
        ?.operation
        .let { it as? BrowserOperation.PasskeyPick }
        ?.choices

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
     *   to approve it; approving without that denies it. Each choice of a
     *   [BrowserOperation.PasskeyPick] carries its own; this is that of its first choice,
     *   shown before any selection.
     */
    suspend fun ask(
        session: BrowserExtensionSession,
        pairingName: String?,
        operation: BrowserOperation,
        requiresIdentity: Boolean = false,
    ): BrowserApproval {
        val id = cryptoGenerator.uuid()
        var input = PromptInput()
        inputs.update { it + (id to input) }
        val requiresIdentityOfChoices = (operation as? BrowserOperation.PasskeyPick)
            ?.choices?.map { it.requiresIdentity }
            ?: listOf(requiresIdentity)
        val answer = try {
            selections.track(id, requiresIdentity = requiresIdentityOfChoices) {
                broker.request { expiresAt ->
                    BrowserApprovalRequest(
                        id = id,
                        session = session,
                        pairingName = pairingName,
                        expiresAt = expiresAt,
                        operation = operation,
                        requiresIdentity = requiresIdentity,
                    )
                }
            }
        } finally {
            inputs.update { map ->
                map[id]?.let { input = it }
                map - id
            }
        }
        return when (answer) {
            is AgentApprovalSelections.Answer.Approved -> {
                BrowserApproval.Approved(input.name, input.addUri, answer.choice)
            }

            AgentApprovalSelections.Answer.Denied -> if (input.anotherDevice) {
                BrowserApproval.AnotherDevice
            } else {
                BrowserApproval.Denied
            }
            AgentApprovalSelections.Answer.Expired -> BrowserApproval.Expired
        }
    }

    /**
     * Confirms the release of an item to a page, prompting the user unless
     * the pairing has a remembered approval of an equal [grant], or the
     * approval window is "Never ask". An item with master-password
     * [reprompt] always prompts, requires the user to confirm their
     * identity, and is never remembered.
     *
     * @param key The vault session of the release; it must be the current
     *   one before and after the prompt.
     * @param verify The page wants user verification. A
     *   [recent identity check][hasRecentIdentityCheck] of the pairing
     *   verifies the user: a grant covers the release, otherwise the prompt
     *   doesn't ask for the identity. Without one, the release prompts and
     *   requires the user to confirm their identity, which starts a check.
     *   An approved release always verified the user. Remembered for later
     *   releases that don't verify.
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
        key: MasterSession.Key,
        session: BrowserExtensionSession,
        pairing: BrowserPairing,
        grant: BrowserGrant,
        reprompt: Boolean,
        verify: Boolean = false,
        matched: Boolean = true,
        crossSite: Boolean = false,
        operation: (approvalWindow: Duration) -> BrowserOperation,
    ): Either<BrowserError, BrowserApproval.Approved> {
        if (!vaultAccess.isCurrent(key)) return BrowserError.VAULT_LOCKED.left()
        val epoch = generation.value
        // Neither "Never ask" nor a grant covers these, and approving them grants nothing.
        val coverable = matched && !crossSite
        val access = if (coverable) grants.getOrGenerateSession(key).access { grant } else null
        val requiresIdentity = !isIdentityCovered(key, pairing.id, reprompt, verify)
        if (access?.isRemembered == true && !requiresIdentity) return BrowserApproval.Approved(name = null).right()
        val approvalWindow = if (coverable) approvalCacheConfig.get().approvalWindow else Duration.ZERO
        val approval = ask(
            session = session,
            pairingName = pairing.name,
            operation = operation(approvalWindow),
            requiresIdentity = requiresIdentity,
        ).settled(key, epoch).getOrElse { return it.left() }
        if (!reprompt) access?.remember()
        // A re-prompt confirms the identity as well.
        if (requiresIdentity) rememberIdentityCheck(key, pairing.id, epoch)
        return approval.right()
    }

    /**
     * Lets the user pick one of the passkeys of [releases] to sign in with,
     * and approve it. Always prompts: the pick is the user's gesture of the
     * sign-in, so no grant covers it. Approving a passkey remembers its
     * release as [approveRelease] does, and a passkey with master-password
     * re-prompt, or a page that wants user verification, requires the user
     * to confirm their identity, see [isIdentityCovered].
     *
     * @param key The vault session of the sign-in; it must be the current
     *   one before and after the prompt.
     * @param verify The page wants user verification.
     * @param releases The releases of the passkeys, given the approval
     *   window; not empty, the most recently used first.
     * @return The approval, whose [choice][BrowserApproval.Approved.choice]
     *   is the index of the picked release; otherwise why not.
     */
    @Suppress("ReturnCount") // Each refusal has its own error code.
    suspend fun approvePasskeyPick(
        key: MasterSession.Key,
        session: BrowserExtensionSession,
        pairing: BrowserPairing,
        verify: Boolean,
        releases: (approvalWindow: Duration) -> List<BrowserRelease>,
    ): Either<BrowserError, BrowserApproval.Approved> {
        if (!vaultAccess.isCurrent(key)) return BrowserError.VAULT_LOCKED.left()
        val epoch = generation.value
        // The same for every passkey; see isIdentityCovered.
        val verified = !verify || hasRecentIdentityCheck(key, pairing.id)
        val choices = releases(approvalCacheConfig.get().approvalWindow).map { release ->
            BrowserPasskeyChoice(release, requiresIdentity = release.reprompt || !verified)
        }
        val approval = ask(
            session = session,
            pairingName = pairing.name,
            operation = BrowserOperation.PasskeyPick(choices),
            requiresIdentity = choices.first().requiresIdentity,
        ).settled(key, epoch).getOrElse { return it.left() }
        val chosen = choices[approval.choice]
        if (!chosen.release.reprompt) {
            val grant = BrowserGrant(
                pairingId = pairing.id,
                site = chosen.release.site,
                accountId = chosen.release.accountId,
                cipherId = chosen.release.cipherId,
            )
            grants.getOrGenerateSession(key).access { grant }.remember()
        }
        // A re-prompt confirms the identity as well.
        if (chosen.requiresIdentity) rememberIdentityCheck(key, pairing.id, epoch)
        return approval.right()
    }

    /**
     * Whether a release of an item to the pairing [pairingId] in the vault
     * session [key] may go without the user confirming their identity: never
     * for an item with master-password [reprompt]; for a page that wants
     * user verification, [verify], only with a
     * [recent identity check][hasRecentIdentityCheck].
     */
    suspend fun isIdentityCovered(
        key: MasterSession.Key,
        pairingId: String,
        reprompt: Boolean,
        verify: Boolean,
    ): Boolean = !reprompt && (!verify || hasRecentIdentityCheck(key, pairingId))

    /**
     * Whether the user of the pairing [pairingId] confirmed their identity in
     * the vault session [key], with the grants of now, within the
     * [identity check window][identityCheckWindow]. Checked when deciding
     * whether to ask, with the window of now.
     */
    private suspend fun hasRecentIdentityCheck(
        key: MasterSession.Key,
        pairingId: String,
    ): Boolean {
        val check = identityChecks.value[pairingId]
            ?: return false
        val current = vaultAccess.isCurrent(key) && check.key === key && check.epoch == generation.value
        return current && check.at.elapsedNow() < identityCheckWindow()
    }

    /**
     * Remembers that the user of the pairing [pairingId] confirmed their
     * identity now: in a prompt, in the vault window, or by unlocking the
     * vault. Nothing is remembered once the vault [key] or the grants of
     * [epoch] were replaced.
     */
    fun rememberIdentityCheck(
        key: MasterSession.Key,
        pairingId: String,
        epoch: String,
    ) {
        if (!vaultAccess.isCurrent(key) || generation.value != epoch) return
        val check = IdentityCheck(key = key, epoch = epoch, at = timeSource.markNow())
        identityChecks.update { it + (pairingId to check) }
    }

    /**
     * How long an identity check verifies the user: the approval window, up
     * to [BROWSER_IDENTITY_CHECK_MAX_WINDOW]; "Never ask" is that maximum.
     */
    private suspend fun identityCheckWindow(): Duration {
        val approvalWindow = approvalCacheConfig.get().approvalWindow
        return when {
            approvalWindow == AGENT_APPROVAL_WINDOW_NEVER_ASK -> BROWSER_IDENTITY_CHECK_MAX_WINDOW
            approvalWindow <= Duration.ZERO -> Duration.ZERO
            else -> minOf(approvalWindow, BROWSER_IDENTITY_CHECK_MAX_WINDOW)
        }
    }

    /** Identifies the remembered releases; another value means they were revoked meanwhile. */
    fun grantsEpoch(): String = generation.value

    /**
     * This approval, unless the user refused it, or it belongs to a vault
     * session [key] or to grants of [epoch] that were replaced while the
     * user was asked.
     */
    private suspend fun BrowserApproval.settled(
        key: MasterSession.Key,
        epoch: String,
    ): Either<BrowserError, BrowserApproval.Approved> = when (this) {
        is BrowserApproval.Refused -> error.left()
        is BrowserApproval.Approved -> {
            currentCoroutineContext().ensureActive()
            val current = vaultAccess.isCurrent(key) && generation.value == epoch
            if (current) right() else BrowserError.VAULT_LOCKED.left()
        }
    }

    /** Revokes every remembered release and identity check. */
    suspend fun invalidate() {
        grants.invalidateAll()
        generation.value = cryptoGenerator.uuid()
        identityChecks.value = emptyMap()
    }

    /** The user confirmed their identity in the vault session [key], with the grants of [epoch], at [at]. */
    private class IdentityCheck(
        val key: MasterSession.Key,
        val epoch: String,
        val at: TimeMark,
    )
}

/**
 * The longest time an identity check verifies the user for the passkey
 * requests of the same pairing that want user verification. Short: the
 * check stands in for the user verification that WebAuthn reports to the
 * page, and the approval window may be hours or "Never ask".
 */
internal val BROWSER_IDENTITY_CHECK_MAX_WINDOW: Duration = 30.seconds

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
     * @param choice The index of the passkey the user picked, see [BrowserOperation.PasskeyPick].
     */
    data class Approved(
        val name: String?,
        val addUri: Boolean = false,
        val choice: Int = 0,
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

    /** The user handed the sign-in back to the browser, see [BrowserExtensionService.useAnotherDevice]. */
    data object AnotherDevice : Refused {
        override val error: BrowserError get() = BrowserError.NOT_SUPPORTED
    }
}

/** What the user gave a prompt before answering it, besides the selected passkey. */
private data class PromptInput(
    /** Denying hands the request back to the browser, see [BrowserExtensionService.useAnotherDevice]. */
    val anotherDevice: Boolean = false,
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
