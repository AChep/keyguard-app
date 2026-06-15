package com.artemchep.keyguard.feature.sshagent.filter

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.fold
import com.artemchep.keyguard.feature.navigation.NavigationIcon
import com.artemchep.keyguard.feature.search.filter.FilterItems
import com.artemchep.keyguard.feature.search.filter.component.VaultHomeScreenFilterPaneNumberOfItems
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.DefaultFab
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha
import com.artemchep.keyguard.ui.FabState
import com.artemchep.keyguard.ui.ScaffoldColumn
import com.artemchep.keyguard.ui.icons.IconBox
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.toolbar.LargeToolbar
import com.artemchep.keyguard.ui.toolbar.util.ToolbarBehavior
import org.jetbrains.compose.resources.stringResource

@Composable
fun SshAgentFiltersScreen() {
    val loadableState = produceSshAgentFiltersState()
    loadableState.fold(
        ifLoading = {
            SshAgentFiltersScreenSkeleton()
        },
        ifOk = { state ->
            SshAgentFiltersScreenContent(state = state)
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SshAgentFiltersScreenSkeleton() {
    val scrollBehavior = ToolbarBehavior.behavior()
    ScaffoldColumn(
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        expressive = true,
        topAppBarScrollBehavior = scrollBehavior,
        topBar = {
            LargeToolbar(
                title = {
                    Text(stringResource(Res.string.ssh_agent_filters_header_title))
                },
                navigationIcon = {
                    NavigationIcon()
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) {
        // Empty
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SshAgentFiltersScreenContent(
    state: SshAgentFiltersState,
) {
    val scrollBehavior = ToolbarBehavior.behavior()

    val fabState = FabState(
        onClick = state.onSave,
        model = null,
    )
    val resetOnClick by rememberUpdatedState(state.onReset)
    val resetEnabled = resetOnClick != null

    val resetContainerColor = run {
        val color = MaterialTheme.colorScheme.secondaryContainer
        if (resetEnabled) {
            color
        } else {
            color
                .combineAlpha(DisabledEmphasisAlpha)
        }
    }
    val resetContentColor = run {
        val color = MaterialTheme.colorScheme.onSecondaryContainer
        if (resetEnabled) {
            color
        } else {
            color
                .combineAlpha(DisabledEmphasisAlpha)
        }
    }

    ScaffoldColumn(
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        expressive = true,
        topAppBarScrollBehavior = scrollBehavior,
        topBar = {
            LargeToolbar(
                title = {
                    Text(stringResource(Res.string.ssh_agent_filters_header_title))
                },
                navigationIcon = {
                    NavigationIcon()
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionState = run {
            val updated = rememberUpdatedState(newValue = fabState)
            updated
        },
        floatingActionButton = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SmallFloatingActionButton(
                    containerColor = resetContainerColor,
                    contentColor = resetContentColor,
                    onClick = {
                        resetOnClick?.invoke()
                    },
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Clear,
                        contentDescription = null,
                    )
                }
                DefaultFab(
                    icon = {
                        IconBox(main = Icons.Outlined.Save)
                    },
                    text = {
                        Text(
                            text = stringResource(Res.string.save),
                        )
                    },
                )
            }
        },
    ) {
        Text(
            modifier = Modifier
                .padding(
                    horizontal = Dimens.contentPadding,
                    vertical = 8.dp,
                ),
            text = stringResource(Res.string.ssh_agent_filters_note_save_to_apply),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        VaultHomeScreenFilterPaneNumberOfItems(
            count = state.count,
        )

        FilterItems(
            items = state.filters,
        )
    }
}

