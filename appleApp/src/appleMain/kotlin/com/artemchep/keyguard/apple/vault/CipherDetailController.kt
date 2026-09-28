package com.artemchep.keyguard.apple.vault

import androidx.compose.ui.graphics.Color
import com.artemchep.keyguard.AppMode
import com.artemchep.keyguard.apple.core.sessionKoin
import com.artemchep.keyguard.common.service.placeholder.PlaceholderFactoryRegistry
import com.artemchep.keyguard.main
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.usecase.GetGravatarUrl
import com.artemchep.keyguard.common.usecase.GetTotpCodeWithOffset
import com.artemchep.keyguard.feature.home.vault.model.VaultItemIcon
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.feature.home.vault.model.Visibility
import com.artemchep.keyguard.feature.home.vault.model.resolveWebsiteIconUrl
import com.artemchep.keyguard.feature.home.vault.model.short
import com.artemchep.keyguard.feature.home.vault.screen.VaultViewState
import com.artemchep.keyguard.feature.home.vault.screen.vaultViewScreenStateProducer
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.apple.core.CoreContext
import com.artemchep.keyguard.apple.core.KeyguardCancellable
import com.artemchep.keyguard.apple.core.collectOnMain
import com.artemchep.keyguard.apple.core.newHeadlessStateFlowScope
import com.artemchep.keyguard.apple.dialog.DialogController
import com.artemchep.keyguard.apple.model.buildVaultItemSnapshots
import com.artemchep.keyguard.apple.model.resolveUriAppIcons
import com.artemchep.keyguard.common.service.app.parser.IosAppAppStoreParser
import com.artemchep.keyguard.common.service.app.parser.AndroidAppGooglePlayParser
import com.artemchep.keyguard.feature.home.vault.model.VaultUriIcon
import com.artemchep.keyguard.apple.model.invokeAction
import com.artemchep.keyguard.apple.model.toHeaderActionSnapshots
import com.artemchep.keyguard.apple.model.totpBadgeFlow
import com.artemchep.keyguard.common.model.LinkInfo
import com.artemchep.keyguard.common.service.extract.LinkInfoExtractor
import com.artemchep.keyguard.common.service.extract.impl.LinkInfoPlatformExtractor
import com.artemchep.keyguard.common.service.extract.impl.LinkInfoExtractorExecute
import com.artemchep.keyguard.platform.LeContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.core.Koin
import org.koin.core.scope.Scope

/**
 * The cipher detail screen. Runs the shared [vaultViewScreenStateProducer]
 * headlessly and projects its [VaultViewState] into a flat [VaultDetailSnapshot]
 * (field rows via the shared [buildVaultItemSnapshots]).
 *
 * Two entry points share one producer-and-projection core ([cipherDetailFlows]):
 *  - [observeCipherDetail] — the single-slot root detail pane. Holds the live
 *    producer closures behind controller fields; [invokeVaultAction] /
 *    [toggleVaultFavorite] act on them.
 *  - [produceDetailInto] — an instance-keyed variant for the navigation stack:
 *    the caller supplies the scope + session DI + a per-instance `publish`, so
 *    many cipher details can be alive at once without sharing the controller's
 *    single slot (see [com.artemchep.keyguard.apple.core.NavigationStackController]).
 *
 * The navigation interceptor handed to the producer is resolved through
 * [navigationInterceptorProvider]; by default it catches only the dialog routes
 * ([DialogController.navigationInterceptor]), but [KeyguardCore] late-binds it to
 * the navigation stack so full-screen routes (a folder chip, an associated login)
 * push onto the stack instead of being dropped.
 */
internal class CipherDetailController(
    private val ctx: CoreContext,
    private val dialogController: DialogController,
) {
    private val getGravatarUrl: GetGravatarUrl by lazy { ctx.koin.get() }

    /**
     * Resolves the navigation interceptor the detail producer is handed for a
     * given session DI. Defaults to the dialog-only interceptor; [KeyguardCore]
     * replaces it with the navigation stack's composed interceptor so full-screen
     * routes drive the stack.
     */
    var navigationInterceptorProvider: (Scope) -> ((NavigationIntent) -> Boolean) =
        { sessionKoin -> dialogController.navigationInterceptor(sessionKoin = sessionKoin) }

    private var latestVaultContent: VaultViewState.Content.Cipher? = null
    private var vaultActionHandlers: Map<String, () -> Unit> = emptyMap()

    private data class DetailTarget(val itemId: String, val accountId: String)

    /**
     * The root detail pane's currently selected target (`null` = nothing selected).
     * The single long-lived [observeCipherDetail] observer `flatMapLatest`es over this,
     * so selecting a different item swaps the per-cipher producer in place — no observer
     * teardown / cold restart, and the previously shown cipher stays on screen until the
     * new one's first snapshot arrives (no empty-pane flash). Updated via [setDetailTarget].
     */
    private val detailTarget = MutableStateFlow<DetailTarget?>(null)

    /** The two channels of one running cipher detail, see [cipherDetailFlows]. */
    private class CipherDetailFlows(
        /** A `(state, snapshot, handlers)` triple on every change of the detail. */
        val snapshots: Flow<Triple<VaultViewState, VaultDetailSnapshot, LinkedHashMap<String, () -> Unit>>>,
        /** The live TOTP badges; `null` while the cipher is not loaded. */
        val totp: Flow<VaultDetailTotpSnapshot?>,
    )

    /**
     * The shared producer-and-projection core. Runs [vaultViewScreenStateProducer]
     * for [itemId] / [accountId] in a headless scope tied to [scope], folds in the
     * reprompt lock, and exposes the detail snapshots plus a separate per-second
     * TOTP badge channel. Pure — no controller state is touched here, so it is
     * safe to run many instances concurrently.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun cipherDetailFlows(
        scope: CoroutineScope,
        sessionKoin: Scope,
        itemId: String,
        accountId: String,
        interceptor: (NavigationIntent) -> Boolean,
    ): CipherDetailFlows {
        val leContext = ctx.koin.get<LeContext>()
        val producerFlow = vaultViewStateFlow(scope, sessionKoin, itemId, accountId, interceptor)
        // The shared use-case that generates the rotating TOTP codes; we drive the
        // macOS badge from it directly so the codes, countdown and progress are all
        // computed by shared Kotlin (never re-derived in Swift).
        val getTotpCode = sessionKoin.get<GetTotpCodeWithOffset>()

        // Single shared copy of the latest top-level state; the producer is cold,
        // so fan out from one StateFlow.
        val latest = MutableStateFlow<VaultViewState?>(null)
        scope.launch {
            producerFlow.collect { vaultState -> latest.value = vaultState }
        }

        // The set of concealed VALUE / CARD rows the user has revealed, after the
        // reveal request passed the shared producer's `executeWithRePrompt` gate (the
        // elevated-access dialog on a master-password-reprompt cipher). This is the
        // bridge analog of the per-field Compose `rememberVisibilityState`: the
        // producer's `transformUserEvent` decides IF a reveal is allowed (firing the
        // prompt), and only on success do we add the id here. Folding it into the
        // combine below re-runs the builder so the snapshot re-emits with the value.
        // Scoped to this producer run, so it resets whenever the cipher reloads / the
        // vault locks (the flow is re-collected).
        val revealedIds = MutableStateFlow<Set<String>>(emptySet())

        val iosAppParser = sessionKoin.get<IosAppAppStoreParser>()
        val androidAppParser = sessionKoin.get<AndroidAppGooglePlayParser>()
        val uriAppIcons = latest.map { state ->
            (state?.content as? VaultViewState.Content.Cipher)?.items.orEmpty()
                .filterIsInstance<VaultViewItem.Uri>()
                .mapNotNull { it.iconSource as? VaultUriIcon.App }
                .toSet()
        }.resolveUriAppIcons { source ->
            when (source) {
                is VaultUriIcon.IosApp -> iosAppParser(source.identifier).bind()?.iconUrl
                is VaultUriIcon.AndroidApp -> androidAppParser(source.identifier).bind()?.iconUrl
            }
        }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

        // Snapshot stream. A Cipher's reprompt lock, the TOTP codes, the attachment
        // progress and the reveal set carry their own live inner state that the
        // top-level state does NOT re-emit for, so combine them in: a change of any
        // of them re-runs the builder and pushes a fresh snapshot. The per-second
        // TOTP countdown is not part of it, see [totp].
        val snapshots = cipherDetailSnapshots(latest, leContext, revealedIds, uriAppIcons)

        // The live TOTP badges on their own channel: the countdown ticks every
        // second, and folding it into [snapshots] would rebuild the whole detail
        // (rows, handler maps, avatar lookup) on every tick.
        val totp = cipherDetailTotp(latest, getTotpCode)
        return CipherDetailFlows(
            snapshots = snapshots,
            totp = totp,
        )
    }

    /** Runs the shared [vaultViewScreenStateProducer] for one cipher, see [cipherDetailFlows]. */
    private suspend fun vaultViewStateFlow(
        scope: CoroutineScope,
        sessionKoin: Scope,
        itemId: String,
        accountId: String,
        interceptor: (NavigationIntent) -> Boolean,
    ): Flow<VaultViewState> = with(sessionKoin) {
        ctx.koin.newHeadlessStateFlowScope("vault_view", scope, interceptor)
            .vaultViewScreenStateProducer(
                mode = AppMode.Main,
                contentColor = Color.Unspecified,
                disabledContentColor = Color.Unspecified,
                getAccounts = get(),
                getCanWrite = get(),
                getCiphers = get(),
                getCollections = get(),
                getOrganizations = get(),
                getFolders = get(),
                getFolderTreeById = get(),
                getConcealFields = get(),
                getMarkdown = get(),
                getAppIcons = get(),
                getWebsiteIcons = get(),
                getPasskeys = get(),
                getTwoFa = get(),
                getTotpCode = get(),
                getPasswordStrength = get(),
                getUrlOverrides = get(),
                passkeyTargetCheck = get(),
                getWatchtowerUnreadAlerts = get(),
                markWatchtowerAlertAsRead = get(),
                cryptoGenerator = get(),
                keyPairGenerator = get(),
                keyPrivateExport = get(),
                keyPublicExport = get(),
                gpgKeyExport = get(),
                gpgPublicKeyExport = get(),
                gpgPrivateKeyExport = get(),
                cipherUnsecureUrlCheck = get(),
                cipherUnsecureUrlAutoFix = get(),
                cipherFieldSwitchToggle = get(),
                moveCipherToFolderById = get(),
                tldService = get(),
                equivalentDomainsBuilderFactory = get(),
                patchWatchtowerAlertCipher = get(),
                rePromptCipherById = get(),
                changeCipherNameById = get(),
                changeCipherPasswordById = get(),
                checkPasswordLeak = get(),
                retryCipher = get(),
                executeCommand = get(),
                copyCipherById = get(),
                restoreCipherById = get(),
                trashCipherById = get(),
                unarchiveCipherById = get(),
                archiveCipherById = get(),
                removeCipherById = get(),
                favouriteCipherById = get(),
                downloadManager = get(),
                downloadAttachment = get(),
                removeAttachment = get(),
                canPreviewAttachment = get(),
                attachmentPreviewRouteFactory = get(),
                passkeysCredentialViewRouteFactory = get(),
                vaultViewRouteFactory = get(),
                vaultRouteFactory = get(),
                collectionsRouteFactory = get(),
                cipherExpiringCheck = get(),
                cipherIncompleteCheck = get(),
                clipboardService = get(),
                getGravatarUrl = get(),
                dateFormatter = get(),
                addCipherOpenedHistory = get(),
                getJustDeleteMeByUrl = get(),
                getJustGetMyDataByUrl = get(),
                iosAppAppStoreParser = get(),
                androidAppGooglePlayParser = get(),
                androidAppFDroidParser = get(),
                windowCoroutineScope = get(),
                placeholderFactories = get<PlaceholderFactoryRegistry>().values,
                linkInfoExtractors = ctx.koin.appleCipherLinkInfoExtractors(),
                confirmationRouteFactory = get(),
                gpgPublicKeyParser = get(),
                getGpgKeyserverConfig = get(),
                changeGpgKeyExpirationById = get(),
                uploadGpgPublicKey = get(),
                refreshGpgPublicKeys = get(),
                verifyGpgPublicKey = get(),
                itemId = itemId,
                accountId = accountId,
            )
    }

    /** The detail snapshot channel of [cipherDetailFlows], projected from its [latest] state. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun cipherDetailSnapshots(
        latest: StateFlow<VaultViewState?>,
        leContext: LeContext,
        revealedIds: MutableStateFlow<Set<String>>,
        uriAppIcons: StateFlow<Map<VaultUriIcon.App, String?>>,
    ): Flow<Triple<VaultViewState, VaultDetailSnapshot, LinkedHashMap<String, () -> Unit>>> =
        latest.filterNotNull()
            .flatMapLatest { vaultState ->
                val content = vaultState.content
                if (content is VaultViewState.Content.Cipher) {
                    val innerFlows = buildList<Flow<Any>> {
                        add(content.locked)
                        // The copy/share actions capture the current code in a
                        // separate WhileSubscribed StateFlow. Keep it collected
                        // and rebuild handlers when it changes, so those actions
                        // never hold an expired code.
                        content.items
                            .filterIsInstance<VaultViewItem.Totp>()
                            .forEach { item -> add(item.localStateFlow) }
                        // Invalidation triggers only: the builder samples the
                        // StateFlows' .value, these just re-run it on download
                        // progress / action changes.
                        content.items
                            .filterIsInstance<VaultViewItem.Attachment>()
                            .forEach { item ->
                                add(item.item.statusState)
                                add(item.item.actionsState)
                            }
                        // A reveal flip must re-run the builder so the now-visible
                        // field's value is projected.
                        add(revealedIds)
                        add(uriAppIcons)
                    }
                    // Never empty (locked is always present), so combine() always
                    // emits at least once.
                    combine(innerFlows) { vaultState }
                } else {
                    flowOf(vaultState)
                }
            }
            .map { vaultState ->
                val actionHandlers = LinkedHashMap<String, () -> Unit>()
                val snapshot = buildDetailSnapshot(
                    vaultState,
                    leContext,
                    actionHandlers,
                    revealedIds,
                    uriAppIcons.value,
                )
                Triple(vaultState, snapshot, actionHandlers)
            }

    /** The live TOTP badge channel of [cipherDetailFlows], projected from its [latest] state. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun cipherDetailTotp(
        latest: StateFlow<VaultViewState?>,
        getTotpCode: GetTotpCodeWithOffset,
    ): Flow<VaultDetailTotpSnapshot?> =
        latest.filterNotNull()
            .map { vaultState ->
                val content = vaultState.content as? VaultViewState.Content.Cipher
                val tokens = content?.items
                    ?.filterIsInstance<VaultViewItem.Totp>()
                    ?.map { item -> item.id to item.totp }
                    .orEmpty()
                content?.data?.id to tokens
            }
            .distinctUntilChanged()
            .flatMapLatest { (cipherId, tokens) ->
                when {
                    cipherId == null -> flowOf(null)
                    tokens.isEmpty() -> flowOf(VaultDetailTotpSnapshot(cipherId, emptyMap()))
                    else -> combine(
                        tokens.map { (id, token) ->
                            totpBadgeFlow(getTotpCode, token).map { badge -> id to badge }
                        },
                    ) { badges -> VaultDetailTotpSnapshot(cipherId, badges.toMap()) }
                }
            }

    /**
     * Observes the single-slot root detail pane: runs [cipherDetailFlows]
     * while the vault is unlocked and delivers each [VaultDetailSnapshot] on the
     * main thread, caching the live producer closures behind controller fields for
     * [invokeVaultAction] / [toggleVaultFavorite]. The live TOTP badges of the shown
     * cipher go to [onTotpChange], also on the main thread.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeCipherDetail(
        onChange: (VaultDetailSnapshot) -> Unit,
        onTotpChange: (VaultDetailTotpSnapshot?) -> Unit,
    ): KeyguardCancellable {
        return ctx.launchSessionObserver(
            onLocked = {
                latestVaultContent = null
                vaultActionHandlers = emptyMap()
                detailTarget.value = null
                onChange(VaultDetailSnapshot.empty)
                onTotpChange(null)
            },
        ) { state ->
            val sessionKoin = state.sessionKoin
            detailTarget
                .flatMapLatest { target ->
                    if (target == null) {
                        flowOf<Triple<VaultViewState, VaultDetailSnapshot, LinkedHashMap<String, () -> Unit>>?>(null)
                    } else {
                        // Run each cipher's producer inside a child scope tied to this
                        // inner flow, so `flatMapLatest` cancels it when the target
                        // changes. cipherDetailFlows does `scope.launch { … }`,
                        // so without this the producer collection would leak onto the
                        // long-lived observer scope on every reselection.
                        channelFlow {
                            val flows = cipherDetailFlows(
                                this,
                                sessionKoin,
                                target.itemId,
                                target.accountId,
                                navigationInterceptorProvider(sessionKoin),
                            )
                            launch {
                                flows.totp.collectOnMain { onTotpChange(it) }
                            }
                            flows.snapshots.collect { send(it) }
                        }
                    }
                }
                .collectOnMain { triple ->
                    if (triple == null) {
                        latestVaultContent = null
                        vaultActionHandlers = emptyMap()
                        onChange(VaultDetailSnapshot.empty)
                        onTotpChange(null)
                    } else {
                        latestVaultContent = triple.first.content as? VaultViewState.Content.Cipher
                        vaultActionHandlers = triple.third
                        onChange(triple.second)
                    }
                }
        }
    }

    /**
     * Sets (or clears, with null ids) the root detail pane's target. The long-lived
     * [observeCipherDetail] observer swaps producers in place; passing nulls shows the
     * empty pane without tearing the observer down.
     */
    fun setDetailTarget(itemId: String?, accountId: String?) {
        detailTarget.value = if (itemId != null && accountId != null) {
            DetailTarget(itemId, accountId)
        } else {
            null
        }
    }

    /**
     * Instance-keyed variant for the navigation stack. Runs the same producer +
     * projection in the supplied [scope] (the stack entry's lifetime) against the
     * supplied [sessionKoin], and delivers each emission through [publish] on the main
     * thread together with the entry's own action handler map and favourite toggle —
     * so the stack entry owns its slot instead of sharing the controller's fields.
     * The live TOTP badges go to [publishTotp] (main thread) instead. Suspends until
     * [scope] is cancelled (the entry is popped or the vault locks).
     */
    suspend fun produceDetailInto(
        scope: CoroutineScope,
        sessionKoin: Scope,
        itemId: String,
        accountId: String,
        interceptor: (NavigationIntent) -> Boolean,
        publishTotp: (VaultDetailTotpSnapshot?) -> Unit,
        publish: suspend (VaultDetailSnapshot, Map<String, () -> Unit>, favourite: (() -> Unit)?) -> Unit,
    ) {
        val flows = cipherDetailFlows(scope, sessionKoin, itemId, accountId, interceptor)
        scope.launch {
            flows.totp.collectOnMain { publishTotp(it) }
        }
        flows.snapshots
            .collectOnMain { (vaultState, snapshot, actionHandlers) ->
                val content = vaultState.content as? VaultViewState.Content.Cipher
                val favourite = content?.onFavourite?.let { onFavourite ->
                    { onFavourite(!content.data.favorite) }
                }
                publish(snapshot, actionHandlers, favourite)
            }
    }

    /**
     * Builds the Swift-facing [VaultDetailSnapshot], filling [actionHandlers] so
     * the snapshot's string ids map back to the live producer closures.
     */
    private suspend fun buildDetailSnapshot(
        state: VaultViewState,
        leContext: LeContext,
        actionHandlers: LinkedHashMap<String, () -> Unit>,
        revealedIds: MutableStateFlow<Set<String>>,
        uriAppIcons: Map<VaultUriIcon.App, String?>,
    ): VaultDetailSnapshot {
        return when (val content = state.content) {
            is VaultViewState.Content.Cipher -> {
                val data = content.data
                // The Compose row carries the Gravatar inside a composable the
                // bridge cannot read, so derive the URL through the same shared
                // use case — it returns nothing while the preference is off.
                val usernameAvatarUrl = data.login?.username
                    ?.let { username ->
                        getGravatarUrl(username)
                            .attempt()
                            .bind()
                            .getOrNull()
                            ?.url
                    }
                // Registers the gated reveal handler for a concealed VALUE / CARD row
                // and returns its handler id. The handler runs the shared producer's
                // `Visibility.transformUserEvent` — the SAME `executeWithRePrompt` path
                // the copy action uses — so the master-password / biometric prompt
                // fires BEFORE the value is disclosed. The setter only runs on success
                // (the user passed the prompt, or the cipher has no reprompt), and only
                // then is the id added to [revealedIds], re-running the builder so the
                // value is projected with isVisible = true.
                val onRequestReveal: (VaultViewItem) -> String? = { item ->
                    val visibility: Visibility? = when (item) {
                        is VaultViewItem.Value -> item.visibility
                        is VaultViewItem.Card -> item.visibility
                        else -> null
                    }
                    if (visibility == null) {
                        null
                    } else {
                        val handlerId = "${item.id}:reveal"
                        actionHandlers[handlerId] = {
                            if (item.id in revealedIds.value) {
                                // Re-hide. Mirrors the Compose hide path
                                // (`transformUserEvent(false)`), which is NOT gated —
                                // hiding never discloses anything — and which writes the
                                // shared global reveal sink to `false`, hiding every
                                // concealed field at once. Match that by clearing the
                                // whole revealed set.
                                visibility.transformUserEvent(false) { }
                                revealedIds.value = emptySet()
                            } else {
                                // Request a reveal. This runs `executeWithRePrompt`
                                // inside `transformUserEvent`, firing the elevated-access
                                // prompt on a reprompt cipher; the setter (and so the
                                // disclosure) only runs on success.
                                visibility.transformUserEvent(true) { newValue ->
                                    if (newValue) {
                                        revealedIds.update { it + item.id }
                                    }
                                }
                            }
                        }
                        handlerId
                    }
                }
                val items = buildVaultItemSnapshots(
                    items = content.items,
                    notesText = data.notes,
                    leContext = leContext,
                    actionHandlers = actionHandlers,
                    usernameAvatarUrl = usernameAvatarUrl,
                    revealedIds = revealedIds.value,
                    onRequestReveal = onRequestReveal,
                    uriAppIcons = uriAppIcons,
                )
                // The top-level toolbar / header actions, mirroring the Compose
                // `VaultViewCipherTitleActions`: a dedicated edit button plus the
                // overflow menu. Their closures are registered in the same
                // [actionHandlers] map as the field-row actions, so both the root
                // pane ([invokeVaultAction]) and stacked entries ([invokeEntryAction])
                // route them with no extra wiring.
                val editActionId = content.onEdit?.let { onEdit ->
                    "header:edit".also { id -> actionHandlers[id] = onEdit }
                }
                val actions = content.actions
                    .toHeaderActionSnapshots("header", leContext, actionHandlers)

                VaultDetailSnapshot(
                    title = data.name,
                    typeIcon = data.type.name,
                    favorite = data.favorite,
                    isLoading = false,
                    notFound = false,
                    cipherId = data.id,
                    items = items,
                    iconUrl = content.icon.resolveWebsiteIconUrl(),
                    iconPlaceholder = VaultItemIcon.TextIcon.short(data.name).text,
                    editActionId = editActionId,
                    actions = actions,
                )
            }

            is VaultViewState.Content.NotFound ->
                VaultDetailSnapshot.empty.copy(notFound = true)

            is VaultViewState.Content.Loading ->
                VaultDetailSnapshot.empty.copy(isLoading = true)
        }
    }

    /**
     * Invokes a vault detail item action (copy / open / toggle / retry / etc.)
     * by its synthesized snapshot id. The closure runs inside the shared producer.
     */
    fun invokeVaultAction(id: String) {
        vaultActionHandlers.invokeAction(id)
    }

    /**
     * Toggles the favourite flag of the currently observed cipher. No-op unless
     * the producer exposes the favourite action (e.g. read-only ciphers).
     */
    fun toggleVaultFavorite() {
        val content = latestVaultContent ?: return
        content.onFavourite?.invoke(!content.data.favorite)
    }
}

/** Native DI cannot discover concrete bindings through the extractor interface. */
@Suppress("UNCHECKED_CAST")
internal fun Koin.appleCipherLinkInfoExtractors(): List<LinkInfoExtractor<LinkInfo, LinkInfo>> =
    listOf(
        get<LinkInfoPlatformExtractor>(),
        get<LinkInfoExtractorExecute>(),
    ) as List<LinkInfoExtractor<LinkInfo, LinkInfo>>
