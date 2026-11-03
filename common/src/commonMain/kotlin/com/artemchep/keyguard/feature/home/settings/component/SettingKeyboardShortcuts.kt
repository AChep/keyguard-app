package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.artemchep.keyguard.feature.home.settings.KgAction
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.feature.home.settings.shortcuts.KeyboardShortcutsSettingsRoute
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.keyboard_shortcuts_title
import com.artemchep.keyguard.ui.icons.ChevronIcon
import kotlinx.coroutines.flow.flowOf
import org.jetbrains.compose.resources.stringResource
import org.koin.core.scope.Scope

@Suppress("UnusedParameter")
fun settingKeyboardShortcutsProvider(
    koinScope: Scope,
) = settingKeyboardShortcutsProvider()

// Only the JVM desktop app runs the global shortcut controller.
private val keyboardShortcutsPlatformClasses = listOf(
    Platform.Desktop.Linux::class,
    Platform.Desktop.Windows::class,
    Platform.Desktop.MacOS.Jvm::class,
    Platform.Desktop.Other::class,
)

fun settingKeyboardShortcutsProvider(): SettingComponent = platformSettingProvider(
    keyboardShortcutsPlatformClasses,
) {
    val item = SettingIi(
        platformClasses = keyboardShortcutsPlatformClasses,
        search = SettingIi.Search(
            group = "about",
            tokens = listOf(
                "keyboard",
                "shortcuts",
                "hotkey",
            ),
        ),
    ) {
        val navigationController by rememberUpdatedState(LocalNavigationController.current)
        SettingKeyboardShortcuts(
            onClick = {
                val route = KeyboardShortcutsSettingsRoute
                val intent = NavigationIntent.NavigateToRoute(route)
                navigationController.queue(intent)
            },
        )
    }
    flowOf(item)
}

@Composable
private fun SettingKeyboardShortcuts(onClick: (() -> Unit)?) {
    LocalSettingPaneComponents.current.KgAction(
        icon = Icons.Outlined.Keyboard,
        trailing = {
            ChevronIcon()
        },
        title = stringResource(Res.string.keyboard_shortcuts_title),
        onClick = onClick,
    )
}
