package com.artemchep.keyguard.feature.home.settings.component

import com.artemchep.keyguard.feature.home.settings.LocalSettingItemArgs
import com.artemchep.keyguard.feature.home.vault.component.Section
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.ui.theme.LocalExpressive
import kotlinx.coroutines.flow.flowOf
import org.kodein.di.DirectDI

fun settingSectionProvider(
    directDI: DirectDI,
) = settingSectionProvider()

fun settingSectionProvider(): SettingComponent = kotlin.run {
    val item = SettingIi {
        val text = run {
            val args = LocalSettingItemArgs.current as? SettingSectionArgs
            args?.title
                ?.let { textResource(it) }
        }

        Section(
            text = text,
            expressive = LocalExpressive.current,
        )
    }
    flowOf(item)
}

data class SettingSectionArgs(
    val title: TextHolder? = null,
)
