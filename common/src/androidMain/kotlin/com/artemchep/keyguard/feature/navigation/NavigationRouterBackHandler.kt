@file:JvmName("NavigationRouterBackHandler2")

package com.artemchep.keyguard.feature.navigation

import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcher
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

/**
 * A definition of the distinct application component that
 * makes sense when rendered in a separate window.
 */
@Composable
fun NavigationRouterBackHandler(
    onBackPressedDispatcher: OnBackPressedDispatcher,
    content: @Composable () -> Unit,
) {
    NavigationRouterBackHandler(
        sideEffect = { handler ->
            val callback: OnBackPressedCallback = remember {
                object : OnBackPressedCallback(false) {
                    override fun handleOnBackPressed() {
                        val targetEntry = handler.eek.value.values.maxByOrNull { it.backStack.size }
                            ?: return@handleOnBackPressed
                        targetEntry.controller.queue(NavigationIntent.Pop)
                    }
                }
            }

            DisposableEffect(onBackPressedDispatcher, callback) {
                onBackPressedDispatcher.addCallback(callback)
                onDispose {
                    callback.remove()
                }
            }
            LaunchedEffect(callback, handler) {
                handler.eek
                    .map { map ->
                        map.values.maxByOrNull { it.backStack.size }
                            ?.controller
                            ?.canPop() ?: flowOf(false)
                    }
                    .flatMapLatest { it }
                    .onEach {
                        callback.isEnabled = it
                    }
                    .collect()
            }
        },
        content = content,
    )
}
