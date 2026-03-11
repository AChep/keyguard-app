package com.artemchep.keyguard.common.service.quicksearch

import com.artemchep.autotype.GlobalHotKeyKey
import com.artemchep.autotype.GlobalHotKeyRegistrationResult
import com.artemchep.autotype.GlobalHotKeySpec
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform

fun interface GlobalHotKeyRegistrar {
    fun register(
        hotKey: GlobalHotKeySpec,
        onPressed: () -> Unit,
    ): GlobalHotKeyRegistrationResult
}

class QuickSearchHotkeyService(
    private val windowManager: QuickSearchWindowManager,
    private val globalHotKeyRegistrar: GlobalHotKeyRegistrar,
    private val beforeOpen: () -> Unit = {},
    private val platform: Platform = CurrentPlatform,
) {
    companion object {
        internal fun quickSearchHotKey(platform: Platform): GlobalHotKeySpec =
            when (platform) {
                is Platform.Desktop.MacOS -> GlobalHotKeySpec(
                    key = GlobalHotKeyKey.Space,
                    isShiftPressed = true,
                    isMetaPressed = true,
                )

                is Platform.Desktop.Windows -> GlobalHotKeySpec(
                    key = GlobalHotKeyKey.Space,
                    isCtrlPressed = true,
                    isShiftPressed = true,
                )

                is Platform.Desktop.Linux -> GlobalHotKeySpec(
                    key = GlobalHotKeyKey.Space,
                    isCtrlPressed = true,
                    isShiftPressed = true,
                )

                else -> GlobalHotKeySpec(
                    key = GlobalHotKeyKey.Space,
                    isCtrlPressed = true,
                    isShiftPressed = true,
                )
            }
    }

    fun start(): () -> Unit {
        val hotKey = quickSearchHotKey(platform)
        val result = globalHotKeyRegistrar.register(
            hotKey = hotKey,
            onPressed = {
                beforeOpen()
                windowManager.requestOpen()
            },
        )
        val registration = when (result) {
            is GlobalHotKeyRegistrationResult.Success -> result.registration
            is GlobalHotKeyRegistrationResult.Failure -> null
        }
        return {
            registration?.close()
        }
    }
}
