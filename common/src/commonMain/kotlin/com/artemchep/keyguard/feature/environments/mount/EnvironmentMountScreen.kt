package com.artemchep.keyguard.feature.environments.mount

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.feature.agent.help.AgentSetupCodeBlock
import com.artemchep.keyguard.feature.dialog.Dialog
import com.artemchep.keyguard.feature.filepicker.FilePickerEffect
import com.artemchep.keyguard.feature.home.vault.component.FlatDropdownSimpleExpressive
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.cancel
import com.artemchep.keyguard.res.choose_folder
import com.artemchep.keyguard.res.cipher_type_environment
import com.artemchep.keyguard.res.environments_flatpak_note
import com.artemchep.keyguard.res.environments_mount_create
import com.artemchep.keyguard.res.environments_mount_path
import com.artemchep.keyguard.ui.FlatItemTextContent
import com.artemchep.keyguard.ui.FlatSimpleNote
import com.artemchep.keyguard.ui.FlatTextField
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.SimpleNote
import com.artemchep.keyguard.ui.icons.icon
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import org.jetbrains.compose.resources.stringResource

@Composable
fun EnvironmentMountScreen(
    environmentId: String?,
) {
    val loadableState = produceEnvironmentMountState(
        environmentId = environmentId,
    )
    loadableState.getOrNull()?.let { state ->
        FilePickerEffect(
            flow = state.filePickerIntentFlow,
        )
    }
    EnvironmentMountScreen(
        loadableState = loadableState,
    )
}

@Composable
private fun EnvironmentMountScreen(
    loadableState: Loadable<EnvironmentMountState>,
) {
    val updatedNavigationController by rememberUpdatedState(LocalNavigationController.current)
    val state = loadableState.getOrNull()
    Dialog(
        icon = icon(Icons.Outlined.Description),
        title = {
            Text(stringResource(Res.string.environments_mount_create))
        },
        content = {
            if (state != null) {
                EnvironmentMountContent(
                    state = state,
                )
            }
        },
        actions = {
            TextButton(
                onClick = {
                    updatedNavigationController.queue(NavigationIntent.Pop)
                },
            ) {
                Text(stringResource(Res.string.cancel))
            }
            TextButton(
                enabled = state?.onCreate != null,
                onClick = {
                    state?.onCreate?.invoke()
                },
            ) {
                Text(stringResource(Res.string.environments_mount_create))
            }
        },
    )
}

@Composable
private fun EnvironmentMountContent(
    state: EnvironmentMountState,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth(),
    ) {
        val environment = state.environment
        if (environment != null) {
            EnvironmentMountEnvironmentItem(
                environment = environment,
            )
            Spacer(
                modifier = Modifier
                    .height(8.dp),
            )
        }
        EnvironmentMountPathField(
            state = state,
        )
        val flatpakCommand = state.flatpakCommand
        if (flatpakCommand != null) {
            Spacer(
                modifier = Modifier
                    .height(16.dp),
            )
            EnvironmentMountFlatpakHelp(
                command = flatpakCommand,
            )
        }
        val error = state.error
        if (error != null) {
            Spacer(
                modifier = Modifier
                    .height(8.dp),
            )
            FlatSimpleNote(
                type = SimpleNote.Type.ERROR,
                text = error,
            )
        }
    }
}

@Composable
private fun EnvironmentMountEnvironmentItem(
    environment: EnvironmentMountState.Environment,
) {
    FlatDropdownSimpleExpressive(
        leading = icon<RowScope>(Icons.Outlined.Code),
        content = {
            FlatItemTextContent(
                title = {
                    Text(
                        text = environment.name,
                    )
                },
                text = {
                    Text(
                        text = stringResource(Res.string.cipher_type_environment),
                    )
                },
            )
        },
        dropdown = environment.dropdown,
    )
}

@Composable
private fun EnvironmentMountPathField(
    state: EnvironmentMountState,
) {
    FlatTextField(
        modifier = Modifier
            .padding(horizontal = Dimens.horizontalPadding),
        label = stringResource(Res.string.environments_mount_path),
        value = state.path,
        textStyle = MaterialTheme.typography.bodyLarge.copy(
            fontFamily = FontFamily.Monospace,
        ),
        singleLine = true,
        trailing = state.onChooseFolder?.let { onChooseFolder ->
            // composable
            {
                IconButton(
                    onClick = onChooseFolder,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.FolderOpen,
                        contentDescription = stringResource(Res.string.choose_folder),
                    )
                }
            }
        },
    )
}

@Composable
private fun EnvironmentMountFlatpakHelp(
    command: String,
) {
    Text(
        modifier = Modifier
            .padding(horizontal = Dimens.horizontalPadding),
        text = stringResource(Res.string.environments_flatpak_note),
        style = MaterialTheme.typography.bodyMedium,
        color = LocalContentColor.current
            .combineAlpha(MediumEmphasisAlpha),
    )
    AgentSetupCodeBlock(
        text = command,
    )
}
