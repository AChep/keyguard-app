package com.artemchep.keyguard.feature.navigation.state

import arrow.core.Either
import arrow.core.getOrElse
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.toIO
import com.artemchep.keyguard.common.service.state.impl.toSchema
import com.artemchep.keyguard.common.usecase.GetScreenState
import com.artemchep.keyguard.common.usecase.PutScreenState
import com.artemchep.keyguard.common.util.flow.combineToList
import com.artemchep.keyguard.feature.crashlytics.crashlyticsAttempt
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException

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

    private class FailedToSerializeScreenStateException(
        message: String,
        e: Throwable,
    ) : IOException(message, e)

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
                            .crashlyticsAttempt { e ->
                                val schema = Json.encodeToString(state.toSchema())
                                FailedToSerializeScreenStateException(
                                    message = schema,
                                    e = e,
                                )
                            }
                    }
                    .combineToList()
            }
            .debounce(SAVE_DEBOUNCE_MS) // no need to save all of the events
            .onEach { entries ->
                // Start by using the restored state. This is needed because
                // of this flow:
                // 1. A user has all of options configured.
                // 2. A user opens a screen for a super brief moment, that
                // either loads a different set of options or loads only
                // a set of all options.
                // 3. Previous state gets overwritten.
                val state = restoredState.toMutableMap()
                entries.forEach { result ->
                    val entry = result.getOrElse {
                        return@forEach
                    }
                    state[entry.first] = entry.second
                }
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
