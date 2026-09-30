package com.artemchep.keyguard.apple.core

import com.artemchep.keyguard.apple.generator.EmailRelayListSession
import com.artemchep.keyguard.apple.generator.EmailRelayListSnapshot
import com.artemchep.keyguard.apple.generator.EmailRelayActionKind
import com.artemchep.keyguard.apple.generator.EmailRelayActionRequestSnapshot
import com.artemchep.keyguard.common.service.relays.EmailRelayRegistry
import com.artemchep.keyguard.common.usecase.GetEmailRelays
import com.artemchep.keyguard.res.duplicate
import com.artemchep.keyguard.apple.generator.WordlistListSession
import com.artemchep.keyguard.apple.generator.WordlistDetailSession
import com.artemchep.keyguard.apple.generator.WordlistListSnapshot
import com.artemchep.keyguard.apple.generator.WordlistDetailSnapshot
import com.artemchep.keyguard.apple.generator.WordlistActionRequestSnapshot
import com.artemchep.keyguard.common.usecase.GetWordlists
import com.artemchep.keyguard.common.usecase.NumberFormatter
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.apple.generator.WORDLIST_ACTION_RENAME
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.word_count_plural
import com.artemchep.keyguard.res.edit
import com.artemchep.keyguard.res.delete
import kotlinx.coroutines.flow.map
import com.artemchep.keyguard.common.model.DCipherFilter
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.DFilter
import com.artemchep.keyguard.feature.auth.bitwarden.BitwardenLoginRoute
import com.artemchep.keyguard.feature.auth.keepass.KeePassLoginRoute
import com.artemchep.keyguard.feature.export.ExportState
import com.artemchep.keyguard.feature.feedback.FeedbackState
import com.artemchep.keyguard.feature.generator.wordlist.WordlistsRoute
import com.artemchep.keyguard.feature.home.settings.accounts.model.AccountType
import com.artemchep.keyguard.feature.home.vault.VaultRoute
import com.artemchep.keyguard.feature.home.vault.add.AddRouteImpl
import com.artemchep.keyguard.feature.home.vault.search.sort.Sort
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.NavigationStackPersistence
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.feature.navigation.RouteDescriptor
import com.artemchep.keyguard.feature.navigation.RouteResultReceiver
import com.artemchep.keyguard.feature.navigation.routeDescriptorFromDeepLink
import com.artemchep.keyguard.feature.send.add.SendAddRoute
import com.artemchep.keyguard.apple.add.AddItemController
import com.artemchep.keyguard.apple.dialog.DialogController
import com.artemchep.keyguard.apple.filter.CipherFilterDetailSnapshot
import com.artemchep.keyguard.apple.filter.CipherFiltersController
import com.artemchep.keyguard.apple.filter.CipherFiltersListSnapshot
import com.artemchep.keyguard.apple.settings.FeedbackController
import com.artemchep.keyguard.apple.settings.FeedbackSnapshot
import com.artemchep.keyguard.apple.directory.DIRECTORY_KIND_DELETE_ACCOUNT
import com.artemchep.keyguard.apple.directory.DIRECTORY_KIND_GET_MY_DATA
import com.artemchep.keyguard.apple.directory.DIRECTORY_KIND_PASSKEYS
import com.artemchep.keyguard.apple.directory.DIRECTORY_KIND_TWO_FA
import com.artemchep.keyguard.apple.directory.ServiceDirectoryListSession
import com.artemchep.keyguard.apple.directory.ServiceDirectoryController
import com.artemchep.keyguard.apple.directory.ServiceDirectoryDetailSnapshot
import com.artemchep.keyguard.apple.directory.ServiceDirectorySnapshot
import com.artemchep.keyguard.apple.vault.CipherDetailController
import com.artemchep.keyguard.apple.vault.CollectionsController
import com.artemchep.keyguard.apple.vault.CollectionsSnapshot
import com.artemchep.keyguard.apple.vault.DownloadsController
import com.artemchep.keyguard.apple.vault.DownloadsSnapshot
import com.artemchep.keyguard.apple.vault.EquivalentDomainsController
import com.artemchep.keyguard.apple.vault.EquivalentDomainsSnapshot
import com.artemchep.keyguard.apple.vault.ExportController
import com.artemchep.keyguard.apple.vault.ExportSnapshot
import com.artemchep.keyguard.apple.vault.FoldersController
import com.artemchep.keyguard.apple.vault.FoldersSnapshot
import com.artemchep.keyguard.apple.vault.OrganizationsController
import com.artemchep.keyguard.apple.vault.OrganizationsSnapshot
import com.artemchep.keyguard.apple.vault.VaultDetailSnapshot
import com.artemchep.keyguard.apple.vault.VaultDetailTotpSnapshot
import com.artemchep.keyguard.apple.vault.DuplicatesSession
import com.artemchep.keyguard.apple.vault.VaultListSession
import com.artemchep.keyguard.feature.watchtower.alerts.WatchtowerAlertsRoute
import com.artemchep.keyguard.feature.watchtower.WatchtowerRoute
import com.artemchep.keyguard.apple.watchtower.WatchtowerSnapshot
import com.artemchep.keyguard.apple.watchtower.WatchtowerAlertsSnapshot
import com.artemchep.keyguard.apple.watchtower.WatchtowerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.koin.core.scope.Scope

/**
 * The kind of a navigation-stack entry, plus the args needed to (re)run its
 * producer. Internal to the bridge — Swift sees only the flat [ScreenEntrySnapshot].
 */
internal sealed interface ScreenKind {
    val entryKind: ScreenEntryKind
    val defaultTitle: String

    data class CipherDetail(
        val itemId: String,
        val accountId: String,
    ) : ScreenKind {
        override val entryKind get() = ScreenEntryKind.CIPHER_DETAIL
        override val defaultTitle get() = ""
    }

    data class VaultList(
        val args: VaultRoute.Args,
        val title: String,
    ) : ScreenKind {
        override val entryKind get() = ScreenEntryKind.VAULT_LIST
        override val defaultTitle get() = title
    }

    data class Watchtower(val args: WatchtowerRoute.Args) : ScreenKind {
        override val entryKind get() = ScreenEntryKind.WATCHTOWER
        override val defaultTitle get() = "Watchtower"
    }

    data class WatchtowerAlerts(
        val title: String,
        val args: WatchtowerAlertsRoute.Args,
    ) : ScreenKind {
        override val entryKind get() = ScreenEntryKind.WATCHTOWER_ALERTS
        override val defaultTitle get() = title
    }

    data class ServiceDirectoryList(
        val kind: String,
        val title: String,
    ) : ScreenKind {
        override val entryKind get() = ScreenEntryKind.SERVICE_DIRECTORY_LIST
        override val defaultTitle get() = title
    }

    data class ServiceDirectoryDetail(
        val kind: String,
        val itemId: String,
        val title: String,
    ) : ScreenKind {
        override val entryKind get() = ScreenEntryKind.SERVICE_DIRECTORY_DETAIL
        override val defaultTitle get() = title
    }

    // Self-observing screens: the Swift view drives its own model-backed
    // observation from the entry's scalar args, so the bridge launches no
    // per-entry producer (see [startEntry]).

    data object GeneratorHistory : ScreenKind {
        override val entryKind get() = ScreenEntryKind.GENERATOR_HISTORY
        override val defaultTitle get() = "History"
    }

    data object EmailRelayList : ScreenKind {
        override val entryKind get() = ScreenEntryKind.EMAIL_RELAY_LIST
        override val defaultTitle get() = "Email forwarders"
    }

    data object WordlistList : ScreenKind {
        override val entryKind get() = ScreenEntryKind.WORDLIST_LIST
        override val defaultTitle get() = "Wordlists"
    }

    data class WordlistDetail(
        val wordlistId: Long,
        val title: String,
    ) : ScreenKind {
        override val entryKind get() = ScreenEntryKind.WORDLIST_DETAIL
        override val defaultTitle get() = title
    }

    data class PasswordHistory(
        val itemId: String,
    ) : ScreenKind {
        override val entryKind get() = ScreenEntryKind.PASSWORD_HISTORY
        override val defaultTitle get() = "Password history"
    }

    /** The SSH agent history of one cipher; the Swift view sets its localized title. */
    data class SshAgentHistory(
        val cipherId: String?,
    ) : ScreenKind {
        override val entryKind get() = ScreenEntryKind.SSH_AGENT_HISTORY
        override val defaultTitle get() = ""
    }

    data class SendDetail(
        val sendId: String,
        val accountId: String,
    ) : ScreenKind {
        override val entryKind get() = ScreenEntryKind.SEND_DETAIL
        override val defaultTitle get() = ""
    }

    // Account groupings — each runs its shared screen-state producer headlessly
    // with the interceptor, so a row's "view items" fires the producer's own
    // filtered-vault navigation.

    data class OrganizationsList(
        val accountId: String,
        val title: String,
    ) : ScreenKind {
        override val entryKind get() = ScreenEntryKind.ORGANIZATIONS_LIST
        override val defaultTitle get() = title
    }

    data class CollectionsList(
        val accountId: String,
        val organizationId: String?,
        val title: String,
    ) : ScreenKind {
        override val entryKind get() = ScreenEntryKind.COLLECTIONS_LIST
        override val defaultTitle get() = title
    }

    data class FoldersList(
        // Null for the watchtower "empty folders" maintenance list (no account scope).
        val accountId: String?,
        val title: String,
        // Scope the list to empty folders only (the watchtower "empty folders" card).
        val empty: Boolean = false,
    ) : ScreenKind {
        override val entryKind get() = ScreenEntryKind.FOLDERS_LIST
        override val defaultTitle get() = title
    }

    data class AccountDetail(
        val accountId: String,
    ) : ScreenKind {
        override val entryKind get() = ScreenEntryKind.ACCOUNT_DETAIL
        override val defaultTitle get() = ""
    }

    data class EquivalentDomains(
        val accountId: String,
        val title: String,
    ) : ScreenKind {
        override val entryKind get() = ScreenEntryKind.EQUIVALENT_DOMAINS
        override val defaultTitle get() = title
    }

    data class Duplicates(
        val filter: DFilter?,
        val title: String,
    ) : ScreenKind {
        override val entryKind get() = ScreenEntryKind.DUPLICATES
        override val defaultTitle get() = title
    }

    data class Export(
        val title: String,
        val filter: DFilter?,
    ) : ScreenKind {
        override val entryKind get() = ScreenEntryKind.EXPORT
        override val defaultTitle get() = title
    }

    data class CipherFiltersList(
        val title: String,
    ) : ScreenKind {
        override val entryKind get() = ScreenEntryKind.CIPHER_FILTERS_LIST
        override val defaultTitle get() = title
    }

    data class CipherFilterDetail(
        val filterId: String,
        val title: String,
        val model: DCipherFilter? = null,
    ) : ScreenKind {
        override val entryKind get() = ScreenEntryKind.CIPHER_FILTER_DETAIL
        override val defaultTitle get() = title
    }

    data object Downloads : ScreenKind {
        override val entryKind get() = ScreenEntryKind.DOWNLOADS
        override val defaultTitle get() = "Downloads"
    }

    data object Feedback : ScreenKind {
        override val entryKind get() = ScreenEntryKind.FEEDBACK
        override val defaultTitle get() = "Contact us"
    }

    data object Subscriptions : ScreenKind {
        override val entryKind get() = ScreenEntryKind.SUBSCRIPTIONS
        override val defaultTitle get() = "Premium"
    }
}

/** Swift-facing discriminator for a navigation-stack entry. */
enum class ScreenEntryKind {
    CIPHER_DETAIL,
    VAULT_LIST,
    WATCHTOWER,
    WATCHTOWER_ALERTS,
    SERVICE_DIRECTORY_LIST,
    SERVICE_DIRECTORY_DETAIL,
    GENERATOR_HISTORY,
    EMAIL_RELAY_LIST,
    WORDLIST_LIST,
    WORDLIST_DETAIL,
    PASSWORD_HISTORY,
    SSH_AGENT_HISTORY,
    SEND_DETAIL,
    ORGANIZATIONS_LIST,
    COLLECTIONS_LIST,
    FOLDERS_LIST,
    ACCOUNT_DETAIL,
    EQUIVALENT_DOMAINS,
    DUPLICATES,
    EXPORT,
    CIPHER_FILTERS_LIST,
    CIPHER_FILTER_DETAIL,
    DOWNLOADS,
    FEEDBACK,
    SUBSCRIPTIONS,
}

/**
 * One entry of the navigation stack, as delivered to SwiftUI. [instanceId] is a
 * monotonic id (globally unique across scopes) that is never reused, so a late
 * action invocation for a popped entry resolves to nothing instead of hitting a
 * recycled entry. The per-entry content travels inline (one non-null field per
 * [kind]) so SwiftUI renders the whole stack from a single
 * [NavigationStackController.observeNavStack] feed.
 */
data class ScreenEntrySnapshot(
    val instanceId: Long,
    val kind: ScreenEntryKind,
    val title: String,
    val detail: VaultDetailSnapshot?,
    // The session a stacked vault-list entry owns (created + closed Kotlin-side).
    // Non-null only for VAULT_LIST entries; Swift subscribes it directly. The session
    // class is ObjC-exported, so the handle is bridgeable.
    val vaultListSession: VaultListSession?,
    val watchtower: WatchtowerSnapshot?,
    val watchtowerAlerts: WatchtowerAlertsSnapshot?,
    val serviceDirectory: ServiceDirectorySnapshot?,
    val serviceDirectoryDetail: ServiceDirectoryDetailSnapshot?,
    // Scalar args for the self-observing screens (the Swift view starts its own
    // model observation from these instead of reading an inline snapshot).
    val wordlistId: Long?,
    val wordlistList: WordlistListSnapshot?,
    val emailRelayList: EmailRelayListSnapshot?,
    val wordlistDetail: WordlistDetailSnapshot?,
    val passwordHistoryItemId: String?,
    val sshAgentHistoryCipherId: String?,
    val sendId: String?,
    val sendAccountId: String?,
    // Account-grouping screens (organizations / collections / folders).
    val organizations: OrganizationsSnapshot?,
    val collections: CollectionsSnapshot?,
    val folders: FoldersSnapshot?,
    val accountDetailId: String?,
    val equivalentDomains: EquivalentDomainsSnapshot?,
    // The session a Duplicates entry owns (created + closed Kotlin-side). Swift
    // subscribes it directly. ObjC-exported, so the handle is bridgeable — mirrors
    // [vaultListSession].
    val duplicatesSession: DuplicatesSession?,
    val export: ExportSnapshot?,
    val cipherFilters: CipherFiltersListSnapshot?,
    val cipherFilterDetail: CipherFilterDetailSnapshot?,
    val downloads: DownloadsSnapshot?,
    val feedback: FeedbackSnapshot?,
)

/**
 * The bridge-side navigation stack: one ordered list of screen instances **per
 * scope** (a section / tab — "vault", "watchtower", …), layered above each
 * section's selection-driven root. Each entry runs its own shared producer in its
 * own scope and owns its own snapshot + handler maps, so multiple screens of the
 * same type are alive at once, and each section keeps its own drill-down across
 * section/tab switches (no global clear).
 *
 * Pushes are driven by the shared producers: the cipher-detail / vault-list /
 * watchtower producers are handed [interceptor], which turns a full-screen
 * [NavigationIntent.NavigateToRoute] into a push onto the **current scope**
 * ([setScope], set by the shell when the visible section/tab changes), and
 * [NavigationIntent.Pop] into a pop. Dialog routes keep flowing to [DialogController].
 *
 * Lifetime nests as `core ⊃ session ⊃ entry`: one [startSession] gate owns the
 * session scope for ALL scopes (entries re-keyed on a vault-state change, torn down
 * on lock); each entry owns a child [Job] (cipher / vault producers) or its own
 * [KeyguardCancellable] (watchtower / directory entries reuse the session-gated
 * observers). All [stacks] / [sinks] access is main-confined.
 */
internal class NavigationStackController(
    private val ctx: CoreContext,
    private val dialogController: DialogController,
    private val cipherDetailController: CipherDetailController,
    private val watchtowerController: WatchtowerController,
    private val serviceDirectoryController: ServiceDirectoryController,
    private val organizationsController: OrganizationsController,
    private val collectionsController: CollectionsController,
    private val foldersController: FoldersController,
    private val equivalentDomainsController: EquivalentDomainsController,
    private val exportController: ExportController,
    private val cipherFiltersController: CipherFiltersController,
    private val downloadsController: DownloadsController,
    private val feedbackController: FeedbackController,
    private val addItemController: AddItemController,
) {
    private inner class ScreenEntry(
        val instanceId: Long,
        val kind: ScreenKind,
    ) {
        // Serializable identity of this entry, for state restoration. Null for the few
        // bridge-only screens with no RouteDescriptor yet (AccountDetail /
        // ServiceDirectoryDetail) — those are simply not restored.
        var descriptor: RouteDescriptor? = null
        var job: Job? = null
        var cancellable: KeyguardCancellable? = null
        var title: String = kind.defaultTitle
        var detail: VaultDetailSnapshot? = null
        // A stacked vault-list entry owns its session and Swift subscription.
        var vaultListSession: VaultListSession? = null
        var scopedWatchtowerController: WatchtowerController? = null
        var watchtower: WatchtowerSnapshot? = null
        var watchtowerAlerts: WatchtowerAlertsSnapshot? = null
        var directorySession: ServiceDirectoryListSession? = null
        val directoryQuery = MutableStateFlow(EntryListQuery())
        val wordlistQuery = MutableStateFlow(EntryListQuery())
        val wordlistSelection = MutableStateFlow(emptySet<String>())
        val emailRelaySelection = MutableStateFlow(emptySet<String>())
        var emailRelayListSession: EmailRelayListSession? = null
        var emailRelayList: EmailRelayListSnapshot? = null
        var wordlistListSession: WordlistListSession? = null
        var wordlistDetailSession: WordlistDetailSession? = null
        var wordlistList: WordlistListSnapshot? = null
        var wordlistDetail: WordlistDetailSnapshot? = null
        var serviceDirectory: ServiceDirectorySnapshot? = null
        var serviceDirectoryDetail: ServiceDirectoryDetailSnapshot? = null
        var organizations: OrganizationsSnapshot? = null
        var collections: CollectionsSnapshot? = null
        var folders: FoldersSnapshot? = null
        var equivalentDomains: EquivalentDomainsSnapshot? = null
        // A Duplicates entry OWNS its session (its own lock/unlock gate +
        // headless producer), created in [startEntry] and closed in [release] —
        // exactly like [vaultListSession] for the stacked vault lists.
        var duplicatesSession: DuplicatesSession? = null
        var export: ExportSnapshot? = null
        var exportState: ExportState? = null
        var cipherFilters: CipherFiltersListSnapshot? = null
        var cipherFilterDetail: CipherFilterDetailSnapshot? = null
        var downloads: DownloadsSnapshot? = null
        var feedback: FeedbackSnapshot? = null
        var feedbackState: FeedbackState? = null
        var actionHandlers: Map<String, () -> Unit> = emptyMap()
        var favourite: (() -> Unit)? = null

        /** Stops replaceable producers; independent vault/duplicates sessions remain alive. */
        fun stop() {
            job?.cancel()
            job = null
            setEntryTotp(instanceId, null)
            cancellable?.cancel()
            cancellable = null
            scopedWatchtowerController = null
            directorySession?.close()
            directorySession = null
            wordlistListSession?.close()
            wordlistListSession = null
            wordlistDetailSession?.close()
            wordlistDetailSession = null
            emailRelayListSession?.close()
            emailRelayListSession = null
            emailRelayList = null
            wordlistList = null
            wordlistDetail = null
        }

        /**
         * Full teardown for a REMOVED entry (popped / scope cleared / vault locked):
         * pauses the producer and closes the owned session (its gate + headless
         * source + channels). Idempotent.
         */
        fun release() {
            stop()
            wordlistSelection.value = emptySet()
            emailRelaySelection.value = emptySet()
            wordlistQuery.value = EntryListQuery()
            vaultListSession?.close()
            vaultListSession = null
            duplicatesSession?.close()
            duplicatesSession = null
        }

        fun toSnapshot() = ScreenEntrySnapshot(
            instanceId = instanceId,
            kind = kind.entryKind,
            title = title,
            detail = detail,
            vaultListSession = vaultListSession,
            watchtower = watchtower,
            watchtowerAlerts = watchtowerAlerts,
            serviceDirectory = serviceDirectory,
            serviceDirectoryDetail = serviceDirectoryDetail,
            wordlistId = (kind as? ScreenKind.WordlistDetail)?.wordlistId,
            wordlistList = wordlistList,
            emailRelayList = emailRelayList,
            wordlistDetail = wordlistDetail,
            passwordHistoryItemId = (kind as? ScreenKind.PasswordHistory)?.itemId,
            sshAgentHistoryCipherId = (kind as? ScreenKind.SshAgentHistory)?.cipherId,
            sendId = (kind as? ScreenKind.SendDetail)?.sendId,
            sendAccountId = (kind as? ScreenKind.SendDetail)?.accountId,
            organizations = organizations,
            collections = collections,
            folders = folders,
            accountDetailId = (kind as? ScreenKind.AccountDetail)?.accountId,
            equivalentDomains = equivalentDomains,
            duplicatesSession = duplicatesSession,
            export = export,
            cipherFilters = cipherFilters,
            cipherFilterDetail = cipherFilterDetail,
            downloads = downloads,
            feedback = feedback,
        )
    }

    /**
     * Opens an external URL for a producer-emitted [NavigationIntent.NavigateToBrowser]
     * (e.g. the account "premium" row, autofill help links). Set by the shell so the
     * native layer applies its browser preference; without it such intents are dropped.
     */
    private var openUrlHandler: ((String) -> Unit)? = null

    fun setOpenUrlHandler(handler: ((String) -> Unit)?) {
        openUrlHandler = handler
    }

    /** Opens [url] via the host's open-url handler (the same path interceptor uses). */
    fun openUrl(url: String) {
        openUrlHandler?.invoke(url)
    }

    private var openSystemUrlHandler: ((String) -> Unit)? = null

    fun setOpenSystemUrlHandler(handler: ((String) -> Unit)?) {
        openSystemUrlHandler = handler
    }

    /** Preserves system actions, including Maps actions whose URL uses HTTPS. */
    fun openSystemUrl(url: String) {
        openSystemUrlHandler?.invoke(url)
    }

    /**
     * Shares plain text (the public Send link) for a producer-emitted
     * [NavigationIntent.NavigateToShare] (the Send detail "share" action). Set by the
     * shell so the native layer presents a UIActivityViewController (iOS) /
     * NSSharingServicePicker (macOS); without it such intents are dropped.
     */
    private var shareHandler: ((String) -> Unit)? = null

    fun setShareHandler(handler: ((String) -> Unit)?) {
        shareHandler = handler
    }

    /**
     * Asks the shell to switch the visible section (tab) to [scope] — used when a deep link
     * targets a screen in a different section than the one currently shown. Set by the shell;
     * scope keys match the SwiftUI `NavStackContainer(scope:)` sections ("vault" / "send" /
     * "generator" / "watchtower" / "settings").
     */
    private var selectScopeHandler: ((String) -> Unit)? = null

    fun setSelectScopeHandler(handler: ((String) -> Unit)?) {
        selectScopeHandler = handler
    }

    /**
     * Presents the native create-item sheet for a producer-emitted `AddRoute` (the
     * generator's "create login / SSH key" actions). The args are flattened to
     * scalars (type name + prefilled name / username / password) for the Swift sheet;
     * without a handler the route is dropped.
     */
    private var addCipherHandler: ((String, String?, String?, String?) -> Unit)? = null

    fun setAddCipherHandler(handler: ((String, String?, String?, String?) -> Unit)?) {
        addCipherHandler = handler
    }

    /**
     * Presents the native add-account flow for a producer-emitted login route
     * ([BitwardenLoginRoute] / [KeePassLoginRoute], e.g. the account list's
     * add-account items or quick search's zero-accounts action). The argument is
     * the [AccountType] name ("BITWARDEN" / "KEEPASS"); without a handler the
     * route is dropped (recorded as unmapped).
     */
    private var addAccountHandler: ((String) -> Unit)? = null

    fun setAddAccountHandler(handler: ((String) -> Unit)?) {
        addAccountHandler = handler
    }

    /**
     * Receives the args of a producer-emitted [BitwardenLoginRoute] right before
     * [addAccountHandler] presents the login sheet, so a re-login keeps the
     * account's email and server. Late-bound by [KeyguardCore].
     */
    var bitwardenLoginArgsHandler: ((BitwardenLoginRoute.Args) -> Unit)? = null

    /**
     * Reveals a local file natively (Finder selection / iOS fallback) for a
     * producer-emitted [NavigationIntent.NavigateToPreviewInFileManager] — e.g.
     * the KeePass account detail's "open local vault" action.
     */
    private var revealFileHandler: ((String) -> Unit)? = null

    fun setRevealFileHandler(handler: ((String) -> Unit)?) {
        revealFileHandler = handler
    }

    /** Chooses an application to open a downloaded attachment. */
    private var previewFileHandler: ((String) -> Unit)? = null

    fun setPreviewFileHandler(handler: ((String) -> Unit)?) {
        previewFileHandler = handler
    }

    /** Shares a downloaded attachment using the native file activity UI. */
    private var shareFileHandler: ((String) -> Unit)? = null

    fun setShareFileHandler(handler: ((String) -> Unit)?) {
        shareFileHandler = handler
    }

    // All of the following are main-confined.
    private var nextId = 1L
    private val stacks = mutableMapOf<String, MutableList<ScreenEntry>>()
    private val sinks = mutableMapOf<String, (List<ScreenEntrySnapshot>) -> Unit>()

    // The live TOTP badges of the stacked cipher details, per entry. They travel on
    // their own channel ([observeNavStackTotp]) so a countdown tick does not
    // re-emit the stacks.
    private val entryTotp = mutableMapOf<Long, VaultDetailTotpSnapshot>()
    private val totpSinks = mutableListOf<(Map<String, VaultDetailTotpSnapshot>) -> Unit>()
    private var currentScope: String = ""
    private var sessionScope: CoroutineScope? = null
    private var sessionKoin: Scope? = null

    /**
     * A deep link received while locked / before the session was ready; applied once the
     * vault reaches Main (see [openDeepLink] / [startSession]).
     */
    private var pendingDeepLink: RouteDescriptor? = null

    /**
     * Durable per-scope descriptor stacks for state restoration: nav scope → the ordered
     * [RouteDescriptor]s of that scope's pushed entries. Written on every navigation
     * mutation ([persist]) and read once on unlock ([startSession]).
     */
    private val navStackPersistence: NavigationStackPersistence by lazy { ctx.koin.get() }

    /**
     * Starts the single whole-stack session gate (shell owns this for the unlocked
     * lifetime). On lock it tears every entry down across all scopes and reports
     * empty; on a vault-state change it re-keys the live entries.
     */
    fun startSession(): KeyguardCancellable = ctx.launchSessionObserver(
        onLocked = {
            // Main thread. The session scope was already cancelled (tearing down the
            // cipher / vault producer jobs); release the rest (incl. the owned
            // sessions), drop everything, report empty.
            sessionScope = null
            sessionKoin = null
            stacks.values.flatten().forEach { it.release() }
            stacks.clear()
            sinks.keys.forEach { emit(it) }
        },
    ) { state ->
        val scope = this
        val di = state.sessionKoin
        // Read persisted stacks off-main; applied on Main only when nothing is live yet
        // (first unlock after launch), so an in-session re-key does not duplicate entries.
        val restored = runCatching {
            navStackPersistence.load(NavigationStackPersistence.Format.Apple)
        }.getOrNull().orEmpty()
        ctx.publishOnMain {
            sessionScope = scope
            sessionKoin = di
            if (stacks.isEmpty() && restored.isNotEmpty()) {
                restoreStacks(restored)
            }
            stacks.values.flatten().forEach { startEntry(it) }
            stacks.keys.forEach { emit(it) }
            // Apply a deep link that arrived while locked, now that the session is live.
            pendingDeepLink?.let { descriptor ->
                pendingDeepLink = null
                applyDeepLink(descriptor)
            }
        }
        awaitCancellation()
    }

    /** Registers the SwiftUI sink for [scope]; emits its current stack immediately. */
    fun observeNavStack(
        scope: String,
        onChange: (List<ScreenEntrySnapshot>) -> Unit,
    ): KeyguardCancellable {
        ctx.scope.launch {
            sinks[scope] = onChange
            onChange(snapshots(scope))
        }
        return KeyguardCancellable(
            ctx.scope.launch {
                try {
                    awaitCancellation()
                } finally {
                    if (sinks[scope] === onChange) sinks.remove(scope)
                }
            },
        )
    }

    /**
     * Observes the live TOTP badges of every stacked cipher detail, keyed by cipher
     * id (entries of the same cipher show the same codes). Emits the current map
     * immediately; nothing is delivered once the returned handle is cancelled.
     */
    fun observeNavStackTotp(
        onChange: (Map<String, VaultDetailTotpSnapshot>) -> Unit,
    ): KeyguardCancellable = KeyguardCancellable(
        ctx.scope.launch {
            val context = coroutineContext
            val sink: (Map<String, VaultDetailTotpSnapshot>) -> Unit = { totp ->
                if (context.isActive) onChange(totp)
            }
            totpSinks += sink
            try {
                sink(entryTotpByCipher())
                awaitCancellation()
            } finally {
                totpSinks -= sink
            }
        },
    )

    /** Sets the scope that subsequent pushes target (the visible section / tab). */
    fun setScope(scope: String) = onMain {
        currentScope = scope
    }

    /** Pushes a new screen instance onto the current scope. Safe from any thread. */
    fun pushScreen(kind: ScreenKind, descriptor: RouteDescriptor? = null) = onMain {
        if (sessionScope == null || sessionKoin == null) return@onMain
        val scope = currentScope
        val entry = ScreenEntry(nextId++, kind)
        entry.descriptor = descriptor
        stacks.getOrPut(scope) { mutableListOf() }.add(entry)
        startEntry(entry)
        emit(scope)
        persist()
    }

    /** Pushes a cipher detail (an iPhone vault row tap, Swift-initiated). */
    fun pushCipherDetail(itemId: String, accountId: String) =
        pushScreen(
            ScreenKind.CipherDetail(itemId = itemId, accountId = accountId),
            RouteDescriptor.VaultCipherView(itemId = itemId, accountId = accountId),
        )

    /** Pushes a service-directory list (a watchtower "Tools" row, Swift-initiated). */
    fun pushServiceDirectoryList(kind: String, title: String) =
        pushScreen(
            ScreenKind.ServiceDirectoryList(kind = kind, title = title),
            serviceDirectoryDescriptor(kind),
        )

    /** Pushes a service-directory service detail (a directory list-item tap). */
    fun pushServiceDirectoryDetail(kind: String, itemId: String, title: String) =
        pushScreen(ScreenKind.ServiceDirectoryDetail(kind = kind, itemId = itemId, title = title))

    /** Pushes the generator history (a generator "Tools" row, Swift-initiated). */
    fun pushGeneratorHistory() =
        pushScreen(ScreenKind.GeneratorHistory, RouteDescriptor.GeneratorHistory)

    /** Pushes the email-relay list (a generator "Tools" row, Swift-initiated). */
    fun pushEmailRelayList() =
        pushScreen(ScreenKind.EmailRelayList, RouteDescriptor.EmailRelayList)

    /** Pushes the wordlists list (a generator "Tools" row, Swift-initiated). */
    fun pushWordlistList() =
        pushScreen(ScreenKind.WordlistList, RouteDescriptor.WordlistList)

    /** Pushes a single wordlist's detail (a wordlists list-item tap). */
    fun pushWordlistDetail(wordlistId: Long, title: String) =
        pushScreen(
            ScreenKind.WordlistDetail(wordlistId = wordlistId, title = title),
            RouteDescriptor.WordlistView(wordlistId = wordlistId),
        )

    /** Pushes a cipher's password history (the cipher detail header button). */
    fun pushPasswordHistory(itemId: String) =
        pushScreen(
            ScreenKind.PasswordHistory(itemId = itemId),
            RouteDescriptor.PasswordHistory(itemId = itemId),
        )

    /**
     * Pushes an account detail (an iPhone Settings account-row tap, Swift-initiated).
     * No [RouteDescriptor] yet (bridge-only screen), so it is not restored on relaunch.
     */
    fun pushAccountDetail(accountId: String) =
        pushScreen(ScreenKind.AccountDetail(accountId = accountId))

    /** Pushes an organization's collections (an organizations list-item tap). */
    fun pushCollectionsList(accountId: String, organizationId: String?, title: String) =
        pushScreen(
            ScreenKind.CollectionsList(
                accountId = accountId,
                organizationId = organizationId,
                title = title,
            ),
            RouteDescriptor.Collections(accountId = accountId, organizationId = organizationId),
        )

    /** Pushes a Send detail (an iPhone Send row tap, Swift-initiated). */
    fun pushSendDetail(sendId: String, accountId: String) =
        pushScreen(
            ScreenKind.SendDetail(sendId = sendId, accountId = accountId),
            RouteDescriptor.SendView(sendId = sendId, accountId = accountId),
        )

    /** Pushes the "Contact us" feedback screen (a Settings → About row, Swift-initiated). */
    fun pushFeedback() = pushScreen(ScreenKind.Feedback, RouteDescriptor.Feedback)

    /** Maps a service-directory [kind] constant to its [RouteDescriptor] (null = unknown). */
    private fun serviceDirectoryDescriptor(kind: String): RouteDescriptor? = when (kind) {
        DIRECTORY_KIND_TWO_FA -> RouteDescriptor.TwoFaServices
        DIRECTORY_KIND_PASSKEYS -> RouteDescriptor.PasskeysServices
        DIRECTORY_KIND_GET_MY_DATA -> RouteDescriptor.JustGetMyDataServices
        DIRECTORY_KIND_DELETE_ACCOUNT -> RouteDescriptor.JustDeleteMeServices
        else -> null
    }

    /**
     * Opens a deep link (a `keyguard://` URL): resolves it to a [RouteDescriptor] and pushes
     * the matching screen onto the current scope. If the vault is still locked the link is
     * stashed and applied once it unlocks (see [startSession]). A URL that is not a
     * recognized deep link is ignored.
     */
    fun openDeepLink(url: String) = onMain {
        val descriptor = routeDescriptorFromDeepLink(url) ?: return@onMain
        if (sessionScope != null && sessionKoin != null) {
            applyDeepLink(descriptor)
        } else {
            pendingDeepLink = descriptor
        }
    }

    /**
     * Routes a deep-link [descriptor] to its owning section: asks the shell to switch the
     * visible tab ([selectScopeHandler]) and pushes the screen onto that section's stack —
     * not whichever section happens to be active. Main-confined; the caller ensures the
     * session is live. No-op for a descriptor with no native screen.
     */
    private fun applyDeepLink(descriptor: RouteDescriptor) {
        val kind = descriptorScreen(descriptor) ?: return
        val scope = descriptorScope(descriptor)
        currentScope = scope
        selectScopeHandler?.invoke(scope)
        val entry = ScreenEntry(nextId++, kind)
        entry.descriptor = descriptor
        stacks.getOrPut(scope) { mutableListOf() }.add(entry)
        startEntry(entry)
        emit(scope)
        persist()
    }

    /**
     * The nav scope (section) a deep-link descriptor belongs to, so it opens in the right
     * tab. Keys match the SwiftUI `NavStackContainer(scope:)` sections.
     */
    private fun descriptorScope(descriptor: RouteDescriptor): String = when (descriptor) {
        is RouteDescriptor.SendView -> "send"

        RouteDescriptor.GeneratorHistory,
        RouteDescriptor.EmailRelayList,
        RouteDescriptor.WordlistList,
        is RouteDescriptor.WordlistView,
        -> "generator"

        is RouteDescriptor.WatchtowerAlerts,
        RouteDescriptor.TwoFaServices,
        RouteDescriptor.PasskeysServices,
        RouteDescriptor.JustGetMyDataServices,
        RouteDescriptor.JustDeleteMeServices,
        -> "watchtower"

        RouteDescriptor.Feedback,
        RouteDescriptor.Subscriptions,
        -> "settings"

        // Everything vault-related (cipher / list / folders / collections / organizations /
        // equivalent domains / duplicates / export / custom filters / downloads).
        else -> "vault"
    }

    /** Writes [text] into a feedback entry's message field. */
    fun setEntryFeedbackMessage(instanceId: Long, text: String) = onMain {
        entry(instanceId)?.feedbackState?.message?.onChange?.invoke(text)
    }

    /** Submits a feedback entry (fires the producer's send → NavigateToEmail). */
    fun submitEntryFeedback(instanceId: Long) = onMain {
        entry(instanceId)?.feedbackState?.onSendClick?.invoke()
    }

    /** Writes [text] into an export entry's password field. */
    fun setExportPassword(instanceId: Long, text: String) = onMain {
        entry(instanceId)?.exportState?.passwordFlow?.value?.model?.onChange?.invoke(text)
    }

    /** Pops the top screen instance of [scope]. Safe from any thread. */
    fun popScreen(scope: String) = onMain {
        val entry = stacks[scope]?.removeLastOrNull() ?: return@onMain
        entry.release()
        emit(scope)
        persist()
    }

    /** Pops every screen instance above [instanceId] (in whichever scope holds it). */
    fun popToScreen(instanceId: Long) = onMain {
        val scope = stacks.entries.firstOrNull { (_, list) -> list.any { it.instanceId == instanceId } }?.key
            ?: return@onMain
        val list = stacks[scope] ?: return@onMain
        val idx = list.indexOfFirst { it.instanceId == instanceId }
        if (idx < 0) return@onMain
        while (list.size > idx + 1) list.removeAt(list.lastIndex).release()
        emit(scope)
        persist()
    }

    /** Clears the [scope] stack back to its root. Safe from any thread. */
    fun clearScope(scope: String) = onMain {
        val list = stacks[scope] ?: return@onMain
        if (list.isEmpty()) return@onMain
        list.forEach { it.release() }
        list.clear()
        emit(scope)
        persist()
    }

    /** Invokes a cipher-detail item action of the entry with [instanceId]. */
    fun invokeEntryAction(instanceId: Long, actionId: String) = onMain {
        val entry = entry(instanceId) ?: return@onMain
        val watchtower = entry.scopedWatchtowerController
        if (watchtower != null) {
            when {
                entry.kind is ScreenKind.WatchtowerAlerts && actionId == "markAllRead" ->
                    watchtower.markAllWatchtowerAlertsRead()
                entry.kind is ScreenKind.WatchtowerAlerts -> watchtower.invokeWatchtowerAlertItem(actionId)
                actionId == "clearFilters" -> watchtower.clearWatchtowerFilters()
                actionId.startsWith("filter:") -> watchtower.invokeWatchtowerFilter(actionId.removePrefix("filter:"))
                else -> watchtower.invokeWatchtowerAction(actionId)
            }
        } else {
            entry.actionHandlers[actionId]?.invoke()
        }
    }

    /** Toggles the favourite flag of a cipher-detail entry. */
    fun toggleEntryFavorite(instanceId: Long) = onMain {
        entry(instanceId)?.favourite?.invoke()
    }

    /** Writes [text] into a directory / cipher-filters entry's search field. */
    fun setEntryListQuery(instanceId: Long, text: String) = onMain {
        val entry = entry(instanceId) ?: return@onMain
        when (val kind = entry.kind) {
            is ScreenKind.ServiceDirectoryList ->
                entry.directorySession?.setQuery(text)

            is ScreenKind.WordlistDetail -> entry.wordlistDetailSession?.setQuery(text)

            is ScreenKind.CipherFiltersList ->
                cipherFiltersController.setQuery(text)

            else -> Unit
        }
    }

    fun retryEntryList(instanceId: Long) = onMain {
        val entry = entry(instanceId) ?: return@onMain
        entry.directorySession?.retry()
        entry.wordlistListSession?.retry()
        entry.emailRelayListSession?.retry()
        entry.wordlistDetailSession?.retry()
    }

    /**
     * Opens an item of a list entry. For a directory list it pushes the service
     * detail by id (the bridge resolves the cached model); for a cipher-filters
     * list it pushes the filter's detail.
     */
    fun openEntryListItem(instanceId: Long, itemId: String) = onMain {
        val entry = entry(instanceId) ?: return@onMain
        when (val kind = entry.kind) {
            is ScreenKind.ServiceDirectoryList ->
                entry.directorySession?.open(itemId)

            ScreenKind.WordlistList -> entry.wordlistListSession?.open(itemId)

            is ScreenKind.CipherFiltersList -> {
                val title = cipherFiltersController.titleFor(itemId) ?: ""
                pushScreen(
                    ScreenKind.CipherFilterDetail(filterId = itemId, title = title),
                    RouteDescriptor.CipherFilterView(filterId = itemId, title = title),
                )
            }

            else -> Unit
        }
    }

    fun toggleEntryListSelection(instanceId: Long, itemId: String) = onMain {
        val entry = entry(instanceId) ?: return@onMain
        entry.wordlistListSession?.toggleSelection(itemId)
        entry.emailRelayListSession?.toggleSelection(itemId)
    }

    fun clearEntryListSelection(instanceId: Long) = onMain {
        val entry = entry(instanceId) ?: return@onMain
        entry.wordlistListSession?.clearSelection()
        entry.emailRelayListSession?.clearSelection()
    }

    fun selectAllEntryListItems(instanceId: Long) = onMain {
        val entry = entry(instanceId) ?: return@onMain
        entry.wordlistListSession?.selectAll()
        entry.emailRelayListSession?.selectAll()
    }

    fun requestEntryWordlistAction(
        instanceId: Long,
        actionId: String,
        itemId: String?,
        onResult: (WordlistActionRequestSnapshot?) -> Unit,
    ) = onMain {
        onResult(entry(instanceId)?.wordlistListSession?.requestAction(actionId, itemId))
    }

    fun requestEntryEmailRelayAction(
        instanceId: Long,
        actionId: String,
        itemId: String?,
        onResult: (EmailRelayActionRequestSnapshot?) -> Unit,
    ) = onMain {
        onResult(entry(instanceId)?.emailRelayListSession?.requestAction(actionId, itemId))
    }

    /**
     * The composed interceptor handed to the detail / list / watchtower producers.
     * Dialog routes go to [DialogController]; a full-screen route or pop becomes a
     * stack operation on the current scope; everything else is left unhandled.
     */
    fun interceptor(sessionKoin: Scope): (NavigationIntent) -> Boolean {
        val dialogInterceptor = dialogController.navigationInterceptor(sessionKoin = sessionKoin)
        fun dispatch(intent: NavigationIntent): Boolean {
            return if (intent is NavigationIntent.Composite) {
                // Shared alert rows emit PopById + NavigateToRoute together.
                // Dispatch every child in order through the same native handlers;
                // dropping the wrapper leaves an otherwise clickable row inert.
                var handled = false
                intent.list.forEach { child ->
                    handled = dispatch(child) || handled
                }
                handled
            } else if (dialogInterceptor(intent)) {
                true
            } else if (handleExternalIntent(intent)) {
                true
            } else if (intent is NavigationIntent.NavigateToRoute && intent.route is AddRouteImpl) {
                val args = (intent.route as AddRouteImpl).args
                val hasGeneratedKey = args.keyPair != null || args.gpgKey != null || args.gpgKeyValue != null
                if (args.initialValue != null || hasGeneratedKey) {
                    // Existing ciphers and generated keys carry structured values
                    // that the scalar create callback cannot represent. Preserve
                    // the full args; the producer still determines create vs edit.
                    addItemController.stashEditCipher(args)
                } else {
                    // The generator's "create login / SSH key" → present the native add
                    // sheet prefilled with the generated value.
                    addCipherHandler?.invoke(
                        args.type?.name ?: "Login",
                        args.name,
                        args.username,
                        args.password,
                    )
                }
                true
            } else if (intent is NavigationIntent.NavigateToRoute && intent.route is SendAddRoute) {
                // Editing an existing Send (the Send detail "edit" button): the
                // producer carries the whole DSend in initialValue — stash it and
                // open the native edit sheet pre-filled.
                addItemController.stashEditSend((intent.route as SendAddRoute).args)
                true
            } else {
                val addAccount = intent.toAddAccountRequestOrNull()
                    ?.takeIf { addAccountHandler != null }
                if (addAccount != null) {
                    onMain {
                        addAccount.bitwardenArgs?.let { args -> bitwardenLoginArgsHandler?.invoke(args) }
                        addAccountHandler?.invoke(addAccount.type.name)
                    }
                    true
                } else {
                    handleFullScreen(intent)
                }
            }
        }
        return ::dispatch
    }

    /**
     * Hands an intent that leaves the app (a link, a share sheet, a file preview,
     * mail / phone / maps) to its native handler; `false` for any other intent.
     */
    private fun handleExternalIntent(intent: NavigationIntent): Boolean = when (intent) {
        is NavigationIntent.NavigateToBrowser -> {
            // Open external links (account "premium", autofill help, …) natively.
            openUrlHandler?.invoke(intent.url)
            true
        }

        is NavigationIntent.NavigateToShare -> {
            // The Send detail "share" action emits this with the public Send
            // link; present the native share sheet over the shared text.
            shareHandler?.invoke(intent.text)
            true
        }

        is NavigationIntent.NavigateToPreview -> {
            previewFileHandler?.invoke(intent.uri)
            true
        }

        is NavigationIntent.NavigateToSend -> {
            shareFileHandler?.invoke(intent.uri)
            true
        }

        is NavigationIntent.NavigateToEmail -> {
            // The feedback ("Contact us") send button emits this; build a mailto:
            // URL (subject/body percent-encoded) and open it natively.
            openSystemUrl(intent.toMailtoUrl())
            true
        }

        is NavigationIntent.NavigateToPhone -> {
            // The identity detail's call / text / navigate actions.
            openSystemUrl(intent.toTelUrl())
            true
        }

        is NavigationIntent.NavigateToSms -> {
            openSystemUrl(intent.toSmsUrl())
            true
        }

        is NavigationIntent.NavigateToMaps -> {
            openSystemUrl(intent.toMapsUrl())
            true
        }

        is NavigationIntent.NavigateToPreviewInFileManager -> {
            // The KeePass account detail's "open local vault" action: reveal
            // the database file natively (Finder selection / iOS fallback).
            revealFileHandler?.invoke(intent.uri)
            true
        }

        else -> false
    }

    // An intent this returns `false` for is reported by the headless scope's
    // navigation controller (see [DroppedNavigation]).
    private fun handleFullScreen(intent: NavigationIntent): Boolean {
        val op = resolveFullScreen(intent) ?: return false
        applyStackOp(op)
        return true
    }

    private class AddAccountRequest(
        val type: AccountType,
        /** The login form's args; set for Bitwarden, including a re-login. */
        val bitwardenArgs: BitwardenLoginRoute.Args? = null,
    )

    /**
     * Recognizes a producer-emitted add-account navigation: [KeePassLoginRoute] or
     * a [BitwardenLoginRoute] (a fresh login, or an account's re-login with its
     * email and server locked), possibly wrapped by `registerRouteResultReceiver`.
     * The wrapped receiver's only effect everywhere in common/ is `navigate(Pop)`,
     * which in the headless bridge would pop an unrelated top entry of the current
     * scope — so the result transmitter is deliberately NOT fired; Swift dismisses
     * its own login sheet on success.
     */
    private fun NavigationIntent.toAddAccountRequestOrNull(): AddAccountRequest? {
        val route = (this as? NavigationIntent.NavigateToRoute)?.route ?: return null
        val inner = (route as? RouteResultReceiver<*>)?.innerRoute ?: route
        return when (inner) {
            is KeePassLoginRoute -> AddAccountRequest(AccountType.KEEPASS)
            is BitwardenLoginRoute -> AddAccountRequest(AccountType.BITWARDEN, inner.args)
            else -> null
        }
    }

    private sealed interface StackOp {
        data class Push(val kind: ScreenKind, val descriptor: RouteDescriptor? = null) : StackOp
        data object Pop : StackOp
    }

    private fun resolveFullScreen(intent: NavigationIntent): StackOp? = when (intent) {
        is NavigationIntent.NavigateToRoute -> resolveRoute(intent.route)

        // Headless: producer-emitted PopById ids are Compose nav ids the bridge does
        // not populate, so treat any pop as "pop the top entry of the current scope".
        is NavigationIntent.Pop -> StackOp.Pop
        is NavigationIntent.PopById -> StackOp.Pop

        // Everything else (SetRoute / NavigateToStack / Exit / the
        // platform intents) is intentionally left for the existing handlers or
        // dropped, matching the pre-stack behaviour.
        else -> null
    }

    /**
     * Resolves a [NavigationIntent.NavigateToRoute] to a stack op by dispatching on the
     * route's data-only [RouteDescriptor]. A route with no mapping resolves to
     * [RouteDescriptor.Unmapped] → null here, and the interceptor records + logs it.
     */
    private fun resolveRoute(route: Route): StackOp? {
        // The generator menu opens the Compose wordlists router. Native apps
        // host the list and its details in their existing navigation stack.
        val descriptor = when (route) {
            WordlistsRoute -> RouteDescriptor.WordlistList
            else -> route.descriptor
        }
        return descriptorScreen(descriptor)?.let { StackOp.Push(it, descriptor) }
    }

    /**
     * Maps a migrated route's [RouteDescriptor] to its native screen. Exhaustive over
     * the sealed config: a new descriptor cannot be added without a mapping here (the
     * compiler enforces it), which is what kills the old silent `else -> null`.
     */
    private fun descriptorScreen(descriptor: RouteDescriptor): ScreenKind? = when (descriptor) {
        is RouteDescriptor.VaultCipherView ->
            ScreenKind.CipherDetail(itemId = descriptor.itemId, accountId = descriptor.accountId)

        is RouteDescriptor.VaultList -> {
            // Rebuild the shared VaultRoute.Args from the serializable descriptor. Sort
            // is a stable-id singleton (Sort.valueOf); the AppBar subtitle is dropped
            // (the native toolbar only uses the title); everything else round-trips.
            val args = VaultRoute.Args(
                appBar = descriptor.title?.let { VaultRoute.Args.AppBar(title = it) },
                filter = descriptor.filter,
                sort = descriptor.sortId?.let { Sort.valueOf(it) },
                main = descriptor.main,
                searchBy = runCatching { VaultRoute.Args.SearchBy.valueOf(descriptor.searchBy) }
                    .getOrDefault(VaultRoute.Args.SearchBy.ALL),
                trash = descriptor.trash,
                archive = descriptor.archive,
                preselect = descriptor.preselect,
                canAddSecrets = descriptor.canAddSecrets,
            )
            ScreenKind.VaultList(args = args, title = args.appBar?.title ?: "Vault")
        }

        is RouteDescriptor.SendView ->
            ScreenKind.SendDetail(sendId = descriptor.sendId, accountId = descriptor.accountId)

        is RouteDescriptor.PasswordHistory ->
            ScreenKind.PasswordHistory(itemId = descriptor.itemId)

        is RouteDescriptor.SshAgentHistory ->
            ScreenKind.SshAgentHistory(cipherId = descriptor.cipherId)

        is RouteDescriptor.WordlistView ->
            ScreenKind.WordlistDetail(wordlistId = descriptor.wordlistId, title = "")

        is RouteDescriptor.Organizations ->
            ScreenKind.OrganizationsList(accountId = descriptor.accountId, title = "Organizations")

        is RouteDescriptor.Collections ->
            ScreenKind.CollectionsList(
                accountId = descriptor.accountId,
                organizationId = descriptor.organizationId,
                title = "Collections",
            )

        is RouteDescriptor.EquivalentDomains ->
            ScreenKind.EquivalentDomains(accountId = descriptor.accountId, title = "Equivalent domains")

        RouteDescriptor.Downloads -> ScreenKind.Downloads

        is RouteDescriptor.Watchtower -> ScreenKind.Watchtower(WatchtowerRoute.Args(filter = descriptor.filter))

        is RouteDescriptor.WatchtowerAlerts -> ScreenKind.WatchtowerAlerts(
            title = "Alerts",
            args = WatchtowerAlertsRoute.Args(filter = descriptor.filter),
        )

        RouteDescriptor.CipherFilters -> ScreenKind.CipherFiltersList(title = "Custom filters")

        RouteDescriptor.GeneratorHistory -> ScreenKind.GeneratorHistory

        RouteDescriptor.EmailRelayList -> ScreenKind.EmailRelayList

        RouteDescriptor.WordlistList -> ScreenKind.WordlistList

        RouteDescriptor.Feedback -> ScreenKind.Feedback

        RouteDescriptor.Subscriptions -> ScreenKind.Subscriptions

        RouteDescriptor.TwoFaServices ->
            ScreenKind.ServiceDirectoryList(DIRECTORY_KIND_TWO_FA, "Two-factor auth")

        RouteDescriptor.PasskeysServices ->
            ScreenKind.ServiceDirectoryList(DIRECTORY_KIND_PASSKEYS, "Passkeys")

        RouteDescriptor.JustGetMyDataServices ->
            ScreenKind.ServiceDirectoryList(DIRECTORY_KIND_GET_MY_DATA, "Get my data")

        RouteDescriptor.JustDeleteMeServices ->
            ScreenKind.ServiceDirectoryList(DIRECTORY_KIND_DELETE_ACCOUNT, "Delete account")

        is RouteDescriptor.Folders -> {
            val accountId = DFilter.findOne<DFilter.ById>(descriptor.filter ?: DFilter.All) {
                it.what == DFilter.ById.What.ACCOUNT
            }?.id
            // The watchtower "empty folders" card emits Folders(empty=true) with NO account
            // filter; push the native folders list unscoped to an account but carrying the
            // empty-only flag. The account-detail entry still pushes the account-scoped list.
            ScreenKind.FoldersList(accountId = accountId, title = "Folders", empty = descriptor.empty)
        }

        is RouteDescriptor.Duplicates ->
            ScreenKind.Duplicates(filter = descriptor.filter, title = "Duplicate items")

        is RouteDescriptor.Export ->
            ScreenKind.Export(title = descriptor.title ?: "Export items", filter = descriptor.filter)

        is RouteDescriptor.CipherFilterView ->
            // Push by id + title; the detail controller loads the DCipherFilter from the
            // repo (fallbackModel = null) exactly like the custom-filters list-item tap,
            // so the rich model never has to cross the bridge as serialized data.
            ScreenKind.CipherFilterDetail(filterId = descriptor.filterId, title = descriptor.title)

        // Root nav sections, not stack entries: the native shell selects them through
        // the nav-items configuration (SwiftUI owns the section switch), so there is no
        // screen to PUSH for them. Resolving to null lets the interceptor record the
        // attempt instead of pushing a duplicate root.
        is RouteDescriptor.SendList,
        is RouteDescriptor.Generator,

        RouteDescriptor.GpgTools,
        RouteDescriptor.Settings,
        -> null

        // A dialog, not a stack entry — and the descriptor intentionally carries no
        // password, so there is nothing to reconstruct it from.
        RouteDescriptor.PasswordMemory -> null

        // No native screen for this route; record it instead of pushing a duplicate.
        is RouteDescriptor.Unmapped -> null
    }

    private fun applyStackOp(op: StackOp) = when (op) {
        is StackOp.Push -> pushScreen(op.kind, op.descriptor)
        StackOp.Pop -> popScreen(currentScope)
    }

    // --- internals (main-confined) -------------------------------------------

    /**
     * Per-stacked-list persistence namespace, derived from the route identity.
     */
    private fun stackedVaultListScope(kind: ScreenKind.VaultList): String {
        val args = kind.args
        val discriminator = buildString {
            append(args.filter?.toString().orEmpty())
            append('|').append(args.trash)
            append('|').append(args.archive)
            append('|').append(args.appBar?.title.orEmpty())
        }
        return "vaultlist.stacked." + discriminator.hashCode().toUInt().toString(HEXADECIMAL_RADIX)
    }

    private fun startEntry(entry: ScreenEntry) {
        val scope = sessionScope ?: return
        val di = sessionKoin ?: return
        entry.stop()
        when (val kind = entry.kind) {
            is ScreenKind.CipherDetail ->
                entry.job = scope.launch {
                    cipherDetailController.produceDetailInto(
                        scope = this,
                        sessionKoin = di,
                        itemId = kind.itemId,
                        accountId = kind.accountId,
                        interceptor = interceptor(di),
                        publishTotp = { totp -> setEntryTotp(entry.instanceId, totp) },
                    ) { snapshot, handlers, favourite ->
                        entry.title = snapshot.title
                        entry.detail = snapshot
                        entry.actionHandlers = handlers
                        entry.favourite = favourite
                        emitFor(entry)
                    }
                }

            is ScreenKind.VaultList ->
                // Keep one session per stack entry; it owns its lock/unlock lifecycle.
                if (entry.vaultListSession == null) {
                    entry.vaultListSession = VaultListSession(
                        ctx = ctx,
                        args = kind.args,
                        persistenceScope = stackedVaultListScope(kind),
                        navigationInterceptorProvider = { sessionKoin -> interceptor(sessionKoin) },
                    )
                }

            // Watchtower entries reuse the existing session-gated
            // observers (each delivers on the main thread), so they hold a
            // KeyguardCancellable rather than a session-scope job.
            is ScreenKind.Watchtower -> {
                val controller = WatchtowerController(ctx, kind.args, "watchtower.${entry.instanceId}")
                controller.navigationInterceptorProvider = { sessionKoin -> interceptor(sessionKoin) }
                entry.scopedWatchtowerController = controller
                entry.cancellable = controller.observeWatchtower { snapshot ->
                    entry.watchtower = snapshot
                    emitFor(entry)
                }
            }

            is ScreenKind.WatchtowerAlerts -> {
                val controller = WatchtowerController(ctx, persistenceScope = "watchtower.${entry.instanceId}")
                controller.navigationInterceptorProvider = { sessionKoin -> interceptor(sessionKoin) }
                entry.scopedWatchtowerController = controller
                entry.cancellable = controller.observeWatchtowerNewAlerts(kind.args) { snapshot ->
                    entry.watchtowerAlerts = snapshot
                    emitFor(entry)
                }
            }

            is ScreenKind.ServiceDirectoryList ->
                entry.directorySession = serviceDirectoryController.createListSession(
                    scope = scope,
                    kind = kind.kind,
                    query = entry.directoryQuery,
                    onOpen = { itemId -> pushServiceDirectoryDetail(kind.kind, itemId, title = "") },
                    onChange = { snapshot ->
                        entry.serviceDirectory = snapshot
                        emitFor(entry)
                    },
                )

            is ScreenKind.ServiceDirectoryDetail ->
                entry.cancellable = serviceDirectoryController.observeServiceDirectoryDetail(
                    kind.kind,
                    kind.itemId,
                ) { snapshot ->
                    if (entry.title.isEmpty()) entry.title = snapshot.title
                    entry.serviceDirectoryDetail = snapshot
                    emitFor(entry)
                }

            is ScreenKind.OrganizationsList ->
                entry.job = scope.launch {
                    organizationsController.produceOrganizationsInto(
                        scope = this,
                        sessionKoin = di,
                        accountId = kind.accountId,
                        interceptor = interceptor(di),
                    ) { snapshot, handlers ->
                        entry.organizations = snapshot
                        entry.actionHandlers = handlers
                        emitFor(entry)
                    }
                }

            is ScreenKind.CollectionsList ->
                entry.job = scope.launch {
                    collectionsController.produceCollectionsInto(
                        scope = this,
                        sessionKoin = di,
                        accountId = kind.accountId,
                        organizationId = kind.organizationId,
                        interceptor = interceptor(di),
                    ) { snapshot, handlers ->
                        entry.collections = snapshot
                        entry.actionHandlers = handlers
                        emitFor(entry)
                    }
                }

            is ScreenKind.FoldersList ->
                entry.job = scope.launch {
                    foldersController.produceFoldersInto(
                        scope = this,
                        sessionKoin = di,
                        accountId = kind.accountId,
                        empty = kind.empty,
                        interceptor = interceptor(di),
                    ) { snapshot, handlers ->
                        entry.folders = snapshot
                        entry.actionHandlers = handlers
                        emitFor(entry)
                    }
                }

            is ScreenKind.EquivalentDomains ->
                entry.job = scope.launch {
                    equivalentDomainsController.produceEquivalentDomainsInto(
                        scope = this,
                        sessionKoin = di,
                        accountId = kind.accountId,
                    ) { snapshot ->
                        entry.equivalentDomains = snapshot
                        emitFor(entry)
                    }
                }

            is ScreenKind.Duplicates ->
                // Keep one session per stack entry; it owns its lock/unlock lifecycle.
                if (entry.duplicatesSession == null) {
                    entry.duplicatesSession = DuplicatesSession(
                        ctx = ctx,
                        filter = kind.filter,
                        navigationInterceptorProvider = { sessionKoin -> interceptor(sessionKoin) },
                    )
                }

            is ScreenKind.Export ->
                entry.job = scope.launch {
                    exportController.produceExportInto(
                        scope = this,
                        sessionKoin = di,
                        title = kind.title,
                        filter = kind.filter,
                        interceptor = interceptor(di),
                    ) { snapshot, handlers, state ->
                        entry.export = snapshot
                        entry.actionHandlers = handlers
                        entry.exportState = state
                        emitFor(entry)
                    }
                }

            is ScreenKind.CipherFiltersList ->
                entry.job = scope.launch {
                    cipherFiltersController.produceFiltersInto(
                        scope = this,
                        sessionKoin = di,
                        interceptor = interceptor(di),
                    ) { snapshot ->
                        entry.cipherFilters = snapshot
                        emitFor(entry)
                    }
                }

            is ScreenKind.CipherFilterDetail ->
                entry.job = scope.launch {
                    cipherFiltersController.produceFilterDetailInto(
                        scope = this,
                        sessionKoin = di,
                        filterId = kind.filterId,
                        fallbackModel = kind.model,
                        interceptor = interceptor(di),
                    ) { snapshot, handlers ->
                        if (entry.title.isEmpty()) entry.title = snapshot.title
                        entry.cipherFilterDetail = snapshot
                        entry.actionHandlers = handlers
                        emitFor(entry)
                    }
                }

            is ScreenKind.Downloads ->
                entry.job = scope.launch {
                    downloadsController.produceDownloadsInto(
                        scope = this,
                        sessionKoin = di,
                        interceptor = interceptor(di),
                    ) { snapshot, handlers ->
                        entry.downloads = snapshot
                        entry.actionHandlers = handlers
                        emitFor(entry)
                    }
                }

            is ScreenKind.Feedback ->
                entry.job = scope.launch {
                    feedbackController.produceFeedbackInto(
                        scope = this,
                        interceptor = interceptor(di),
                    ) { snapshot, state ->
                        entry.feedback = snapshot
                        entry.feedbackState = state
                        emitFor(entry)
                    }
                }

            ScreenKind.EmailRelayList -> {
                val getEmailRelays = di.get<GetEmailRelays>()
                val providers = di.get<EmailRelayRegistry>().values
                val leContext = ctx.koin.get<LeContext>()
                entry.emailRelayListSession = EmailRelayListSession(
                    scope = scope,
                    source = { getEmailRelays() },
                    providerNames = providers.associate { it.type to it.name },
                    selectedIds = entry.emailRelaySelection,
                    actionTitle = { action ->
                        val resource = when (action) {
                            EmailRelayActionKind.EDIT -> Res.string.edit
                            EmailRelayActionKind.DUPLICATE -> Res.string.duplicate
                            EmailRelayActionKind.DELETE -> Res.string.delete
                        }
                        textResource(resource, leContext)
                    },
                    onChange = { snapshot ->
                        entry.emailRelayList = snapshot
                        emitFor(entry)
                    },
                )
            }

            ScreenKind.WordlistList -> {
                val getWordlists = di.get<GetWordlists>()
                val numberFormatter = di.get<NumberFormatter>()
                val leContext = ctx.koin.get<LeContext>()
                entry.wordlistListSession = WordlistListSession(
                    scope = scope,
                    source = { getWordlists() },
                    selectedIds = entry.wordlistSelection,
                    counter = { item ->
                        val count = item.wordCount.toInt()
                        textResource(
                            Res.plurals.word_count_plural, leContext, count, numberFormatter.formatNumber(count),
                        )
                    },
                    actionTitle = { action ->
                        val resource = if (action == WORDLIST_ACTION_RENAME) Res.string.edit else Res.string.delete
                        textResource(resource, leContext)
                    },
                    onOpen = { item -> pushWordlistDetail(item.idRaw, item.name) },
                    onChange = { snapshot ->
                        entry.wordlistList = snapshot
                        emitFor(entry)
                    },
                )
            }

            is ScreenKind.WordlistDetail ->
                entry.wordlistDetailSession = WordlistDetailSession(
                    scope = scope,
                    wordlistId = kind.wordlistId,
                    getWordlists = di.get(),
                    getWords = di.get(),
                    query = entry.wordlistQuery,
                    onChange = { snapshot ->
                        entry.wordlistDetail = snapshot
                        emitFor(entry)
                    },
                )

            // Self-observing screens: the Swift view starts its own model-backed
            // observation (from the entry's scalar args) on appear, so there is no
            // per-entry producer to launch here.
            ScreenKind.GeneratorHistory,
            ScreenKind.Subscriptions,
            is ScreenKind.PasswordHistory,
            is ScreenKind.SshAgentHistory,
            is ScreenKind.SendDetail,
            is ScreenKind.AccountDetail,
            -> Unit
        }
    }

    private fun entry(instanceId: Long): ScreenEntry? =
        stacks.values.firstNotNullOfOrNull { list -> list.firstOrNull { it.instanceId == instanceId } }

    /** Re-emits whichever scope's stack contains [entry] (it may not be the current one). */
    private fun emitFor(entry: ScreenEntry) {
        val scope = stacks.entries.firstOrNull { (_, list) -> entry in list }?.key ?: return
        emit(scope)
    }

    private fun emit(scope: String) {
        sinks[scope]?.invoke(snapshots(scope))
    }

    private fun snapshots(scope: String): List<ScreenEntrySnapshot> =
        stacks[scope].orEmpty().map { it.toSnapshot() }

    /** Sets (or, with `null`, drops) the live TOTP badges of one stacked entry. */
    private fun setEntryTotp(instanceId: Long, totp: VaultDetailTotpSnapshot?) {
        val previous = if (totp == null) {
            entryTotp.remove(instanceId)
        } else {
            entryTotp.put(instanceId, totp)
        }
        if (previous == totp) return
        val snapshot = entryTotpByCipher()
        totpSinks.toList().forEach { it(snapshot) }
    }

    private fun entryTotpByCipher(): Map<String, VaultDetailTotpSnapshot> =
        entryTotp.values.associateBy { it.cipherId }

    /**
     * Persists the current per-scope stacks as [RouteDescriptor] lists (dropping entries
     * with no descriptor). Fire-and-forget on the background scope; called after every
     * mutation. NOT called on lock, so the saved stack survives to the next unlock.
     */
    private fun persist() {
        val snapshot = stacks
            .mapValues { (_, list) -> list.mapNotNull { it.descriptor } }
            .filterValues { it.isNotEmpty() }
        navStackPersistence.save(NavigationStackPersistence.Format.Apple, snapshot)
            .launchIn(ctx.backgroundScope)
    }

    /**
     * Rebuilds in-memory entries from a persisted [saved] descriptor map. The caller starts
     * them afterwards (the [startSession] startEntry loop). Descriptors that no longer map
     * to a screen ([RouteDescriptor.Unmapped]) are skipped.
     */
    private fun restoreStacks(saved: Map<String, List<RouteDescriptor>>) {
        saved.forEach { (scopeKey, descriptors) ->
            val list = stacks.getOrPut(scopeKey) { mutableListOf() }
            descriptors.forEach { descriptor ->
                val kind = descriptorScreen(descriptor) ?: return@forEach
                val entry = ScreenEntry(nextId++, kind)
                entry.descriptor = descriptor
                list.add(entry)
            }
        }
    }

    /** Runs [block] on the core (main) scope so [stacks] / [sinks] stay main-confined. */
    private inline fun onMain(crossinline block: () -> Unit) {
        ctx.scope.launch { block() }
    }
}

private const val HEXADECIMAL_RADIX = 16
