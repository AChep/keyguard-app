package com.artemchep.keyguard.common.service.browser

import com.artemchep.keyguard.feature.browserextension.protocol.BrowserProtocol
import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.coroutines.flow.StateFlow

/**
 * Application boundary of the browser extension integration, shared by the UI
 * of every platform. The extension reaches the app through the CLI agent, see
 * `browserExtension/protocol/README.md`.
 */
@Suppress("TooManyFunctions") // One function per action of the user.
interface BrowserExtensionService {
    val state: StateFlow<BrowserExtensionState>
    val approvals: StateFlow<List<BrowserApprovalRequest>>

    /** The logins that extensions ask to save, each shown in its own window until [closeSave]. */
    val saves: StateFlow<List<BrowserSaveRequest>>

    suspend fun setEnabled(enabled: Boolean)

    /**
     * Answers the pending prompt [id]. Approving a [BrowserOperation.Pair]
     * names the pairing as [namePairing] set it, or else keeps the suggested name.
     *
     * @return `true` if the prompt was still pending.
     */
    fun respondToApproval(id: String, approved: Boolean): Boolean

    /**
     * Names the pairing of the pending [BrowserOperation.Pair] prompt [id],
     * for when it is approved.
     *
     * @return `true` if the prompt was still pending.
     */
    fun namePairing(id: String, name: String): Boolean

    /**
     * Whether approving the pending release prompt [id] adds the page to
     * its item, see [BrowserRelease.addUri]. Unchecked until set.
     *
     * @return `true` if the prompt was still pending and offers it.
     */
    fun setAddUri(id: String, add: Boolean): Boolean

    /**
     * Confirms the user's identity for the pending prompt [id] with [verify],
     * if its operation [requires it][BrowserRelease.reprompt]. Returns
     * `false` if the user did not confirm it, or if the prompt is no longer
     * pending. Approving such a prompt without confirming denies it.
     */
    suspend fun confirmIdentity(id: String, verify: suspend () -> Boolean): Boolean

    /**
     * Selects the passkey [choice] of the pending [BrowserOperation.PasskeyPick]
     * prompt [id], for when it is approved. An identity confirmed for another
     * passkey no longer counts.
     *
     * @return `true` if the prompt was still pending and offers it.
     */
    fun selectPasskey(id: String, choice: Int): Boolean

    /**
     * Hands the pending [BrowserOperation.PasskeyPick] prompt [id] back to the
     * browser, which offers its own passkeys and devices, once it is denied.
     *
     * @return `true` if the prompt was still pending.
     */
    fun useAnotherDevice(id: String): Boolean

    /** The window of the save request [id] is on screen; the extension learns it opened. */
    fun onSaveShown(id: String)

    /** The window of the save request [id] closed, whether the user saved or not. */
    fun closeSave(id: String)

    /** Forgets the pairing [id]; its connected sessions learn it and end. */
    suspend fun removePairing(id: String)

    /**
     * Names the pairing [id] [name], trimmed and cut to
     * [BrowserProtocol.MAX_NAME_LENGTH] characters. A blank name changes nothing.
     */
    suspend fun renamePairing(id: String, name: String)

    /**
     * Adds [browsers] to the [removed connectors][BrowserExtensionState.removedConnectors],
     * or takes them out. Only records the choice: the caller installs or
     * removes the connectors.
     */
    suspend fun setConnectorsRemoved(browsers: Set<BrowserConnectorBrowser>, removed: Boolean)
}

/**
 * The side of the browser extension service that a platform runtime drives.
 * Screens use [BrowserExtensionService] instead.
 */
interface BrowserExtensionHost : BrowserExtensionService {
    /** @param supported Whether this build serves browser extensions. */
    fun configureRuntime(supported: Boolean)
}

data class BrowserExtensionState(
    /** Whether the user turned the integration on. */
    val enabled: Boolean = false,
    /** Whether this build serves browser extensions; until then the service is inert. */
    val supported: Boolean = false,
    /** The extensions connected now. */
    val sessions: List<BrowserExtensionSession> = emptyList(),
    /** The extensions the user paired. */
    val pairings: List<BrowserPairing> = emptyList(),
    /** The browsers whose connectors the user removed; Keyguard doesn't install them again. */
    val removedConnectors: Set<BrowserConnectorBrowser> = emptySet(),
) {
    /** Whether the integration needs the CLI agent that relays the extensions. */
    val needsAgent: Boolean
        get() = enabled && supported
}

/** A paired extension: the app knows its static key. */
data class BrowserPairing(
    val id: String,
    /** The name the user gave the browser when pairing. */
    val name: String,
    /** The extension's origin, see [BrowserExtensionSession.origin]. */
    val origin: String,
    val createdAt: Instant,
    val lastUsedAt: Instant,
)

/** One connected extension. */
data class BrowserExtensionSession(
    val id: String,
    /** `chrome-extension://<id>/` for a Chromium browser, or the ID of a Firefox extension. */
    val origin: String,
    /** The browser process that started the connector, for display. */
    val caller: BrowserCaller,
    /** Whether the agent verified that the connector is the bundled one. */
    val verifiedHost: Boolean,
    /** The pairing of the extension, `null` until it pairs. */
    val pairingId: String? = null,
)

/**
 * The browser process that started the connector. The process and its
 * application are verified by the operating system, unless the session
 * comes from an [unverified host][BrowserExtensionSession.verifiedHost].
 */
data class BrowserCaller(
    val pid: Int = 0,
    val processName: String = "",
    val executablePath: String = "",
    val appName: String = "",
    val appBundlePath: String = "",
)

data class BrowserApprovalRequest(
    val id: String,
    /** The session that asks. */
    val session: BrowserExtensionSession,
    /** The name of the session's pairing, `null` for an unpaired session. */
    val pairingName: String?,
    val expiresAt: Instant,
    val operation: BrowserOperation,
    /** Approving requires the user to confirm their identity, see [BrowserExtensionService.confirmIdentity]. */
    val requiresIdentity: Boolean = false,
)

/** What an extension asks the user for. */
sealed interface BrowserOperation {
    /**
     * Pairing the extension. The user compares the [code] with the one the
     * extension shows, and names the browser.
     */
    data class Pair(
        /** Six decimal digits. */
        val code: String,
        /** Prefilled from the verified browser, or else from the extension. */
        val suggestedName: String,
    ) : BrowserOperation

    /** Unlocking the vault; the prompt resolves itself once the vault unlocks. */
    data object Unlock : BrowserOperation

    /** Releasing a secret of an item to a page. */
    sealed interface Release : BrowserOperation {
        val release: BrowserRelease
    }

    /** Filling the username and the password of an item on a page. */
    data class Fill(
        override val release: BrowserRelease,
    ) : Release

    /** Filling a one-time password of an item on a page. */
    data class Totp(
        override val release: BrowserRelease,
    ) : Release

    /**
     * Signing in to a page with a passkey of an item. The username of the
     * [release] is the passkey's user name.
     */
    data class PasskeyGet(
        override val release: BrowserRelease,
    ) : Release

    /**
     * Signing in to a page with one of the [choices], which the user picks.
     * Not empty; the most recently used first.
     */
    data class PasskeyPick(
        val choices: List<BrowserPasskeyChoice>,
    ) : BrowserOperation

    /**
     * Saving a new passkey of a page in an item. The username of the
     * [release] is the user name of the new passkey. Only asked when the
     * page wants the user verified: picking the item approves it.
     */
    data class PasskeyCreate(
        override val release: BrowserRelease,
    ) : Release
}

/** One passkey of a [BrowserOperation.PasskeyPick]: what a [BrowserOperation.PasskeyGet] of it asks. */
data class BrowserPasskeyChoice(
    val release: BrowserRelease,
    /** Approving this passkey requires the user to confirm their identity. */
    val requiresIdentity: Boolean,
)

/** An item whose secrets an extension asks for, and where it fills them. */
data class BrowserRelease(
    /** The origin of the page, such as `https://login.example.com`. */
    val origin: String,
    /** The site of the page: its scheme and registrable domain, such as `https://example.com`. */
    val site: String,
    /**
     * The site of the tab's top page, when the page is a frame of another
     * site's page: the release always asks, and approving it is never remembered.
     */
    val topSite: String? = null,
    val accountId: String,
    val cipherId: String,
    val name: String,
    val username: String?,
    /** The account that holds the item, for display. */
    val accountName: String?,
    /**
     * The item asks for the master password: the user confirms their
     * identity, and the approval is never remembered.
     */
    val reprompt: Boolean,
    /**
     * How long approving covers this item on this site for the same
     * pairing: zero asks every time, infinite until the vault locks.
     */
    val approvalWindow: Duration,
    /**
     * The page wants user verification for a passkey, `required` or
     * `preferred`: the user confirms their identity, even when an approval
     * covers the item.
     */
    val userVerification: Boolean = false,
    /**
     * The relying party of a passkey, such as `example.org`, when its site
     * is not [site]: it lists the page among its related origins. For display only.
     */
    val otherSiteRpId: String? = null,
    /**
     * No URI of the item matches the page, which the user found by search:
     * the prompt warns, and approving it is never remembered.
     */
    val matched: Boolean = true,
    /**
     * The URI of the page, such as `https://login.example.com`, that
     * approving may add to an item that isn't [matched], see
     * [BrowserExtensionService.setAddUri]; `null` when the user can't edit it.
     */
    val addUri: String? = null,
)

/**
 * A login that an extension asks to save. The app opens a window with a new
 * item, or with the matching item and the new password; the user saves it.
 */
data class BrowserSaveRequest(
    val id: String,
    /** The session that asks. */
    val session: BrowserExtensionSession,
    /** The name of the session's pairing. */
    val pairingName: String?,
    /** The scheme of the page, such as `https`. */
    val webScheme: String,
    /** The host of the page. */
    val webDomain: String,
    val username: String?,
    val password: String?,
    /** The login of the same site and username to update; `null` for a new one. */
    val cipherId: String? = null,
)
