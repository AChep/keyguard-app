package com.artemchep.keyguard.wear.feature.privilegedapp

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.transformedHeight
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.privilegedapp.PrivilegedAppItemContent
import com.artemchep.keyguard.feature.privilegedapp.PrivilegedAppItemRenderers
import com.artemchep.keyguard.feature.privilegedapp.PrivilegedAppListContentState
import com.artemchep.keyguard.feature.privilegedapp.PrivilegedAppListState
import com.artemchep.keyguard.feature.privilegedapp.producePrivilegedAppListState
import com.artemchep.keyguard.feature.privilegedapp.PrivilegedAppSelectionItem
import com.artemchep.keyguard.feature.privilegedapp.toPrivilegedAppListContentState
import com.artemchep.keyguard.feature.privilegedapp.toPrivilegedAppSelectionItems
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.cancel
import com.artemchep.keyguard.res.privilegedapps_empty_label
import com.artemchep.keyguard.res.privilegedapps_list_header_title
import com.artemchep.keyguard.res.selection_n_selected
import com.artemchep.keyguard.wear.feature.picker.WearPickerRoute
import com.artemchep.keyguard.wear.ui.WearContextAction
import com.artemchep.keyguard.wear.ui.WearListAction
import com.artemchep.keyguard.wear.ui.WearListEmpty
import com.artemchep.keyguard.wear.ui.WearListLabel
import com.artemchep.keyguard.wear.ui.WearScaffoldScreen
import com.artemchep.keyguard.wear.ui.WearSectionHeader
import com.artemchep.keyguard.wear.ui.skeletonItems
import org.jetbrains.compose.resources.stringResource

@Composable
fun WearPrivilegedAppListScreen() {
    val loadableState = producePrivilegedAppListState()
    WearPrivilegedAppListScreen(
        loadableState = loadableState,
    )
}

@Composable
fun WearPrivilegedAppListScreen(
    loadableState: Loadable<PrivilegedAppListState>,
) {
    val navigationController by rememberUpdatedState(LocalNavigationController.current)
    val contentState = loadableState.toPrivilegedAppListContentState()

    WearScaffoldScreen(
        title = stringResource(Res.string.privilegedapps_list_header_title),
    ) { transformationSpec ->
        when (contentState) {
            is PrivilegedAppListContentState.Loading -> {
                skeletonItems(
                    transformationSpec = transformationSpec,
                    count = 12,
                )
            }

            is PrivilegedAppListContentState.Error -> {
                item("error") {
                    WearListLabel(
                        modifier = Modifier
                            .transformedHeight(this, transformationSpec),
                        text = "Failed to load privileged app list!",
                        error = true,
                        transformation = SurfaceTransformation(transformationSpec),
                    )
                }
            }

            is PrivilegedAppListContentState.Content -> {
                val content = contentState.content
                if (content.items.isEmpty()) {
                    item("empty") {
                        WearListEmpty(
                            modifier = Modifier
                                .transformedHeight(this, transformationSpec),
                            text = stringResource(Res.string.privilegedapps_empty_label),
                            transformation = SurfaceTransformation(transformationSpec),
                        )
                    }
                }

                items(
                    items = content.items,
                    key = { item -> item.key },
                ) { item ->
                    PrivilegedAppItemContent(
                        modifier = Modifier
                            .fillMaxWidth()
                            .transformedHeight(this, transformationSpec),
                        item = item,
                        renderers = wearPrivilegedAppItemRenderers(
                            navigationController = navigationController,
                            transformation = SurfaceTransformation(transformationSpec),
                        ),
                    )
                }

                content.selection
                    ?.toPrivilegedAppSelectionItems()
                    .orEmpty()
                    .forEach { selectionItem ->
                        item(selectionItem.key) {
                            WearPrivilegedAppSelectionItem(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .transformedHeight(this, transformationSpec),
                                item = selectionItem,
                                transformation = SurfaceTransformation(transformationSpec),
                            )
                        }
                    }
            }
        }
    }
}

private fun wearPrivilegedAppItemRenderers(
    navigationController: com.artemchep.keyguard.feature.navigation.NavigationController,
    transformation: SurfaceTransformation?,
) = PrivilegedAppItemRenderers(
    section = { modifier, item ->
        WearSectionHeader(
            modifier = modifier,
            title = item.name,
            transformation = transformation,
        )
    },
    content = { modifier, item ->
        WearPrivilegedAppItem(
            modifier = modifier,
            item = item,
            onOpenActions = {
                val route = WearPickerRoute(actions = item.dropdown)
                navigationController.queue(
                    NavigationIntent.NavigateToRoute(route = route),
                )
            },
            transformation = transformation,
        )
    },
)

@Composable
private fun WearPrivilegedAppSelectionItem(
    modifier: Modifier = Modifier,
    item: PrivilegedAppSelectionItem,
    transformation: SurfaceTransformation? = null,
) {
    when (item) {
        is PrivilegedAppSelectionItem.Label -> {
            WearListLabel(
                modifier = modifier,
                text = stringResource(Res.string.selection_n_selected, item.count),
                transformation = transformation,
            )
        }

        is PrivilegedAppSelectionItem.SelectAll -> {
            WearListAction(
                modifier = modifier,
                title = "Select all",
                onClick = item.onClick,
                transformation = transformation,
            )
        }

        is PrivilegedAppSelectionItem.Action -> {
            WearContextAction(
                modifier = modifier,
                item = item.action,
                transformation = transformation,
            )
        }

        is PrivilegedAppSelectionItem.Clear -> {
            WearListAction(
                modifier = modifier,
                title = stringResource(Res.string.cancel),
                onClick = item.onClick,
                transformation = transformation,
            )
        }
    }
}

@Composable
private fun WearPrivilegedAppItem(
    modifier: Modifier = Modifier,
    item: PrivilegedAppListState.Item.Content,
    onOpenActions: () -> Unit,
    transformation: SurfaceTransformation? = null,
) {
    val selectableState by item.selectableState.collectAsStateWithLifecycle()
    val onClick = selectableState.onClick
        ?: onOpenActions.takeIf { item.dropdown.isNotEmpty() }
    val onLongClick = selectableState.onLongClick

    FilledTonalButton(
        modifier = modifier,
        onClick = {
            onClick?.invoke()
        },
        onLongClick = onLongClick,
        enabled = onClick != null || onLongClick != null,
        label = {
            Text(
                text = item.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        secondaryLabel = {
            item.cert
                .takeIf { it.isNotBlank() }
                ?.let { cert ->
                    Text(
                        text = cert,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
        },
        transformation = transformation,
    )
}
