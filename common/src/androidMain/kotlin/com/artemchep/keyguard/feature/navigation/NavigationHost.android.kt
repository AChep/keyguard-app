package com.artemchep.keyguard.feature.navigation

import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcher
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember

/**
 * Provides the [host] of the window to the [content] and hands
 * the Back presses of the [onBackPressedDispatcher] to it.
 */
@Composable
fun NavigationHost(
    onBackPressedDispatcher: OnBackPressedDispatcher,
    host: NavigationHost = remember { NavigationHost() },
    content: @Composable () -> Unit,
) {
    DisposableEffect(onBackPressedDispatcher, host) {
        val remove = onBackPressedDispatcher.addNavigationHostCallback(host)
        onDispose {
            remove()
        }
    }

    NavigationHost(
        host = host,
        content = content,
    )
}

/**
 * Adds a callback that is enabled while the [host] can handle Back
 * and gives Back and the Back gestures to it. Returns a function that
 * removes the callback.
 */
internal fun OnBackPressedDispatcher.addNavigationHostCallback(
    host: NavigationHost,
): () -> Unit {
    val dispatcher = this
    val callback = object : OnBackPressedCallback(host.isBackEnabled) {
        override fun handleOnBackStarted(backEvent: BackEventCompat) {
            host.startBack(backEvent.toNavigationBackEvent())
        }

        override fun handleOnBackProgressed(backEvent: BackEventCompat) {
            host.progressBack(backEvent.toNavigationBackEvent())
        }

        override fun handleOnBackCancelled() {
            host.cancelBack()
        }

        override fun handleOnBackPressed() {
            if (host.handleBack()) {
                return
            }
            // Nothing can pop after all. Disable the callback
            // and let the system handle this press.
            host.invalidateBack()
            if (!isEnabled) {
                dispatcher.onBackPressed()
            }
        }
    }
    addCallback(callback)
    val removeListener = host.addBackEnabledListener { enabled ->
        callback.isEnabled = enabled
    }
    return {
        removeListener()
        callback.remove()
    }
}

private fun BackEventCompat.toNavigationBackEvent() = NavigationBackEvent(
    progress = progress,
    swipeEdge = when (swipeEdge) {
        BackEventCompat.EDGE_LEFT -> NavigationBackEdge.LEFT
        BackEventCompat.EDGE_RIGHT -> NavigationBackEdge.RIGHT
        else -> NavigationBackEdge.NONE
    },
    touchX = touchX,
    touchY = touchY,
)
