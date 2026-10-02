package com.artemchep.keyguard.apple.vault

import androidx.compose.ui.graphics.Color
import com.artemchep.keyguard.AppMode
import com.artemchep.keyguard.apple.core.sessionKoin
import com.artemchep.keyguard.common.service.placeholder.PlaceholderFactoryRegistry
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
import com.artemchep.keyguard.apple.core.collectOnMain
import com.artemchep.keyguard.apple.core.newHeadlessStateFlowScope
import com.artemchep.keyguard.apple.dialog.DialogController
import com.artemchep.keyguard.apple.model.buildVaultItemSnapshots
import com.artemchep.keyguard.apple.model.resolveUriAppIcons
import com.artemchep.keyguard.common.service.app.parser.IosAppAppStoreParser
import com.artemchep.keyguard.common.service.app.parser.AndroidAppGooglePlayParser
import com.artemchep.keyguard.feature.home.vault.model.VaultUriIcon
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

internal class CipherDetailController(
    private val ctx: CoreContext,
    private val dialogController: DialogController,
) {
    private val getGravatarUrl: GetGravatarUrl by lazy { ctx.koin.get() }

    /**
     * Defaults to the dialog-only interceptor; [KeyguardCore] replaces it with the navigation stack's
     * interceptor so full-screen routes (a folder chip, an associated login) push onto the stack instead
     * of being dropped.
     */
    var navigationInterceptorProvider: (Scope) -> ((NavigationIntent) -> Boolean) =
        { sessionKoin -> dialogController.navigationInterceptor(sessionKoin = sessionKoin) }

    private class CipherDetailFlows(
        val snapshots: Flow<Triple<VaultViewState, VaultDetailSnapshot, LinkedHashMap<String, () -> Unit>>>,
        /** `null` while the cipher is not loaded. */
        val totp: Flow<VaultDetailTotpSnapshot?>,
    )

    /** Touches no controller state, so it is safe to run many instances concurrently. */
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
        // Drives the TOTP badge, so the codes, countdown and progress are computed by
        // shared Kotlin, never re-derived in Swift.
        val getTotpCode = sessionKoin.get<GetTotpCodeWithOffset>()

        // The producer is cold, so fan out from one StateFlow.
        val latest = MutableStateFlow<VaultViewState?>(null)
        scope.launch {
            producerFlow.collect { vaultState -> latest.value = vaultState }
        }

        // The bridge analog of the per-field Compose `rememberVisibilityState`. Scoped to
        // this producer run, so it resets when the detail is re-opened or the vault locks.
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

        val snapshots = cipherDetailSnapshots(latest, leContext, revealedIds, uriAppIcons)

        // The TOTP countdown ticks every second; folding it into [snapshots] would
        // rebuild the whole detail (rows, handler maps, avatar lookup) on every tick.
        val totp = cipherDetailTotp(latest, getTotpCode)
        return CipherDetailFlows(
            snapshots = snapshots,
            totp = totp,
        )
    }

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

    /**
     * The reprompt lock, TOTP codes, attachment progress and reveal set carry live inner state the top-level
     * state does NOT re-emit for, so they are combined in to re-run the builder.
     */
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

    /** Creates a producer and action map owned by one detail presentation. */
    fun makeSession(
        itemId: String,
        accountId: String,
    ): CipherDetailSession = CipherDetailSession { publish, publishTotp ->
        ctx.launchSessionObserver(
            onLocked = {
                publish(VaultDetailSnapshot.empty, emptyMap(), null)
                publishTotp(null)
            },
        ) { state ->
            produceDetailInto(
                scope = this,
                sessionKoin = state.sessionKoin,
                itemId = itemId,
                accountId = accountId,
                interceptor = navigationInterceptorProvider(state.sessionKoin),
                publishTotp = publishTotp,
                publish = publish,
            )
        }
    }

    /**
     * Produces one presentation's snapshots and actions without retaining them in the controller.
     * Used by both navigation entries and standalone sessions. Delivers on the main thread and
     * suspends until [scope] is cancelled (the presentation closes or the vault locks).
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
                // Returns the reveal handler id of a concealed VALUE / CARD row, null for other rows.
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
                                // `transformUserEvent` runs `executeWithRePrompt` (the same gate
                                // as the copy action), so the elevated-access prompt fires BEFORE
                                // the value is disclosed; the setter only runs on success.
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
                // Mirrors the Compose `VaultViewCipherTitleActions`: an edit button plus the
                // overflow menu. Registered in the same [actionHandlers] map as the field-row
                // actions, so standalone sessions and stacked entries use the same rendering.
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
}

/** Native DI cannot discover concrete bindings through the extractor interface. */
@Suppress("UNCHECKED_CAST")
internal fun Koin.appleCipherLinkInfoExtractors(): List<LinkInfoExtractor<LinkInfo, LinkInfo>> =
    listOf(
        get<LinkInfoPlatformExtractor>(),
        get<LinkInfoExtractorExecute>(),
    ) as List<LinkInfoExtractor<LinkInfo, LinkInfo>>
