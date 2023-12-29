package com.artemchep.keyguard.android.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.PowerManager
import android.view.Display
import androidx.core.content.getSystemService
import com.artemchep.keyguard.common.model.Screen
import com.artemchep.keyguard.common.util.flow.observerFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn

private const val SCREEN_ON_CHECK_INTERVAL = 2000L

fun screenFlow(
    context: Context,
): Flow<Screen> = observerFlow<Screen> { callback ->
    val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val screen = context.screenState()
            callback(screen)
        }
    }
    val intentFilter = IntentFilter()
        .apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
    context.registerReceiver(broadcastReceiver, intentFilter)

    val screen = context.screenState()
    callback(screen)
    return@observerFlow {
        context.unregisterReceiver(broadcastReceiver)
    }
}
    .flatMapLatest { screen ->
        when (screen) {
            is Screen.On -> flow {
                emit(screen)

                // While the screen is on, send the update every
                // few seconds. This is needed because of the
                // Always On mode which may not send the 'screen is off'
                // broadcast.
                while (true) {
                    delay(SCREEN_ON_CHECK_INTERVAL)
                    val newScreen = context.screenState()
                    emit(newScreen)
                }
            }

            else -> flowOf(screen)
        }
    }
    .flowOn(Dispatchers.Main)
    .distinctUntilChanged()

/**
 * It does not store the screen state, it
 * retrieves it every time.
 */
private fun Context.screenState(): Screen =
    when (isScreenOn()) {
        true -> Screen.On
        false -> Screen.Off
    }

/**
 * Returns `true` if the screen is turned on,
 * `false` otherwise.
 */
private fun Context.isScreenOn(): Boolean {
    run {
        val dm = getSystemService<DisplayManager>()
        val displays = dm?.getDisplays(null)?.takeIf { it.isNotEmpty() } ?: return@run

        var display: Display? = null
        for (d in displays) {
            val virtual = d.flags.and(Display.FLAG_PRESENTATION) != 0
            if (d.isValid && !virtual) {
                display = d

                val type: Int
                try {
                    val method = Display::class.java.getDeclaredMethod("getType")
                    method.isAccessible = true
                    type = method.invoke(d) as Int
                } catch (e: Exception) {
                    continue
                }

                if (type == 1 /* built-in display */) {
                    break
                }
            }
        }

        if (display == null) {
            return false
        }

        return display.state == Display.STATE_ON
    }

    val pm = getSystemService<PowerManager>()
    return pm?.isInteractive ?: false
}
