package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.service.agent.AgentApprovalBroker
import com.artemchep.keyguard.common.service.agent.AgentApprovalSamples
import com.artemchep.keyguard.common.usecase.ShowMessage
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.agent.sample.ApprovalPromptSample
import com.artemchep.keyguard.feature.home.settings.KgPicker
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.platform.util.isRelease
import com.artemchep.keyguard.ui.ContextItem
import com.artemchep.keyguard.ui.FlatItemAction
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.koin.core.scope.Scope

actual fun settingApprovalPromptSamplesProvider(
    koinScope: Scope,
): SettingComponent = if (!isRelease) {
    settingApprovalPromptSamplesProvider(
        samples = koinScope.get(),
        showMessage = koinScope.get(),
        windowCoroutineScope = koinScope.get(),
    )
} else {
    flowOf(null)
}

fun settingApprovalPromptSamplesProvider(
    samples: AgentApprovalSamples,
    showMessage: ShowMessage,
    windowCoroutineScope: WindowCoroutineScope,
): SettingComponent = kotlin.run {
    val dropdown = ApprovalPromptSample.entries.map { sample ->
        FlatItemAction(
            id = "settings.approvalPromptSamples.${sample.name}",
            title = TextHolder.Value(sample.title),
            onClick = {
                windowCoroutineScope.launch {
                    // Shown through the real approval window; no agent waits for it.
                    val result = samples.show(sample::build)
                    val message = ToastMessage(
                        type = if (result == AgentApprovalBroker.Result.Approved) {
                            ToastMessage.Type.SUCCESS
                        } else {
                            ToastMessage.Type.INFO
                        },
                        title = "Sample ${result.name.lowercase()}",
                        text = sample.title,
                    )
                    showMessage.copy(message)
                }
            },
        )
    }
    val item = SettingIi {
        SettingApprovalPromptSamples(
            dropdown = dropdown,
        )
    }
    flowOf(item)
}

@Composable
private fun SettingApprovalPromptSamples(
    dropdown: List<ContextItem>,
) {
    LocalSettingPaneComponents.current.KgPicker(
        icon = Icons.Outlined.VerifiedUser,
        title = "Show approval prompt",
        dropdown = dropdown,
    )
}
