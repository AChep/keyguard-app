package com.artemchep.keyguard.apple.generator

import com.artemchep.keyguard.apple.core.sessionKoin
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.feature.generator.history.GeneratorHistoryItem
import com.artemchep.keyguard.feature.generator.history.GeneratorHistoryState
import com.artemchep.keyguard.feature.generator.history.generatorHistoryStateProducer
import com.artemchep.keyguard.apple.core.CoreContext
import com.artemchep.keyguard.apple.core.KeyguardCancellable
import com.artemchep.keyguard.apple.core.collectOnMain
import com.artemchep.keyguard.apple.core.newHeadlessStateFlowScope
import com.artemchep.keyguard.apple.dialog.DialogController
import com.artemchep.keyguard.apple.model.buildMenuActionSnapshots
import com.artemchep.keyguard.apple.model.buildSelectionActionSnapshots
import com.artemchep.keyguard.apple.model.invokeAction
import com.artemchep.keyguard.platform.LeContext
import kotlinx.coroutines.flow.map

/** The history use cases live in the per-session sub-DI, so this gates on an unlocked vault. */
internal class GeneratorHistoryController(
    private val ctx: CoreContext,
    private val dialogController: DialogController,
) {
    private var latestState: GeneratorHistoryState? = null
    private var itemActionHandlers: Map<String, () -> Unit> = emptyMap()
    private var optionHandlers: Map<String, () -> Unit> = emptyMap()
    private var selectionActionHandlers: Map<String, () -> Unit> = emptyMap()

    fun observeGeneratorHistory(
        onChange: (GeneratorHistorySnapshot) -> Unit,
    ): KeyguardCancellable {
        val leContext = ctx.koin.get<LeContext>()
        return ctx.launchSessionObserver(
            onLocked = {
                latestState = null
                itemActionHandlers = emptyMap()
                optionHandlers = emptyMap()
                selectionActionHandlers = emptyMap()
                onChange(GeneratorHistorySnapshot.empty)
            },
        ) { state ->
            val producerScope = this
            val producerFlow = with(state.sessionKoin) {
                // Thread the dialog interceptor so the producer's dialog routes (Remove confirmations, large type,
                // data breaches) reach a renderer instead of being dropped. The session DI lets the breach checker
                // resolve its session-scoped dependencies.
                ctx.koin.newHeadlessStateFlowScope(
                    "generator_history",
                    producerScope,
                    dialogController.navigationInterceptor(sessionKoin = state.sessionKoin),
                )
                    .generatorHistoryStateProducer(
                        getGeneratorHistory = get(),
                        removeGeneratorHistory = get(),
                        removeGeneratorHistoryById = get(),
                        keyPairExport = get(),
                        publicKeyExport = get(),
                        privateKeyExport = get(),
                        gpgKeyExport = get(),
                        gpgPublicKeyExport = get(),
                        gpgPrivateKeyExport = get(),
                        dateFormatter = get(),
                        clipboardService = get(),
                        confirmationRouteFactory = get(),
                    )
            }
            // Build the snapshot + handler maps off the main thread, then install
            // the maps and deliver on the main thread together.
            producerFlow
                .map { loadable -> projectGeneratorHistory(loadable.getOrNull(), leContext) }
                .collectOnMain { projection ->
                    latestState = projection.state
                    itemActionHandlers = projection.itemHandlers
                    optionHandlers = projection.optionHandlers
                    selectionActionHandlers = projection.selectionHandlers
                    onChange(projection.snapshot)
                }
        }
    }

    fun invokeGeneratorHistoryItemAction(id: String) {
        itemActionHandlers.invokeAction(id)
    }

    fun invokeGeneratorHistoryOption(id: String) {
        optionHandlers[id]?.invoke()
    }

    fun invokeGeneratorHistorySelectionAction(id: String) {
        selectionActionHandlers.invokeAction(id)
    }

    /**
     * Goes through the producer's per-item selection handle: onClick while a selection is active, otherwise
     * onLongClick, which begins one. No-op for section headers or rows the producer cannot select.
     */
    fun toggleGeneratorHistorySelection(itemId: String) {
        val state = latestState ?: return
        val item = state.items
            .firstOrNull { it is GeneratorHistoryItem.Value && it.id == itemId } as? GeneratorHistoryItem.Value
            ?: return
        val selectable = item.selectableState.value
        (selectable.onClick ?: selectable.onLongClick)?.invoke()
    }

    fun clearGeneratorHistorySelection() {
        latestState?.selection?.onClear?.invoke()
    }

    private suspend fun projectGeneratorHistory(
        state: GeneratorHistoryState?,
        leContext: LeContext,
    ): GeneratorHistoryProjection {
        val itemHandlers = LinkedHashMap<String, () -> Unit>()
        val optionHandlers = LinkedHashMap<String, () -> Unit>()
        val selectionHandlers = LinkedHashMap<String, () -> Unit>()
        val snapshot = buildGeneratorHistorySnapshot(
            state = state,
            leContext = leContext,
            itemHandlers = itemHandlers,
            optionHandlers = optionHandlers,
            selectionHandlers = selectionHandlers,
        )
        return GeneratorHistoryProjection(
            state = state,
            snapshot = snapshot,
            itemHandlers = itemHandlers,
            optionHandlers = optionHandlers,
            selectionHandlers = selectionHandlers,
        )
    }

    private suspend fun buildGeneratorHistorySnapshot(
        state: GeneratorHistoryState?,
        leContext: LeContext,
        itemHandlers: LinkedHashMap<String, () -> Unit>,
        optionHandlers: LinkedHashMap<String, () -> Unit>,
        selectionHandlers: LinkedHashMap<String, () -> Unit>,
    ): GeneratorHistorySnapshot {
        state ?: return GeneratorHistorySnapshot.empty
        val items = state.items.map { item ->
            when (item) {
                is GeneratorHistoryItem.Section -> GeneratorHistoryItemSnapshot(
                    id = item.id,
                    kind = GeneratorHistoryItemKind.SECTION,
                    title = item.text.orEmpty(),
                    date = null,
                    type = null,
                )

                is GeneratorHistoryItem.Value -> {
                    val selectable = item.selectableState.value
                    // Reuse the options-menu projection so the dropdown keeps its section dividers;
                    // the ids are namespaced by the item id so they cannot collide.
                    val actions = buildMenuActionSnapshots(
                        actions = item.dropdown,
                        idPrefix = "item:${item.id}",
                        leContext = leContext,
                        handlers = itemHandlers,
                    )
                    GeneratorHistoryItemSnapshot(
                        id = item.id,
                        kind = GeneratorHistoryItemKind.VALUE,
                        title = item.title,
                        date = item.text,
                        type = item.type?.name,
                        actions = actions,
                        selected = selectable.selected,
                        selecting = selectable.selecting,
                    )
                }
            }
        }

        val options = buildMenuActionSnapshots(
            actions = state.options,
            idPrefix = "option",
            leContext = leContext,
            handlers = optionHandlers,
        )
        val selection = state.selection
        val selectionActions = buildSelectionActionSnapshots(
            actions = selection?.actions,
            leContext = leContext,
            handlers = selectionHandlers,
        )

        return GeneratorHistorySnapshot(
            loaded = true,
            items = items,
            options = options,
            selectionCount = selection?.count ?: 0,
            selectionActions = selectionActions,
        )
    }

    private data class GeneratorHistoryProjection(
        val state: GeneratorHistoryState?,
        val snapshot: GeneratorHistorySnapshot,
        val itemHandlers: Map<String, () -> Unit>,
        val optionHandlers: Map<String, () -> Unit>,
        val selectionHandlers: Map<String, () -> Unit>,
    )
}
