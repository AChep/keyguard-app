package com.artemchep.keyguard.feature.home.vault.apple

import com.artemchep.keyguard.feature.attachments.SelectableItemState
import com.artemchep.keyguard.feature.home.vault.model.VaultItem2
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

fun VaultItem2.Item.localStateChanges(): Flow<Unit> =
    when (val source = localStateSource) {
        is VaultItem2.Item.LocalStateSource.PerItem ->
            source.stateFlow.map { }

        is VaultItem2.Item.LocalStateSource.Shared ->
            source.stateFlow
                .map { it.forItem(interactionId) }
                .distinctUntilChanged()
                .map { }
    }

/** The row's current selection state, mirroring the Compose row's derivation. */
fun VaultItem2.Item.currentSelectableItemState(): SelectableItemState =
    when (val source = localStateSource) {
        is VaultItem2.Item.LocalStateSource.PerItem ->
            source.stateFlow.value.selectableItemState

        is VaultItem2.Item.LocalStateSource.Shared -> {
            val state = source.stateFlow.value.forItem(interactionId)
            val onToggleSelection = { source.onToggleSelection(interactionId) }
            SelectableItemState(
                selecting = state.selecting,
                selected = state.selected,
                can = source.canSelect,
                onClick = onToggleSelection.takeIf { state.selecting },
                onLongClick = onToggleSelection
                    .takeIf { !state.selecting && source.canSelect },
            )
        }
    }
