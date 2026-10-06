package com.artemchep.keyguard.feature.remotepicker

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import arrow.core.Either
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.ShapeState
import com.artemchep.keyguard.common.model.fold
import com.artemchep.keyguard.common.model.getShapeState
import com.artemchep.keyguard.feature.EmptyView
import com.artemchep.keyguard.feature.ErrorView
import com.artemchep.keyguard.feature.auth.common.TextFieldModel
import com.artemchep.keyguard.feature.filepicker.humanReadableByteCountSI
import com.artemchep.keyguard.feature.home.vault.component.FlatItemSimpleExpressive
import com.artemchep.keyguard.feature.navigation.NavigationIcon
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.create_database
import com.artemchep.keyguard.res.database_name
import com.artemchep.keyguard.res.retry
import com.artemchep.keyguard.res.select_file
import com.artemchep.keyguard.res.webdav_picker_choose_folder
import com.artemchep.keyguard.res.webdav_picker_empty
import com.artemchep.keyguard.res.webdav_picker_error
import com.artemchep.keyguard.res.webdav_picker_filename_exists
import com.artemchep.keyguard.res.webdav_picker_filename_extension
import com.artemchep.keyguard.res.webdav_picker_filename_invalid
import com.artemchep.keyguard.res.webdav_picker_filename_required
import com.artemchep.keyguard.res.webdav_picker_refresh
import com.artemchep.keyguard.ui.DefaultFab
import com.artemchep.keyguard.ui.FabScope
import com.artemchep.keyguard.ui.FabState
import com.artemchep.keyguard.ui.FlatTextField
import com.artemchep.keyguard.ui.ScaffoldLazyColumn
import com.artemchep.keyguard.ui.skeleton.skeletonItems
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.toolbar.LargeToolbar
import com.artemchep.keyguard.ui.toolbar.util.ToolbarBehavior
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RemotePickerContent(
    state: RemotePickerState,
    mode: RemotePickerMode,
    title: String,
) {
    val scrollBehavior = ToolbarBehavior.behavior()
    val fabState = when (mode) {
        RemotePickerMode.OpenKeePassDatabase -> null
        else -> FabState(
            onClick = state.onConfirm,
            model = null,
        )
    }
    ScaffoldLazyColumn(
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        expressive = true,
        topAppBarScrollBehavior = scrollBehavior,
        floatingActionState = rememberUpdatedState(fabState),
        floatingActionButton = {
            RemotePickerFab(mode)
        },
        topBar = {
            LargeToolbar(
                title = {
                    Text(
                        text = title,
                    )
                },
                navigationIcon = {
                    NavigationIcon()
                },
                actions = {
                    IconButton(
                        onClick = state.onRefresh,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = stringResource(
                                Res.string.webdav_picker_refresh,
                            ),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) {
        remotePickerBody(state)
    }
}

@Composable
private fun FabScope.RemotePickerFab(
    mode: RemotePickerMode,
) {
    DefaultFab(
        icon = {
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = null,
            )
        },
        text = {
            Text(
                text = stringResource(
                    when (mode) {
                        RemotePickerMode.SelectFolder ->
                            Res.string.webdav_picker_choose_folder

                        RemotePickerMode.CreateKeePassDatabase ->
                            Res.string.create_database

                        RemotePickerMode.OpenKeePassDatabase ->
                            Res.string.select_file
                    },
                ),
            )
        },
    )
}

private fun LazyListScope.remotePickerBody(
    state: RemotePickerState,
) {
    item("breadcrumbs") {
        RemotePickerBreadcrumbs(
            breadcrumbs = state.breadcrumbs,
        )
    }
    remotePickerFileName(state)
    remotePickerDirectoryContent(
        content = state.content,
        onRefresh = state.onRefresh,
    )
    item("bottom.spacer") {
        Spacer(
            modifier = Modifier
                .height(80.dp),
        )
    }
}

private fun LazyListScope.remotePickerFileName(
    state: RemotePickerState,
) {
    val fileNameState = state.fileName
        ?: return
    item("filename") {
        val error = when (state.fileNameError) {
            RemotePickerState.FileNameError.Required ->
                stringResource(Res.string.webdav_picker_filename_required)

            RemotePickerState.FileNameError.Invalid ->
                stringResource(Res.string.webdav_picker_filename_invalid)

            RemotePickerState.FileNameError.ExtensionRequired ->
                stringResource(
                    Res.string.webdav_picker_filename_extension,
                    KEEPASS_DATABASE_EXTENSION,
                )

            RemotePickerState.FileNameError.AlreadyExists ->
                stringResource(Res.string.webdav_picker_filename_exists)

            null -> null
        }
        FlatTextField(
            modifier = Modifier
                .padding(horizontal = Dimens.fieldHorizontalPadding),
            label = stringResource(Res.string.database_name),
            value = TextFieldModel(
                text = fileNameState.value,
                error = error,
                onChange = fileNameState::value::set,
            ),
            shapeState = ShapeState.ALL,
            singleLine = true,
            clearButton = true,
        )
    }
    item("filename.spacer") {
        Spacer(
            modifier = Modifier
                .height(8.dp),
        )
    }
}

private fun LazyListScope.remotePickerDirectoryContent(
    content: Loadable<Either<Throwable, List<RemotePickerState.Item>>>,
    onRefresh: () -> Unit,
) {
    content.fold(
        ifLoading = {
            skeletonItems()
        },
        ifOk = { result ->
            result.fold(
                ifLeft = { exception ->
                    item("error") {
                        RemotePickerError(
                            exception = exception,
                            onRetry = onRefresh,
                        )
                    }
                },
                ifRight = { items ->
                    remotePickerDirectoryItems(items)
                },
            )
        },
    )
}

private fun LazyListScope.remotePickerDirectoryItems(
    items: List<RemotePickerState.Item>,
) {
    if (items.isEmpty()) {
        item("empty") {
            EmptyView(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 32.dp),
                text = {
                    Text(
                        text = stringResource(Res.string.webdav_picker_empty),
                    )
                },
            )
        }
    }
    items(
        count = items.size,
        key = { index -> items[index].key },
    ) { index ->
        val item = items[index]
        RemotePickerItem(
            item = item,
            shapeState = getShapeState(items, index) { _, _ -> true },
        )
    }
}

@Composable
private fun RemotePickerBreadcrumbs(
    breadcrumbs: List<RemotePickerState.Breadcrumb>,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Dimens.contentPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        breadcrumbs.forEachIndexed { index, breadcrumb ->
            if (index > 0) {
                Icon(
                    modifier = Modifier
                        .size(18.dp),
                    imageVector = Icons.Outlined.ChevronRight,
                    contentDescription = null,
                )
            }
            TextButton(
                onClick = breadcrumb.onClick ?: NoOpOnClick,
                enabled = breadcrumb.onClick != null,
            ) {
                Text(
                    text = breadcrumb.name,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun RemotePickerItem(
    item: RemotePickerState.Item,
    shapeState: Int,
) {
    val enabled = item.onClick != null
    FlatItemSimpleExpressive(
        shapeState = shapeState,
        title = {
            Text(
                text = item.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = item.size
            ?.takeUnless { item.isFolder }
            ?.let { size ->
                {
                    Text(
                        text = humanReadableByteCountSI(size),
                    )
                }
            },
        leading = {
            Icon(
                imageVector = if (item.isFolder) {
                    Icons.Outlined.Folder
                } else {
                    Icons.Outlined.Description
                },
                contentDescription = null,
            )
        },
        onClick = item.onClick,
        enabled = enabled,
    )
}

@Composable
private fun RemotePickerError(
    exception: Throwable,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ErrorView(
            text = {
                Text(
                    text = exception.message
                        ?: stringResource(Res.string.webdav_picker_error),
                )
            },
        )
        Button(
            onClick = onRetry,
        ) {
            Text(
                text = stringResource(Res.string.retry),
            )
        }
    }
}

private val NoOpOnClick: () -> Unit = {}
