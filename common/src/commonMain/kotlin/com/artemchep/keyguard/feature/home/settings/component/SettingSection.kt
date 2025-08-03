package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.feature.home.settings.LocalSettingItemArgs
import com.artemchep.keyguard.feature.home.vault.component.Section
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.ui.theme.LocalExpressive
import com.artemchep.keyguard.ui.util.HorizontalDivider
import kotlinx.coroutines.flow.flowOf
import org.kodein.di.DirectDI

fun settingSectionProvider(
    directDI: DirectDI,
) = settingSectionProvider()

fun settingSectionProvider(): SettingComponent = kotlin.run {
    val item = SettingIi {
        val expressive = LocalExpressive.current
        val title = run {
            val args = LocalSettingItemArgs.current as? SettingSectionArgs
            args?.title
        }

        if (title != null) {
            Section(
                text = textResource(title),
            )
        } else if (expressive) {
            Spacer(
                modifier = Modifier
                    .height(24.dp),
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
    val title: TextHolder? = null,
)
