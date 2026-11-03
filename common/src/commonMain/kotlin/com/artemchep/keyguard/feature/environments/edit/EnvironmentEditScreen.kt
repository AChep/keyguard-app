package com.artemchep.keyguard.feature.environments.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.ShapeState
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.feature.ErrorView
import com.artemchep.keyguard.feature.add.AddStateOwnership
import com.artemchep.keyguard.feature.add.ToolbarContent
import com.artemchep.keyguard.feature.auth.common.TextFieldModel
import com.artemchep.keyguard.feature.filepicker.FileDropOverlay
import com.artemchep.keyguard.feature.filepicker.fileDropTarget
import com.artemchep.keyguard.feature.home.vault.component.Section
import com.artemchep.keyguard.feature.navigation.NavigationIcon
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.description
import com.artemchep.keyguard.res.environments_name
import com.artemchep.keyguard.res.environments_variables
import com.artemchep.keyguard.res.error_failed_unknown
import com.artemchep.keyguard.res.save
import com.artemchep.keyguard.ui.DefaultFab
import com.artemchep.keyguard.ui.DefaultProgressBar
import com.artemchep.keyguard.ui.FabState
import com.artemchep.keyguard.ui.FlatSimpleNote
import com.artemchep.keyguard.ui.FlatTextField
import com.artemchep.keyguard.ui.OptionsButton
import com.artemchep.keyguard.ui.ScaffoldLazyColumn
import com.artemchep.keyguard.ui.focus.FocusRequester2
import com.artemchep.keyguard.ui.skeleton.SkeletonText
import com.artemchep.keyguard.ui.skeleton.skeletonItems
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.toolbar.LargeToolbar
import com.artemchep.keyguard.ui.toolbar.util.ToolbarBehavior
import org.jetbrains.compose.resources.stringResource

@Composable
fun EnvironmentEditScreen(
    environmentId: String?,
) {
    val loadableState = produceEnvironmentEditState(
        environmentId = environmentId,
    )
    EnvironmentEditScreen(
        loadableState = loadableState,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EnvironmentEditScreen(
    loadableState: Loadable<EnvironmentEditState>,
) {
    val scrollBehavior = ToolbarBehavior.behavior()
    val state = loadableState.getOrNull()
    val fileDrop = state?.fileDrop
    var fileDragActive by remember(fileDrop != null) {
        mutableStateOf(false)
    }
    ScaffoldLazyColumn(
        modifier = Modifier
            .environmentFileDropTarget(
                fileDrop = fileDrop,
                onDragActiveChange = { isActive ->
                    fileDragActive = isActive
                },
            )
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        expressive = true,
        topAppBarScrollBehavior = scrollBehavior,
        topBar = {
            EnvironmentEditToolbar(
                state = state,
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionState = rememberUpdatedState(
            newValue = state?.onSave?.let { onSave ->
                FabState(
                    onClick = onSave.takeUnless { state.busy },
                    model = null,
                )
            },
        ),
        floatingActionButton = {
            DefaultFab(
                icon = {
                    Icon(
                        imageVector = Icons.Outlined.Save,
                        contentDescription = null,
                    )
                },
                text = {
                    Text(stringResource(Res.string.save))
                },
            )
        },
        overlay = {
            DefaultProgressBar(
                visible = state?.busy == true,
            )
        },
        listVerticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        populateItems(
            loadableState = loadableState,
            fileDrop = fileDrop?.takeIf { fileDragActive },
        )
    }
}

private const val TITLE_SKELETON_WIDTH_FRACTION = 0.4f

@Composable
private fun Modifier.environmentFileDropTarget(
    fileDrop: EnvironmentEditState.FileDrop?,
    onDragActiveChange: (Boolean) -> Unit,
): Modifier {
    val fileDropState = rememberUpdatedState(fileDrop)
    return fileDropTarget(
        enabled = fileDrop != null,
        onDragActiveChange = onDragActiveChange,
        onFileDrop = { info ->
            fileDropState.value?.onFileDrop?.invoke(info)
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EnvironmentEditToolbar(
    state: EnvironmentEditState?,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    LargeToolbar(
        title = {
            EnvironmentEditTitle(
                title = state?.title,
            )
        },
        navigationIcon = {
            NavigationIcon()
        },
        actions = {
            OptionsButton(
                actions = state?.actions.orEmpty(),
            )
        },
        scrollBehavior = scrollBehavior,
    )
}

@Composable
private fun EnvironmentEditTitle(
    title: String?,
) {
    if (title != null) {
        Text(title)
    } else {
        SkeletonText(
            modifier = Modifier
                .fillMaxWidth(TITLE_SKELETON_WIDTH_FRACTION),
        )
    }
}

private fun LazyListScope.populateItems(
    loadableState: Loadable<EnvironmentEditState>,
    fileDrop: EnvironmentEditState.FileDrop?,
) {
    if (loadableState !is Loadable.Ok) {
        skeletonItems(
            count = 3,
        )
        return
    }

    val content = loadableState.value.content
    if (content == null) {
        item("error") {
            ErrorView(
                text = {
                    Text(
                        text = stringResource(Res.string.error_failed_unknown),
                    )
                },
            )
        }
        return
    }
    populateContent(
        content = content,
        fileDrop = fileDrop,
    )
}

private fun LazyListScope.populateContent(
    content: EnvironmentEditState.Content,
    fileDrop: EnvironmentEditState.FileDrop?,
) {
    item("ownership") {
        EnvironmentOwnershipItem(
            ownership = content.ownership,
        )
    }
    item("ownership.section") {
        Section()
    }
    if (content.note != null) {
        item("note") {
            FlatSimpleNote(
                note = content.note,
            )
        }
    }
    item("name") {
        EnvironmentNameItem(
            name = content.name,
            requestFocus = content.create,
        )
    }
    item("description") {
        FlatTextField(
            modifier = Modifier
                .padding(horizontal = Dimens.fieldHorizontalPadding),
            label = stringResource(Res.string.description),
            value = content.description,
            shapeState = ShapeState.END,
        )
    }
    item("variables.section") {
        Section(
            text = stringResource(Res.string.environments_variables),
            expressive = true,
        )
    }
    if (fileDrop != null) {
        item("variables.drop") {
            FileDropOverlay(
                modifier = Modifier
                    .padding(horizontal = Dimens.buttonHorizontalPadding)
                    .fillMaxWidth(),
                text = fileDrop.text,
            )
        }
    }
    content.variables.forEach { variable ->
        item(variable.key) {
            EnvironmentVariableItem(
                variable = variable,
            )
        }
    }
    item("variables.add") {
        EnvironmentAddVariableItem(
            actions = content.addActions,
        )
    }
}

@Composable
private fun EnvironmentOwnershipItem(
    ownership: AddStateOwnership,
) {
    ToolbarContent(
        account = ownership.account,
        organization = ownership.organization,
        collection = ownership.collection,
        folder = ownership.folder,
        onClick = ownership.onClick,
    )
}

@Composable
private fun EnvironmentNameItem(
    name: TextFieldModel,
    requestFocus: Boolean,
) {
    val focusRequester = remember {
        FocusRequester2()
    }
    if (requestFocus) {
        LaunchedEffect(focusRequester) {
            focusRequester.requestFocus()
        }
    }
    FlatTextField(
        modifier = Modifier
            .padding(horizontal = Dimens.fieldHorizontalPadding),
        focusRequester = focusRequester,
        label = stringResource(Res.string.environments_name),
        value = name,
        shapeState = ShapeState.START,
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            imeAction = ImeAction.Next,
        ),
    )
}
