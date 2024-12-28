package com.artemchep.keyguard.feature.confirmation.organization

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.feature.confirmation.folder.CreateNewFolder
import com.artemchep.keyguard.feature.dialog.Dialog
import com.artemchep.keyguard.feature.home.vault.component.Section
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.feature.search.filter.component.FilterItemComposable
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.ExpandedIfNotEmpty
import com.artemchep.keyguard.ui.FlatSimpleNote
import com.artemchep.keyguard.ui.icons.icon
import com.artemchep.keyguard.ui.theme.Dimens
import org.jetbrains.compose.resources.stringResource

@Composable
fun OrganizationConfirmationScreen(
    args: OrganizationConfirmationRoute.Args,
    transmitter: RouteResultTransmitter<OrganizationConfirmationResult>,
) {
    val state = organizationConfirmationState(
        args = args,
        transmitter = transmitter,
    )
    OrganizationConfirmationScreen(
        decor = args.decor,
        state = state,
    )
}

@Composable
private fun OrganizationConfirmationScreen(
    decor: OrganizationConfirmationRoute.Args.Decor,
    state: OrganizationConfirmationState,
) {
    Dialog(
        icon = decor.icon?.let { icon(it) },
        title = {
            Text(decor.title)
        },
        content = {
            val data = state.content.getOrNull()
            Column {
                ExpandedIfNotEmpty(
                    valueOrNull = decor.note,
                ) { note ->
                    FlatSimpleNote(
                        modifier = Modifier,
                        note = note,
                    )
                }

                val accountsOrNull = state.content.getOrNull()?.accounts
                FolderSection(
                    title = stringResource(Res.string.accounts),
                    section = accountsOrNull,
                )
                val organizationsOrNull = state.content.getOrNull()?.organizations
                FolderSection(
                    title = stringResource(Res.string.organizations),
                    section = organizationsOrNull,
                )
                val collectionsOrNull = state.content.getOrNull()?.collections
                FolderSection(
                    title = stringResource(Res.string.collections),
                    section = collectionsOrNull,
                )
                val foldersOrNull = state.content.getOrNull()?.folders
                FolderSection(
                    title = stringResource(Res.string.folders),
                    section = foldersOrNull,
                )

                val field = state.content.getOrNull()?.folderNew
                CreateNewFolder(
                    field = field,
                )
            }

            if (state.content is Loadable.Loading && data == null) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(16.dp),
                )
            }
        },
        actions = {
            val updatedOnDeny by rememberUpdatedState(state.onDeny)
            val updatedOnConfirm by rememberUpdatedState(state.onConfirm)
            TextButton(
                enabled = state.onDeny != null,
                onClick = {
                    updatedOnDeny?.invoke()
                },
            ) {
                Text(stringResource(Res.string.close))
            }
            TextButton(
                enabled = state.onConfirm != null,
                onClick = {
                    updatedOnConfirm?.invoke()
                },
            ) {
                Text(stringResource(Res.string.ok))
            }
        },
    )
}

@Composable
private fun ColumnScope.Note(
    modifier: Modifier = Modifier,
    title: String,
    section: OrganizationConfirmationState.Content.Section?,
) {
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.FolderSection(
    modifier: Modifier = Modifier,
    title: String,
    section: OrganizationConfirmationState.Content.Section?,
) {
    ExpandedIfNotEmpty(
        valueOrNull = section?.takeUnless { it.items.isEmpty() },
    ) { l ->
        Column {
            Section(text = title)
            FlowRow(
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .animateContentSize(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                l.items.forEach { item ->
                    key(item.key) {
                        FilterItemComposable(
                            modifier = Modifier,
                            checked = item.selected,
                            leading = item.icon,
                            title = item.title,
                            text = item.text,
                            onClick = item.onClick,
                        )
                    }
                }
            }
            ExpandedIfNotEmpty(
                valueOrNull = l.text,
            ) { text ->
                Text(
                    modifier = Modifier
                        .padding(horizontal = Dimens.horizontalPadding)
                        .padding(top = 8.dp),
                    text = text,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}
