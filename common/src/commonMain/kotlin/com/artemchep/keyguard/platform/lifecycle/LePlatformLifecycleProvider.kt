package com.artemchep.keyguard.platform.lifecycle

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class LePlatformLifecycleProvider(
    private val scope: CoroutineScope,
    private val cryptoGenerator: CryptoGenerator,
) {
    private val sink = MutableStateFlow<PersistentMap<String, LeLifecycleState>>(persistentMapOf())

    val lifecycleStateFlow: StateFlow<LeLifecycleState> = sink
        .map { state ->
            state.values.maxOrNull()
                ?: LeLifecycleState.CREATED
        }
        .stateIn(
            scope,
            SharingStarted.Lazily,
            initialValue = LeLifecycleState.INITIALIZED,
        )

    fun register(
        lifecycleFlow: StateFlow<LeLifecycleState>,
    ): () -> Unit {
        val id = cryptoGenerator.uuid()
        val job = scope.launch {
            lifecycleFlow
                .onEach { lifecycleState ->
                    if (!isActive) {
                        return@onEach
                    }

                    sink.update { state ->
                        state.put(id, lifecycleState)
                    }
                }
                .collect()
        }

        return {
            job.cancel()
            // Remove the last known state out of the
            // lifecycle state map.
            sink.update { state ->
                state.remove(id)
            }
        }
    }
}

@Composable
fun LaunchLifecycleProviderEffect(
    processLifecycleProvider: LePlatformLifecycleProvider,
) {
    val lifecycleFlow = LocalLifecycleStateFlow
    DisposableEffect(
        processLifecycleProvider,
        lifecycleFlow,
    ) {
        val unregister = processLifecycleProvider.register(lifecycleFlow)
        onDispose {
            unregister()
        }
    }
}
