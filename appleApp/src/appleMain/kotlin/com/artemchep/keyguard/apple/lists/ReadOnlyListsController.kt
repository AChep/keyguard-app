package com.artemchep.keyguard.apple.lists

import com.artemchep.keyguard.apple.core.sessionKoin
import com.artemchep.keyguard.main
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.feature.home.vault.model.VaultPasswordHistoryItem
import com.artemchep.keyguard.feature.home.vault.screen.VaultViewPasswordHistoryState
import com.artemchep.keyguard.feature.home.vault.screen.vaultViewPasswordHistoryScreenStateProducer
import com.artemchep.keyguard.feature.license.LicenseState
import com.artemchep.keyguard.feature.license.licenseStateProducer
import com.artemchep.keyguard.feature.localizationcontributors.directory.LocalizationContributorsListState
import com.artemchep.keyguard.feature.localizationcontributors.directory.localizationContributorsListStateProducer
import com.artemchep.keyguard.feature.logs.LogsItem
import com.artemchep.keyguard.feature.logs.LogsState
import com.artemchep.keyguard.feature.logs.logsStateProducer
import com.artemchep.keyguard.feature.sshagent.history.SshAgentHistoryItem
import com.artemchep.keyguard.feature.sshagent.history.SshAgentHistoryState
import com.artemchep.keyguard.feature.sshagent.history.sshAgentHistoryStateProducer
import com.artemchep.keyguard.feature.urlblock.UrlBlockListState
import com.artemchep.keyguard.feature.urlblock.urlBlockListStateProducer
import com.artemchep.keyguard.feature.urloverride.UrlOverrideListState
import com.artemchep.keyguard.feature.urloverride.urlOverrideListStateProducer
import com.artemchep.keyguard.apple.core.CoreContext
import com.artemchep.keyguard.apple.core.KeyguardCancellable
import com.artemchep.keyguard.apple.core.collectOnMain
import com.artemchep.keyguard.apple.core.newHeadlessStateFlowScope
import com.artemchep.keyguard.apple.dialog.DialogController
import com.artemchep.keyguard.apple.model.buildMenuActionSnapshots
import com.artemchep.keyguard.apple.model.buildSelectionActionSnapshots
import com.artemchep.keyguard.apple.model.invokeAction
import com.artemchep.keyguard.platform.LeContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * The read-only list screens that just run a shared producer and project it into
 * a flat snapshot: SSH agent signing history, a cipher's password history, and
 * the about/developer screens (licenses, localization contributors, logs,
 * blocked-URL list, URL-override list). The vault-backed ones await
 * [com.artemchep.keyguard.common.model.VaultState.Main]; the static/global ones
 * run off the global DI.
 */
internal class ReadOnlyListsController(
    private val ctx: CoreContext,
    private val dialogController: DialogController,
) {
    // Blocked-URL list interactive state (the per-row edit/duplicate/delete
    // dropdown, the bulk-delete selection, and the create-new primary action).
    private var urlBlockState: UrlBlockListState.Content? = null
    private var urlBlockItemHandlers: Map<String, () -> Unit> = emptyMap()
    private var urlBlockSelectionHandlers: Map<String, () -> Unit> = emptyMap()

    // URL-override list interactive state (mirrors the blocked-URL one).
    private var urlOverrideState: UrlOverrideListState.Content? = null
    private var urlOverrideItemHandlers: Map<String, () -> Unit> = emptyMap()
    private var urlOverrideSelectionHandlers: Map<String, () -> Unit> = emptyMap()

    // Password-history interactive state (the per-entry dropdown — copy password /
    // remove from history / show in large type / show-and-lock / check data breaches,
    // the bulk-delete multi-selection, and the top-level "Clear history" action).
    private var passwordHistoryState: VaultViewPasswordHistoryState.Content.Cipher? = null
    private var passwordHistoryItemHandlers: Map<String, () -> Unit> = emptyMap()
    private var passwordHistorySelectionHandlers: Map<String, () -> Unit> = emptyMap()
    private var passwordHistoryActionHandlers: Map<String, () -> Unit> = emptyMap()

    // Only the newest observation may write or clear the password-history state.
    private var passwordHistoryGeneration = 0L

    /** Observes the SSH agent history of the cipher [cipherId], or of all ciphers when `null`. */
    fun observeSshAgentHistory(
        cipherId: String?,
        onChange: (SshAgentHistorySnapshot) -> Unit,
    ): KeyguardCancellable {
        return ctx.launchSessionObserver(
            onLocked = {
                onChange(SshAgentHistorySnapshot.empty)
            },
        ) { state ->
            val producerScope = this
            val producerFlow = with(state.sessionKoin) {
                ctx.koin.newHeadlessStateFlowScope("ssh_agent_history", producerScope)
                    .sshAgentHistoryStateProducer(
                        cipherId = cipherId,
                        getSshUsageHistory = get(),
                        removeSshUsageHistory = get(),
                        getCiphers = get(),
                        dateFormatter = get(),
                        confirmationRouteFactory = get(),
                        json = get(),
                    )
            }
            producerFlow
                .map { loadable -> buildSshAgentHistorySnapshot(loadable.getOrNull()) }
                .collectOnMain { onChange(it) }
        }
    }

    private fun buildSshAgentHistorySnapshot(
        state: SshAgentHistoryState?,
    ): SshAgentHistorySnapshot {
        state ?: return SshAgentHistorySnapshot.empty
        val items = state.items.map { item ->
            when (item) {
                is SshAgentHistoryItem.Section -> SshAgentHistoryItemSnapshot(
                    id = item.id,
                    kind = SshAgentHistoryItemKind.SECTION,
                    caller = item.text.orEmpty(),
                    description = "",
                    date = null,
                    responseText = "",
                    response = null,
                )

                is SshAgentHistoryItem.Value -> SshAgentHistoryItemSnapshot(
                    id = item.id,
                    kind = SshAgentHistoryItemKind.VALUE,
                    caller = item.caller,
                    description = item.description,
                    date = item.formattedDate,
                    responseText = item.responseText,
                    response = item.response.name,
                )
            }
        }
        return SshAgentHistorySnapshot(loaded = true, subtitle = state.subtitle, items = items)
    }

    /**
     * Observes a cipher's password history by running the shared
     * [vaultViewPasswordHistoryScreenStateProducer]. Threads the dialog interceptor
     * so the producer's routes reach a renderer instead of being dropped: the
     * per-entry "Check data breaches" (PasswordLeakRoute) / "Show in large type" /
     * "Show and lock" (LargeTypeRoute), and the per-item / bulk / clear-all Delete
     * confirmations (ConfirmationRoute). Projects each entry's own dropdown (copy
     * password / remove from history / large-type / show-and-lock / check breaches),
     * the bulk-delete multi-selection and the top-level "Clear history" action; the
     * SwiftUI screen drives them through the opaque-id `invokePasswordHistory*` /
     * `togglePasswordHistorySelection` / `clearPasswordHistorySelection` methods.
     */
    fun observePasswordHistory(
        itemId: String,
        onChange: (PasswordHistorySnapshot) -> Unit,
    ): KeyguardCancellable {
        val leContext = ctx.koin.get<LeContext>()
        val observationGeneration = ++passwordHistoryGeneration
        return ctx.launchSessionObserver(
            // The state holds plaintext passwords; drop it as soon as the vault
            // locks or the screen stops observing.
            onLocked = {
                if (clearPasswordHistory(observationGeneration)) onChange(PasswordHistorySnapshot.empty)
            },
            onTeardown = { clearPasswordHistory(observationGeneration) },
        ) { state ->
            val producerScope = this
            val producerFlow = with(state.sessionKoin) {
                // Pass the session DI so a password row's "Check data breaches"
                // action (PasswordLeakRoute) resolves its session-scoped checker
                // and presents the password-breach dialog.
                ctx.koin.newHeadlessStateFlowScope(
                    "vault_password_history.$itemId",
                    producerScope,
                    dialogController.navigationInterceptor(sessionKoin = state.sessionKoin),
                )
                    .vaultViewPasswordHistoryScreenStateProducer(
                        getCanWrite = get(),
                        getAccounts = get(),
                        getCiphers = get(),
                        cipherRemovePasswordHistory = get(),
                        cipherRemovePasswordHistoryById = get(),
                        clipboardService = get(),
                        dateFormatter = get(),
                        confirmationRouteFactory = get(),
                        itemId = itemId,
                    )
            }
            // Build the snapshot + handler maps off the main thread, then install
            // the maps and deliver on the main thread together.
            producerFlow
                .map { historyState ->
                    val itemHandlers = LinkedHashMap<String, () -> Unit>()
                    val selectionHandlers = LinkedHashMap<String, () -> Unit>()
                    val actionHandlers = LinkedHashMap<String, () -> Unit>()
                    val snapshot = buildPasswordHistorySnapshot(
                        state = historyState,
                        leContext = leContext,
                        itemHandlers = itemHandlers,
                        selectionHandlers = selectionHandlers,
                        actionHandlers = actionHandlers,
                    )
                    PasswordHistoryProjection(
                        content = historyState.content as? VaultViewPasswordHistoryState.Content.Cipher,
                        snapshot = snapshot,
                        itemHandlers = itemHandlers,
                        selectionHandlers = selectionHandlers,
                        actionHandlers = actionHandlers,
                    )
                }
                .collectOnMain { projection ->
                    if (passwordHistoryGeneration != observationGeneration) return@collectOnMain
                    passwordHistoryState = projection.content
                    passwordHistoryItemHandlers = projection.itemHandlers
                    passwordHistorySelectionHandlers = projection.selectionHandlers
                    passwordHistoryActionHandlers = projection.actionHandlers
                    onChange(projection.snapshot)
                }
        }
    }

    /** Clears the password-history state if [generation] is the newest observation; returns whether it was. */
    private fun clearPasswordHistory(generation: Long): Boolean {
        if (passwordHistoryGeneration != generation) return false
        passwordHistoryState = null
        passwordHistoryItemHandlers = emptyMap()
        passwordHistorySelectionHandlers = emptyMap()
        passwordHistoryActionHandlers = emptyMap()
        return true
    }

    private suspend fun buildPasswordHistorySnapshot(
        state: VaultViewPasswordHistoryState,
        leContext: LeContext,
        itemHandlers: LinkedHashMap<String, () -> Unit>,
        selectionHandlers: LinkedHashMap<String, () -> Unit>,
        actionHandlers: LinkedHashMap<String, () -> Unit>,
    ): PasswordHistorySnapshot {
        return when (val content = state.content) {
            is VaultViewPasswordHistoryState.Content.Cipher -> {
                val items = content.items
                    .filterIsInstance<VaultPasswordHistoryItem.Value>()
                    .map { item ->
                        val actions = buildMenuActionSnapshots(
                            actions = item.dropdown,
                            idPrefix = "item:${item.id}",
                            leContext = leContext,
                            handlers = itemHandlers,
                        )
                        PasswordHistoryItemSnapshot(
                            id = item.id,
                            value = item.value,
                            date = item.date,
                            monospace = item.monospace,
                            actions = actions,
                            selected = item.selected,
                            selecting = item.selecting,
                        )
                    }
                val selection = content.selection
                val selectionActions = buildSelectionActionSnapshots(
                    actions = selection?.actions,
                    leContext = leContext,
                    handlers = selectionHandlers,
                )
                val topActions = buildMenuActionSnapshots(
                    actions = content.actions,
                    idPrefix = "screen",
                    leContext = leContext,
                    handlers = actionHandlers,
                )
                PasswordHistorySnapshot(
                    loaded = true,
                    notFound = false,
                    items = items,
                    selectionCount = selection?.count ?: 0,
                    selectionActions = selectionActions,
                    actions = topActions,
                )
            }

            is VaultViewPasswordHistoryState.Content.NotFound ->
                PasswordHistorySnapshot(loaded = true, notFound = true, items = emptyList())

            is VaultViewPasswordHistoryState.Content.Loading ->
                PasswordHistorySnapshot.empty
        }
    }

    /**
     * Runs a per-entry dropdown action of a password-history row (copy password /
     * remove from history / show in large type / show-and-lock / check data
     * breaches) by its id.
     */
    fun invokePasswordHistoryItemAction(id: String) {
        passwordHistoryItemHandlers[id]?.invoke()
    }

    /** Runs a bulk action of the active password-history multi-selection (Delete) by its id. */
    fun invokePasswordHistorySelectionAction(id: String) {
        passwordHistorySelectionHandlers[id]?.invoke()
    }

    /** Runs a top-level password-history action (the "Clear history" action) by its id. */
    fun invokePasswordHistoryAction(id: String) {
        passwordHistoryActionHandlers.invokeAction(id)
    }

    /**
     * Toggles whether the password-history row with [itemId] is part of the
     * multi-selection. Routes through the producer's per-item selection handle
     * (onClick while a selection is active, otherwise onLongClick which begins one).
     */
    fun togglePasswordHistorySelection(itemId: String) {
        val item = passwordHistoryState
            ?.items
            ?.filterIsInstance<VaultPasswordHistoryItem.Value>()
            ?.firstOrNull { it.id == itemId }
            ?: return
        (item.onClick ?: item.onLongClick)?.invoke()
    }

    /** Clears the active password-history multi-selection. */
    fun clearPasswordHistorySelection() {
        passwordHistoryState?.selection?.onClear?.invoke()
    }

    /** Observes the open-source licenses list by running the shared [licenseStateProducer]. */
    fun observeLicense(
        onChange: (LicenseListSnapshot) -> Unit,
    ): KeyguardCancellable {
        return ctx.launchObserver {
            coroutineScope {
                val producerScope = this
                val producerFlow = with(ctx.koin) {
                    ctx.koin.newHeadlessStateFlowScope("open_source_licenses", producerScope)
                        .licenseStateProducer(licenseService = get())
                }
                producerFlow
                    .map { loadable -> buildLicenseSnapshot(loadable.getOrNull()) }
                    .collectOnMain { onChange(it) }
            }
        }
    }

    private fun buildLicenseSnapshot(
        state: LicenseState?,
    ): LicenseListSnapshot {
        state ?: return LicenseListSnapshot.empty
        val items = state.content.items.map { lib ->
            val id = lib.groupId + ":" + lib.artifactId
            LicenseItemSnapshot(
                id = id,
                name = lib.name ?: id,
                version = lib.version,
                license = lib.spdxLicenses.joinToString(separator = ", ") { it.name },
                url = lib.scm?.url
                    ?: lib.spdxLicenses.firstOrNull { it.url != null }?.url,
            )
        }
        return LicenseListSnapshot(loaded = true, items = items)
    }

    /** Observes the localization contributors list by running the shared producer. */
    fun observeLocalizationContributors(
        onChange: (LocalizationContributorsSnapshot) -> Unit,
    ): KeyguardCancellable {
        return ctx.launchObserver {
            coroutineScope {
                val producerScope = this
                val producerFlow = with(ctx.koin) {
                    ctx.koin.newHeadlessStateFlowScope("localization_contributors_list", producerScope)
                        .localizationContributorsListStateProducer(localizationContributorsService = get())
                }
                producerFlow
                    .map { loadable -> buildLocalizationContributorsSnapshot(loadable.getOrNull()) }
                    .collectOnMain { onChange(it) }
            }
        }
    }

    private fun buildLocalizationContributorsSnapshot(
        state: LocalizationContributorsListState?,
    ): LocalizationContributorsSnapshot {
        val content = state?.content?.getOrNull()?.getOrNull()
            ?: return LocalizationContributorsSnapshot.empty
        val items = content.items.map { item ->
            LocalizationContributorItemSnapshot(
                id = item.key,
                name = item.name.text,
                score = item.score,
            )
        }
        return LocalizationContributorsSnapshot(loaded = true, items = items)
    }

    /** Observes the app logs by running the shared [logsStateProducer]. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeLogs(
        onChange: (LogsSnapshot) -> Unit,
    ): KeyguardCancellable {
        // ExportLogs belongs to the unlocked session graph. Resolving it from
        // global DI throws before the producer can publish its first snapshot.
        return ctx.launchSessionObserver(
            onLocked = { onChange(LogsSnapshot.empty) },
        ) { state ->
            val producerScope = this
            val producerFlow = with(state.sessionKoin) {
                ctx.koin.newHeadlessStateFlowScope("logs", producerScope)
                    .logsStateProducer(
                        dateFormatter = get(),
                        clipboardService = get(),
                        getInMemoryLogs = get(),
                        getInMemoryLogsEnabled = get(),
                        putInMemoryLogsEnabled = get(),
                        permissionService = get(),
                        exportLogs = get(),
                    )
            }
            // The top-level LogsState rarely re-emits; its live entries flow
            // through the inner contentFlow it does NOT re-emit for, so switch
            // to that inner flow.
            producerFlow
                .map { it.getOrNull() }
                .flatMapLatest { st ->
                    if (st == null) {
                        flowOf(LogsSnapshot.empty)
                    } else {
                        st.contentFlow.map { content -> buildLogsSnapshot(content) }
                    }
                }
                .collectOnMain { onChange(it) }
        }
    }

    private fun buildLogsSnapshot(
        content: LogsState.Content,
    ): LogsSnapshot {
        val items = content.items.map { item ->
            when (item) {
                is LogsItem.Section -> LogsItemSnapshot(
                    id = item.id,
                    kind = LogsItemKind.SECTION,
                    text = item.text.orEmpty(),
                    level = null,
                    time = null,
                )

                is LogsItem.Value -> LogsItemSnapshot(
                    id = item.id,
                    kind = LogsItemKind.VALUE,
                    text = item.text.text,
                    level = item.level.name,
                    time = item.time,
                )
            }
        }
        return LogsSnapshot(loaded = true, items = items)
    }

    /**
     * Observes the blocked-URL list by running the shared [urlBlockListStateProducer].
     * Threads the dialog interceptor so the producer's CRUD routes reach a renderer
     * instead of being dropped: the add/edit form (ConfirmationRoute with
     * name/description/enabled/exposed/uri/mode) and the per-item / bulk Delete
     * confirmations. Projects the per-row edit/duplicate/delete dropdown, the
     * bulk-delete multi-selection and the create-new primary action; the SwiftUI
     * screen drives them through the opaque-id `invokeUrlBlockList*` /
     * `toggleUrlBlockListSelection` / `clearUrlBlockListSelection` methods.
     */
    fun observeUrlBlockList(
        onChange: (UrlRuleListSnapshot) -> Unit,
    ): KeyguardCancellable {
        val leContext = ctx.koin.get<LeContext>()
        return ctx.launchSessionObserver(
            onLocked = {
                urlBlockState = null
                urlBlockItemHandlers = emptyMap()
                urlBlockSelectionHandlers = emptyMap()
                onChange(UrlRuleListSnapshot.empty)
            },
        ) { state ->
            coroutineScope {
                val producerScope = this
                val sessionKoin = state.sessionKoin
                val producerFlow = with(sessionKoin) {
                    ctx.koin.newHeadlessStateFlowScope(
                        "urlblock_list",
                        producerScope,
                        dialogController.navigationInterceptor(sessionKoin = sessionKoin),
                    )
                        .urlBlockListStateProducer(
                            confirmationRouteFactory = get(),
                            addUrlBlock = get(),
                            removeUrlBlockById = get(),
                            getUrlBlocks = get(),
                        )
                }
                // Build the snapshot + handler maps off the main thread, then install
                // the maps and deliver on the main thread together.
                producerFlow
                    .map { loadable ->
                        val content = loadable.getOrNull()?.content?.getOrNull()?.getOrNull()
                        val itemHandlers = LinkedHashMap<String, () -> Unit>()
                        val selectionHandlers = LinkedHashMap<String, () -> Unit>()
                        val snapshot = buildUrlBlockSnapshot(
                            content = content,
                            leContext = leContext,
                            itemHandlers = itemHandlers,
                            selectionHandlers = selectionHandlers,
                        )
                        UrlRuleListProjection(
                            content = content,
                            snapshot = snapshot,
                            itemHandlers = itemHandlers,
                            selectionHandlers = selectionHandlers,
                        )
                    }
                    .collectOnMain { projection ->
                        urlBlockState = projection.content
                        urlBlockItemHandlers = projection.itemHandlers
                        urlBlockSelectionHandlers = projection.selectionHandlers
                        onChange(projection.snapshot)
                    }
            }
        }
    }

    private suspend fun buildUrlBlockSnapshot(
        content: UrlBlockListState.Content?,
        leContext: LeContext,
        itemHandlers: LinkedHashMap<String, () -> Unit>,
        selectionHandlers: LinkedHashMap<String, () -> Unit>,
    ): UrlRuleListSnapshot {
        content ?: return UrlRuleListSnapshot.empty
        val items = content.items.map { item ->
            val selectable = item.selectableState.value
            val actions = buildMenuActionSnapshots(
                actions = item.dropdown,
                idPrefix = "item:${item.key}",
                leContext = leContext,
                handlers = itemHandlers,
            )
            UrlRuleItemSnapshot(
                id = item.key,
                title = item.title,
                subtitle = item.uri.text,
                detail = item.mode.text,
                active = item.active,
                actions = actions,
                selected = selectable.selected,
                selecting = selectable.selecting,
            )
        }
        val selection = content.selection
        val selectionActions = buildSelectionActionSnapshots(
            actions = selection?.actions,
            leContext = leContext,
            handlers = selectionHandlers,
        )
        return UrlRuleListSnapshot(
            loaded = true,
            items = items,
            hasPrimaryAction = content.primaryAction != null,
            selectionCount = selection?.count ?: 0,
            selectionActions = selectionActions,
        )
    }

    /** Runs a per-row dropdown action of a blocked-URL row (edit / duplicate / delete) by its id. */
    fun invokeUrlBlockListItemAction(id: String) {
        urlBlockItemHandlers[id]?.invoke()
    }

    /** Runs a bulk action of the active blocked-URL multi-selection (Delete) by its id. */
    fun invokeUrlBlockListSelectionAction(id: String) {
        urlBlockSelectionHandlers[id]?.invoke()
    }

    /** Opens the create-new blocked-URL form (the producer's primary action). */
    fun invokeUrlBlockListPrimaryAction() {
        urlBlockState?.primaryAction?.invoke()
    }

    /**
     * Toggles whether the blocked-URL row with [itemId] is part of the multi-selection.
     * Routes through the producer's per-item selection handle (onClick while a
     * selection is active, otherwise onLongClick which begins one).
     */
    fun toggleUrlBlockListSelection(itemId: String) {
        val item = urlBlockState?.items?.firstOrNull { it.key == itemId } ?: return
        val selectable = item.selectableState.value
        (selectable.onClick ?: selectable.onLongClick)?.invoke()
    }

    /** Clears the active blocked-URL multi-selection. */
    fun clearUrlBlockListSelection() {
        urlBlockState?.selection?.onClear?.invoke()
    }

    /**
     * Observes the URL-override list by running the shared [urlOverrideListStateProducer].
     * Threads the dialog interceptor so the producer's CRUD routes reach a renderer
     * instead of being dropped: the add/edit form (ConfirmationRoute with
     * name/regex/command/enabled) and the per-item / bulk Delete confirmations.
     * Projects the per-row edit/duplicate/delete dropdown, the bulk-delete
     * multi-selection and the create-new primary action; the SwiftUI screen drives
     * them through the opaque-id `invokeUrlOverrideList*` /
     * `toggleUrlOverrideListSelection` / `clearUrlOverrideListSelection` methods.
     */
    fun observeUrlOverrideList(
        onChange: (UrlRuleListSnapshot) -> Unit,
    ): KeyguardCancellable {
        val leContext = ctx.koin.get<LeContext>()
        return ctx.launchSessionObserver(
            onLocked = {
                urlOverrideState = null
                urlOverrideItemHandlers = emptyMap()
                urlOverrideSelectionHandlers = emptyMap()
                onChange(UrlRuleListSnapshot.empty)
            },
        ) { state ->
            coroutineScope {
                val producerScope = this
                val sessionKoin = state.sessionKoin
                val producerFlow = with(sessionKoin) {
                    ctx.koin.newHeadlessStateFlowScope(
                        "urloverride_list",
                        producerScope,
                        dialogController.navigationInterceptor(sessionKoin = sessionKoin),
                    )
                        .urlOverrideListStateProducer(
                            confirmationRouteFactory = get(),
                            addUrlOverride = get(),
                            removeUrlOverrideById = get(),
                            getUrlOverrides = get(),
                            executeCommand = get(),
                        )
                }
                // Build the snapshot + handler maps off the main thread, then install
                // the maps and deliver on the main thread together.
                producerFlow
                    .map { loadable ->
                        val content = loadable.getOrNull()?.content?.getOrNull()?.getOrNull()
                        val itemHandlers = LinkedHashMap<String, () -> Unit>()
                        val selectionHandlers = LinkedHashMap<String, () -> Unit>()
                        val snapshot = buildUrlOverrideSnapshot(
                            content = content,
                            leContext = leContext,
                            itemHandlers = itemHandlers,
                            selectionHandlers = selectionHandlers,
                        )
                        UrlRuleListProjection(
                            content = content,
                            snapshot = snapshot,
                            itemHandlers = itemHandlers,
                            selectionHandlers = selectionHandlers,
                        )
                    }
                    .collectOnMain { projection ->
                        urlOverrideState = projection.content
                        urlOverrideItemHandlers = projection.itemHandlers
                        urlOverrideSelectionHandlers = projection.selectionHandlers
                        onChange(projection.snapshot)
                    }
            }
        }
    }

    private suspend fun buildUrlOverrideSnapshot(
        content: UrlOverrideListState.Content?,
        leContext: LeContext,
        itemHandlers: LinkedHashMap<String, () -> Unit>,
        selectionHandlers: LinkedHashMap<String, () -> Unit>,
    ): UrlRuleListSnapshot {
        content ?: return UrlRuleListSnapshot.empty
        val items = content.items.map { item ->
            val selectable = item.selectableState.value
            val actions = buildMenuActionSnapshots(
                actions = item.dropdown,
                idPrefix = "item:${item.key}",
                leContext = leContext,
                handlers = itemHandlers,
            )
            UrlRuleItemSnapshot(
                id = item.key,
                title = item.title,
                subtitle = item.regex.text,
                detail = item.command.text,
                active = item.active,
                actions = actions,
                selected = selectable.selected,
                selecting = selectable.selecting,
            )
        }
        val selection = content.selection
        val selectionActions = buildSelectionActionSnapshots(
            actions = selection?.actions,
            leContext = leContext,
            handlers = selectionHandlers,
        )
        return UrlRuleListSnapshot(
            loaded = true,
            items = items,
            hasPrimaryAction = content.primaryAction != null,
            selectionCount = selection?.count ?: 0,
            selectionActions = selectionActions,
        )
    }

    /** Runs a per-row dropdown action of a URL-override row (edit / duplicate / delete) by its id. */
    fun invokeUrlOverrideListItemAction(id: String) {
        urlOverrideItemHandlers[id]?.invoke()
    }

    /** Runs a bulk action of the active URL-override multi-selection (Delete) by its id. */
    fun invokeUrlOverrideListSelectionAction(id: String) {
        urlOverrideSelectionHandlers[id]?.invoke()
    }

    /** Opens the create-new URL-override form (the producer's primary action). */
    fun invokeUrlOverrideListPrimaryAction() {
        urlOverrideState?.primaryAction?.invoke()
    }

    /**
     * Toggles whether the URL-override row with [itemId] is part of the
     * multi-selection. Routes through the producer's per-item selection handle.
     */
    fun toggleUrlOverrideListSelection(itemId: String) {
        val item = urlOverrideState?.items?.firstOrNull { it.key == itemId } ?: return
        val selectable = item.selectableState.value
        (selectable.onClick ?: selectable.onLongClick)?.invoke()
    }

    /** Clears the active URL-override multi-selection. */
    fun clearUrlOverrideListSelection() {
        urlOverrideState?.selection?.onClear?.invoke()
    }

    private data class PasswordHistoryProjection(
        val content: VaultViewPasswordHistoryState.Content.Cipher?,
        val snapshot: PasswordHistorySnapshot,
        val itemHandlers: Map<String, () -> Unit>,
        val selectionHandlers: Map<String, () -> Unit>,
        val actionHandlers: Map<String, () -> Unit>,
    )

    private data class UrlRuleListProjection<T>(
        val content: T?,
        val snapshot: UrlRuleListSnapshot,
        val itemHandlers: Map<String, () -> Unit>,
        val selectionHandlers: Map<String, () -> Unit>,
    )
}
