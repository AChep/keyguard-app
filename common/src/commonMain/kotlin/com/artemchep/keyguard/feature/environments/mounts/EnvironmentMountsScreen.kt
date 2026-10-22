package com.artemchep.keyguard.feature.environments.mounts

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.common.service.environment.EnvironmentMountStatus
import com.artemchep.keyguard.feature.EmptyView
import com.artemchep.keyguard.feature.home.vault.component.FlatDropdownSimpleExpressive
import com.artemchep.keyguard.feature.home.vault.component.Section
import com.artemchep.keyguard.feature.navigation.NavigationIcon
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.add
import com.artemchep.keyguard.res.environments_mounts
import com.artemchep.keyguard.res.environments_mounts_empty
import com.artemchep.keyguard.res.environments_mount_note
import com.artemchep.keyguard.res.environments_mount_status_error
import com.artemchep.keyguard.res.status_ready
import com.artemchep.keyguard.res.status_starting
import com.artemchep.keyguard.ui.DefaultFab
import com.artemchep.keyguard.ui.FabState
import com.artemchep.keyguard.ui.FlatItemTextContent
import com.artemchep.keyguard.ui.FlatSimpleNote
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.ScaffoldLazyColumn
import com.artemchep.keyguard.ui.icons.IconBox
import com.artemchep.keyguard.ui.icons.icon
import com.artemchep.keyguard.ui.skeleton.skeletonItems
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.toolbar.LargeToolbar
import com.artemchep.keyguard.ui.toolbar.util.ToolbarBehavior
import org.jetbrains.compose.resources.stringResource

@Composable
fun EnvironmentMountsScreen() {
    val loadableState = produceEnvironmentMountsState()
    EnvironmentMountsScreen(
        loadableState = loadableState,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EnvironmentMountsScreen(
    loadableState: Loadable<EnvironmentMountsState>,
) {
    val scrollBehavior = ToolbarBehavior.behavior()
    ScaffoldLazyColumn(
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        expressive = true,
        topAppBarScrollBehavior = scrollBehavior,
        topBar = {
            LargeToolbar(
                title = {
                    Text(stringResource(Res.string.environments_mounts))
                },
                navigationIcon = {
                    NavigationIcon()
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionState = run {
            val onClick = loadableState.getOrNull()?.primaryAction
            val state = FabState(
                onClick = onClick,
                model = null,
            )
            rememberUpdatedState(newValue = state)
        },
        floatingActionButton = {
            DefaultFab(
                icon = {
                    IconBox(main = Icons.Outlined.Add)
                },
                text = {
                    Text(
                        text = stringResource(Res.string.add),
                    )
                },
            )
        },
    ) {
        populateItems(
            loadableState = loadableState,
        )
    }
}

private fun LazyListScope.populateItems(
    loadableState: Loadable<EnvironmentMountsState>,
) {
    if (loadableState !is Loadable.Ok) {
        skeletonItems(
            count = 2,
        )
        return
    }

    val state = loadableState.value
    state.notes.forEachIndexed { index, note ->
        item("note.$index") {
            FlatSimpleNote(
                note = note,
            )
        }
    }
    if (state.notes.isNotEmpty()) {
        item("notes.section") {
            Section(
                expressive = true,
            )
        }
    }
    if (state.items.isEmpty()) {
        item("empty") {
            EmptyView(
                text = {
                    Text(
                        text = stringResource(Res.string.environments_mounts_empty),
                    )
                },
            )
        }
    }
    items(
        items = state.items,
        key = { it.key },
    ) { item ->
        EnvironmentMountItem(
            modifier = Modifier
                .animateItem(),
            item = item,
        )
    }
    item("note") {
        EnvironmentMountsNote()
    }
}

@Composable
private fun EnvironmentMountsNote() {
    Column(
        modifier = Modifier,
    ) {
        Spacer(
            modifier = Modifier
                .height(32.dp),
        )
        Icon(
            modifier = Modifier
                .padding(horizontal = Dimens.textHorizontalPadding),
            imageVector = Icons.Outlined.Info,
            contentDescription = null,
            tint = LocalContentColor.current.combineAlpha(alpha = MediumEmphasisAlpha),
        )
        Spacer(
            modifier = Modifier
                .height(16.dp),
        )
        Text(
            modifier = Modifier
                .padding(horizontal = Dimens.textHorizontalPadding),
            text = stringResource(Res.string.environments_mount_note),
            style = MaterialTheme.typography.bodyMedium,
            color = LocalContentColor.current.combineAlpha(alpha = MediumEmphasisAlpha),
        )
    }
}

@Composable
private fun EnvironmentMountItem(
    modifier: Modifier,
    item: EnvironmentMountsState.Item,
) {
    FlatDropdownSimpleExpressive(
        modifier = modifier,
        shapeState = item.shapeState,
        leading = icon<RowScope>(Icons.Outlined.Description),
        content = {
            FlatItemTextContent(
                title = {
                    Text(
                        text = item.path,
                        fontFamily = FontFamily.Monospace,
                    )
                },
                text = {
                    Column {
                        item.environmentName?.let { name ->
                            Text(text = name)
                        }
                        Text(
                            text = stringResource(item.status.titleH()),
                            color = if (item.status == EnvironmentMountStatus.Error) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                },
            )
        },
        dropdown = item.dropdown,
    )
}

/** The delivery status of a local .env file. */
internal fun EnvironmentMountStatus.titleH() = when (this) {
    EnvironmentMountStatus.Pending -> Res.string.status_starting
    EnvironmentMountStatus.Ready -> Res.string.status_ready
    EnvironmentMountStatus.Error -> Res.string.environments_mount_status_error
}
