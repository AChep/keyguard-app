package com.artemchep.keyguard.common.model

import com.artemchep.keyguard.platform.Platform
import kotlin.test.Test
import kotlin.test.assertEquals

class GlobalShortcutsTest {
    private val macosQuickSearch =
        GlobalShortcutBinding(GlobalShortcutKey.Space, shift = true, meta = true)

    private fun macosPlatforms(majorVersion: Int) = listOf(
        Platform.Desktop.MacOS.Jvm(majorVersion = majorVersion),
        Platform.Desktop.MacOS.Native(majorVersion = majorVersion),
    )

    @Test fun `quick search on macOS 27 and later uses option command backslash`() {
        listOf(27, 28).flatMap(::macosPlatforms).forEach { platform ->
            assertEquals(
                GlobalShortcutBinding(GlobalShortcutKey.Backslash, alt = true, meta = true),
                defaultGlobalShortcut(GlobalShortcutAction.QuickSearch, platform),
            )
        }
    }

    @Test fun `quick search on macOS 26 and earlier or unknown keeps shift command space`() {
        listOf(26, 15, 0).flatMap(::macosPlatforms).forEach { platform ->
            assertEquals(
                macosQuickSearch,
                defaultGlobalShortcut(GlobalShortcutAction.QuickSearch, platform),
            )
        }
    }

    @Test fun `other platforms use control shift space`() {
        listOf(Platform.Desktop.Windows, Platform.Desktop.Linux.native).forEach { platform ->
            assertEquals(
                GlobalShortcutBinding(GlobalShortcutKey.Space, ctrl = true, shift = true),
                defaultGlobalShortcut(GlobalShortcutAction.QuickSearch, platform),
            )
        }
    }

    @Test fun `lock vault default does not depend on the macOS version`() {
        listOf(26, 27, 0).flatMap(::macosPlatforms).forEach { platform ->
            assertEquals(
                GlobalShortcutBinding(GlobalShortcutKey.L, shift = true, meta = true),
                defaultGlobalShortcut(GlobalShortcutAction.LockVault, platform),
            )
        }
    }

    @Test fun `resolve keeps a custom binding whatever the macOS version`() {
        val custom = GlobalShortcutBinding(GlobalShortcutKey.F8, alt = true)
        val config = GlobalShortcutConfig(binding = custom)
        listOf(26, 27, 0).flatMap(::macosPlatforms).forEach { platform ->
            assertEquals(
                custom,
                config.resolve(GlobalShortcutAction.QuickSearch, platform),
            )
        }
    }

    @Test fun `resolve uses the macOS version of the platform`() {
        val config = GlobalShortcutConfig()
        assertEquals(
            macosQuickSearch,
            config.resolve(GlobalShortcutAction.QuickSearch, Platform.Desktop.MacOS.Jvm(majorVersion = 26)),
        )
        assertEquals(
            GlobalShortcutBinding(GlobalShortcutKey.Backslash, alt = true, meta = true),
            config.resolve(GlobalShortcutAction.QuickSearch, Platform.Desktop.MacOS.Jvm(majorVersion = 27)),
        )
    }
}
