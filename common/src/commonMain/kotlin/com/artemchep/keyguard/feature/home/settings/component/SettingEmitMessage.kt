package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Message
import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.usecase.ShowMessage
import com.artemchep.keyguard.feature.home.vault.component.VaultViewButtonItem
import com.artemchep.keyguard.ui.icons.icon
import kotlinx.coroutines.flow.flowOf
import org.kodein.di.DirectDI
import org.kodein.di.instance
import java.util.UUID

fun settingEmitMessageProvider(
    directDI: DirectDI,
) = settingEmitMessageProvider(
    showMessage = directDI.instance(),
)

fun settingEmitMessageProvider(
    showMessage: ShowMessage,
): SettingComponent = kotlin.run {
    val item = SettingIi {
        SettingEmitMessage(
            onClick = {
                val type = ToastMessage.Type.entries
                    .toTypedArray()
                    .random()
                val model = ToastMessage(
                    title = "Test message",
                    type = type,
                    text = UUID.randomUUID().toString(),
                )
                showMessage.copy(model)
            },
        )
    }
    flowOf(item)
}

@Composable
fun SettingEmitMessage(
    onClick: () -> Unit,
) {
    VaultViewButtonItem(
        leading = icon<RowScope>(Icons.Outlined.Message),
        text = "Emit message",
        onClick = onClick,
    )
}
