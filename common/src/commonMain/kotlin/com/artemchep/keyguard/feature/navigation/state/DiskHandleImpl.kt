package com.artemchep.keyguard.feature.navigation.state

import arrow.core.Either
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.toIO
import com.artemchep.keyguard.common.usecase.GetScreenState
import com.artemchep.keyguard.common.usecase.PutScreenState
import com.artemchep.keyguard.common.util.flow.combineToList
import com.artemchep.keyguard.feature.crashlytics.crashlyticsTap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update

class DiskHandleImpl private constructor(
    private val scope: CoroutineScope,
    private val putScreenState: PutScreenState,
    private val key: String,
    override val restoredState: Map<String, Any?>,
) : DiskHandle {
    companion object {
        private const val SAVE_DEBOUNCE_MS = 180L

        suspend fun read(
            scope: CoroutineScope,
            getScreenState: GetScreenState,
            putScreenState: PutScreenState,
            key: String,
        ): DiskHandle {
            val restoredState = getScreenState(key)
                .toIO()
                .crashlyticsTap()
                .attempt()
                .bind()
                .getOrNull()
                .orEmpty()
            return DiskHandleImpl(
                scope = scope,
                putScreenState = putScreenState,
                key = key,
                restoredState = restoredState,
            )
        }
    }

    private val registrySink = MutableStateFlow(persistentMapOf<String, Flow<Any?>>())

    init {
        registrySink
            .flatMapLatest { state ->
                state.entries
                    .map { (key, value) ->
                        // Combine flow of values with a
                        // key of the variable.
                        value
                            .map { f -> key to f }
                    }
                    .combineToList()
            }
            .debounce(SAVE_DEBOUNCE_MS) // no need to save all of the events
            .onEach { entries ->
                val state = entries.toMap()
                val result = tryWrite(state)
                if (result is Either.Left) {
                    val e = result.value
                    e.printStackTrace()
                }
            }
            .launchIn(scope)
    }

    private suspend fun tryWrite(state: Map<String, Any?>) = putScreenState(key, state)
        .attempt()
        .bind()

    override fun link(key: String, flow: Flow<Any?>) = registrySink.update { state ->
        val savedFlow = state[key]
        if (savedFlow != null) {
            require(savedFlow === flow) {
                "Tried to link a flow with a disk, but a collision has occurred."
            }
            state
        } else {
            state.put(key, flow)
        }
    }

    override fun unlink(key: String) = registrySink.update { state ->
        state.remove(key)
    }
}
