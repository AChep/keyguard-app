package com.artemchep.keyguard.apple.generator

import com.artemchep.keyguard.apple.core.sessionKoin
import com.artemchep.keyguard.main
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

/**
 * The generator history screen. Runs the shared [generatorHistoryStateProducer]
 * headlessly; the history use cases live in the per-session sub-DI, so this gates
 * on an unlocked vault. Projects the producer's per-item dropdown actions, the
 * multi-selection (count + bulk actions + per-item selection handle) and the
 * top-level Clear history option; the SwiftUI screen drives them through the
 * opaque-id `invokeGeneratorHistory*` / `toggleGeneratorHistorySelection` /
 * `clearGeneratorHistorySelection` methods.
 */
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
                // Thread the dialog interceptor so the producer's full-screen
                // routes reach a renderer instead of being dropped: the per-item /
                // bulk / "Clear history" Remove confirmations (ConfirmationRoute),
                // "Show in large type" (LargeTypeRoute) and "Check data breaches"
                // (PasswordLeakRoute). Pass the session DI so the breach checker
                // resolves its session-scoped dependencies.
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

    /** Runs a per-item dropdown action (copy / show in large type / remove) by its id. */
    fun invokeGeneratorHistoryItemAction(id: String) {
        itemActionHandlers.invokeAction(id)
    }

    /** Runs a top-level overflow option (Clear history) by its id. */
    fun invokeGeneratorHistoryOption(id: String) {
        optionHandlers[id]?.invoke()
    }

    /** Runs a bulk action of the active multi-selection (Remove from history) by its id. */
    fun invokeGeneratorHistorySelectionAction(id: String) {
        selectionActionHandlers.invokeAction(id)
    }

    /**
     * Toggles whether the value row with [itemId] is part of the multi-selection.
     * Routes through the producer's per-item selection handle (onClick while a
     * selection is active, otherwise onLongClick which begins one). No-op for
     * section headers or rows the producer cannot select.
     */
    fun toggleGeneratorHistorySelection(itemId: String) {
        val state = latestState ?: return
        val item = state.items
            .firstOrNull { it is GeneratorHistoryItem.Value && it.id == itemId } as? GeneratorHistoryItem.Value
            ?: return
        val selectable = item.selectableState.value
        (selectable.onClick ?: selectable.onLongClick)?.invoke()
    }

    /** Clears the active multi-selection. No-op unless something is selected. */
    fun clearGeneratorHistorySelection() {
        latestState?.selection?.onClear?.invoke()
    }

    /** Selects every value row. No-op unless the producer offers a select-all handle. */
    fun selectAllGeneratorHistory() {
        latestState?.selection?.onSelectAll?.invoke()
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

    /**
     * Builds the Swift-facing [GeneratorHistorySnapshot], filling [itemHandlers] /
     * [optionHandlers] / [selectionHandlers] so each snapshot id maps back to the
     * live producer closure. Runs on the producer pipeline; the caller installs the
     * maps on Main.
     */
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
                    // Reuse the options-menu projection so the dropdown keeps its
                    // section dividers (copy / large type / breaches / remove); the
                    // ids are namespaced by the item id so they cannot collide.
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
