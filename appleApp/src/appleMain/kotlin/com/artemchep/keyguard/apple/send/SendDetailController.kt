package com.artemchep.keyguard.apple.send

import androidx.compose.ui.graphics.Color
import com.artemchep.keyguard.apple.core.sessionKoin
import com.artemchep.keyguard.apple.vault.appleCipherLinkInfoExtractors
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.send.view.SendViewState
import com.artemchep.keyguard.feature.send.view.sendViewScreenStateProducer
import com.artemchep.keyguard.apple.core.CoreContext
import com.artemchep.keyguard.apple.core.KeyguardCancellable
import com.artemchep.keyguard.apple.core.collectOnMain
import com.artemchep.keyguard.apple.core.newHeadlessStateFlowScope
import com.artemchep.keyguard.apple.dialog.DialogController
import com.artemchep.keyguard.apple.model.buildVaultItemSnapshots
import com.artemchep.keyguard.apple.model.invokeAction
import com.artemchep.keyguard.apple.model.toHeaderActionSnapshots
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.ui.FlatItemAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.koin.core.scope.Scope

/**
 * The Send detail screen. Runs the shared [sendViewScreenStateProducer]
 * headlessly and projects its [SendViewState] into a [SendDetailSnapshot] (item
 * rows via the shared [buildVaultItemSnapshots]). Hands the producer the
 * [DialogController] interceptor for attachment-preview routes.
 */
internal class SendDetailController(
    private val ctx: CoreContext,
    private val dialogController: DialogController,
) {
    /**
     * Resolves the navigation interceptor the Send view producer is handed for a
     * given session DI. Defaults to the dialog-only interceptor; [KeyguardCore]
     * late-binds it to the navigation stack's composed interceptor so the Send
     * detail's "edit" action (a `SendAddRoute`) reaches the stack — which opens the
     * native edit sheet — instead of being dropped.
     */
    var navigationInterceptorProvider: (Scope) -> ((NavigationIntent) -> Boolean) =
        { sessionKoin -> dialogController.navigationInterceptor(sessionKoin = sessionKoin) }

    private var latestSendContent: SendViewState.Content.Cipher? = null
    private var sendActionHandlers: Map<String, () -> Unit> = emptyMap()

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeSendDetail(
        itemId: String,
        accountId: String,
        onChange: (SendDetailSnapshot) -> Unit,
    ): KeyguardCancellable {
        val leContext = ctx.koin.get<LeContext>()
        return ctx.launchSessionObserver(
            onLocked = {
                latestSendContent = null
                sendActionHandlers = emptyMap()
                onChange(SendDetailSnapshot.empty)
            },
        ) { state ->
            val producerScope = this
            val interceptor = navigationInterceptorProvider(state.sessionKoin)
            val producerFlow = sendViewStateFlow(producerScope, state.sessionKoin, itemId, accountId, interceptor)
            // A file Send carries an Attachment row whose download
            // status / actions live on their own StateFlows that the
            // top-level state does NOT re-emit for; combine them in
            // as invalidation triggers (the builder samples .value).
            producerFlow
                .flatMapLatest { sendState ->
                    val content = sendState.content
                    val innerFlows = (content as? SendViewState.Content.Cipher)
                        ?.items
                        ?.filterIsInstance<VaultViewItem.Attachment>()
                        ?.flatMap { listOf<Flow<Any>>(it.item.statusState, it.item.actionsState) }
                        .orEmpty()
                    if (innerFlows.isEmpty()) {
                        // combine() of no flows never emits; pass the
                        // state straight through.
                        flowOf(sendState)
                    } else {
                        combine(innerFlows) { sendState }
                    }
                }
                .map { sendState ->
                    val actionHandlers = LinkedHashMap<String, () -> Unit>()
                    val snapshot = buildSendDetailSnapshot(sendState, leContext, actionHandlers)
                    Triple(sendState, snapshot, actionHandlers)
                }
                .collectOnMain { (sendState, snapshot, actionHandlers) ->
                    latestSendContent = sendState.content as? SendViewState.Content.Cipher
                    sendActionHandlers = actionHandlers
                    onChange(snapshot)
                }
        }
    }

    /** Runs the shared [sendViewScreenStateProducer] for one Send in a headless scope tied to [scope]. */
    private suspend fun sendViewStateFlow(
        scope: CoroutineScope,
        sessionKoin: Scope,
        itemId: String,
        accountId: String,
        interceptor: (NavigationIntent) -> Boolean,
    ): Flow<SendViewState> = with(sessionKoin) {
        ctx.koin.newHeadlessStateFlowScope("send_view", scope, interceptor)
            .sendViewScreenStateProducer(
                contentColor = Color.Unspecified,
                disabledContentColor = Color.Unspecified,
                getAccounts = get(),
                getCanWrite = get(),
                getSends = get(),
                getCollections = get(),
                getOrganizations = get(),
                getFolders = get(),
                getConcealFields = get(),
                getMarkdown = get(),
                getAppIcons = get(),
                getWebsiteIcons = get(),
                getPasswordStrength = get(),
                retryCipher = get(),
                toolbox = get(),
                downloadManager = get(),
                downloadAttachment = get(),
                tfaService = get(),
                clipboardService = get(),
                getGravatarUrl = get(),
                getEnvSendUrl = get(),
                dateFormatter = get(),
                windowCoroutineScope = get(),
                linkInfoExtractors = ctx.koin.appleCipherLinkInfoExtractors(),
                confirmationRouteFactory = get(),
                sendId = itemId,
                accountId = accountId,
            )
    }

    /** Builds the Swift-facing [SendDetailSnapshot], filling [actionHandlers]. */
    private suspend fun buildSendDetailSnapshot(
        state: SendViewState,
        leContext: LeContext,
        actionHandlers: LinkedHashMap<String, () -> Unit>,
    ): SendDetailSnapshot {
        return when (val content = state.content) {
            is SendViewState.Content.Cipher -> {
                val data = content.data
                val items = buildVaultItemSnapshots(
                    items = content.items,
                    notesText = data.notes,
                    leContext = leContext,
                    actionHandlers = actionHandlers,
                )

                val headerActions = content.actions
                    .filterIsInstance<FlatItemAction>()
                    .toHeaderActionSnapshots("send", leContext, actionHandlers)

                SendDetailSnapshot(
                    title = data.name,
                    typeIcon = data.type.name,
                    canCopy = content.onCopy != null,
                    canShare = content.onShare != null,
                    canEdit = content.onEdit != null,
                    isLoading = false,
                    notFound = false,
                    actions = headerActions,
                    items = items,
                )
            }

            is SendViewState.Content.NotFound ->
                SendDetailSnapshot.empty.copy(notFound = true)

            is SendViewState.Content.Loading ->
                SendDetailSnapshot.empty.copy(isLoading = true)
        }
    }

    /** Invokes a Send detail item / header context action by its snapshot id. */
    fun invokeSendAction(id: String) {
        sendActionHandlers.invokeAction(id)
    }

    /** Copies the Send share link. No-op unless the producer exposes the action. */
    fun sendCopy() {
        latestSendContent?.onCopy?.invoke()
    }

    /** Opens the native share sheet for the Send link. No-op unless available. */
    fun sendShare() {
        latestSendContent?.onShare?.invoke()
    }

    /** Opens the Send for editing. No-op unless the producer exposes the action. */
    fun sendEdit() {
        latestSendContent?.onEdit?.invoke()
    }
}
