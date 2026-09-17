package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Message
import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.usecase.ShowMessage
import com.artemchep.keyguard.feature.home.settings.KgAction
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.flowOf
import org.koin.core.scope.Scope

fun settingEmitMessageProvider(
    koinScope: Scope,
) = settingEmitMessageProvider(
    showMessage = koinScope.get(),
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
                    text = Uuid.random().toString(),
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
    LocalSettingPaneComponents.current.KgAction(
        icon = Icons.AutoMirrored.Outlined.Message,
        title = "Emit message",
        onClick = onClick,
    )
}
