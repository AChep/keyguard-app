package com.artemchep.keyguard.feature.home.vault.add

import com.artemchep.keyguard.common.model.Loadable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.transformLatest

/**
 * Keeps [StateFlow.value] authoritative while deriving optional asynchronous decoration.
 *
 * `transformLatest` cannot start the next transform until a non-cooperative previous transform
 * finishes cancelling. Collecting the source independently ensures that such work can delay only
 * its decoration, never propagation of the source value itself. [Loadable.Loading] represents
 * pending work, while [Loadable.Ok] preserves a completed nullable decoration.
 */
internal fun <Value, Decoration> StateFlow<Value>.withLatestAsyncDecoration(
    decorate: suspend (Value) -> Decoration?,
): Flow<Pair<Value, Loadable<Decoration?>>> {
    val decorations: Flow<Pair<Value, Loadable<Decoration?>>> = transformLatest { source ->
        emit(source to Loadable.Loading)
        emit(source to Loadable.Ok(decorate(source)))
    }
    return combine(this, decorations) { _, decorated ->
        // Either upstream may win the notification race. Reading StateFlow.value
        // here prevents a decoration event from briefly republishing an old source.
        val current = value
        val decoration = decorated.second.takeIf { decorated.first == current }
            ?: Loadable.Loading
        current to decoration
    }
}
