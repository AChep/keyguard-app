package com.artemchep.keyguard.apple.generator

import com.artemchep.keyguard.apple.core.sessionKoin
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.feature.generator.history.GeneratorHistoryItem
import com.artemchep.keyguard.feature.generator.history.GeneratorHistoryState
import com.artemchep.keyguard.feature.generator.history.generatorHistoryStateProducer
import com.artemchep.keyguard.apple.core.CoreContext
import com.artemchep.keyguard.apple.core.ListSession
import com.artemchep.keyguard.apple.core.ListSessionActions
import com.artemchep.keyguard.apple.core.toggle
import com.artemchep.keyguard.apple.core.collectOnMain
import com.artemchep.keyguard.apple.core.newHeadlessStateFlowScope
import com.artemchep.keyguard.apple.dialog.DialogController
import com.artemchep.keyguard.apple.model.buildMenuActionSnapshots
import com.artemchep.keyguard.apple.model.buildSelectionActionSnapshots
import com.artemchep.keyguard.platform.LeContext
import kotlinx.coroutines.flow.map

/** The history use cases live in the per-session sub-DI, so this gates on an unlocked vault. */
internal class GeneratorHistoryController(
    private val ctx: CoreContext,
    private val dialogController: DialogController,
) {
    fun makeSession(): ListSession<GeneratorHistorySnapshot> = ListSession { publish ->
        val leContext = ctx.koin.get<LeContext>()
        ctx.launchSessionObserver(
            onLocked = {
                publish(GeneratorHistorySnapshot.empty, ListSessionActions())
            },
            onTeardown = { publish(GeneratorHistorySnapshot.empty, ListSessionActions()) },
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
                .collectOnMain { (snapshot, actions) -> publish(snapshot, actions) }
        }
    }

    private suspend fun projectGeneratorHistory(
        state: GeneratorHistoryState?,
        leContext: LeContext,
    ): Pair<GeneratorHistorySnapshot, ListSessionActions> {
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
        val items = state?.items.orEmpty()
        return snapshot to ListSessionActions(
            items = itemHandlers,
            screen = optionHandlers,
            selection = selectionHandlers,
            toggleSelection = { id ->
                items.firstNotNullOfOrNull { (it as? GeneratorHistoryItem.Value)?.takeIf { item -> item.id == id } }
                    ?.selectableState?.value?.toggle()
            },
            clearSelection = state?.selection?.onClear,
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
}
