package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.feature.home.settings.LocalSettingItemArgs
import com.artemchep.keyguard.feature.home.vault.component.Section
import com.artemchep.keyguard.ui.util.HorizontalDivider
import kotlinx.coroutines.flow.flowOf
import org.kodein.di.DirectDI

fun settingSectionProvider(
    directDI: DirectDI,
) = settingSectionProvider()

fun settingSectionProvider(): SettingComponent = kotlin.run {
    val item = SettingIi {
        val title = run {
            val args = LocalSettingItemArgs.current as? SettingSectionArgs
            args?.title
        }

        if (title != null) {
            Section(
                text = title,
            )
        } else {
            HorizontalDivider(
                modifier = Modifier
                    .padding(vertical = 4.dp),
            )
        }
    }
    flowOf(item)
}

data class SettingSectionArgs(
    val title: String? = null,
)
