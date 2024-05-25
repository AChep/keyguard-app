package com.artemchep.keyguard.feature.send.add

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.fold
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.feature.add.AddScreenItems
import com.artemchep.keyguard.feature.add.AddScreenScope
import com.artemchep.keyguard.feature.add.AnyField
import com.artemchep.keyguard.feature.add.ToolbarContent
import com.artemchep.keyguard.feature.add.ToolbarContentItemErrSkeleton
import com.artemchep.keyguard.feature.home.vault.add.AddState
import com.artemchep.keyguard.feature.home.vault.component.Section
import com.artemchep.keyguard.feature.navigation.NavigationIcon
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.DefaultFab
import com.artemchep.keyguard.ui.ExpandedIfNotEmpty
import com.artemchep.keyguard.ui.FabState
import com.artemchep.keyguard.ui.FlatItemLayout
import com.artemchep.keyguard.ui.FlatSimpleNote
import com.artemchep.keyguard.ui.OptionsButton
import com.artemchep.keyguard.ui.ScaffoldColumn
import com.artemchep.keyguard.ui.ScaffoldLazyColumn
import com.artemchep.keyguard.ui.button.FavouriteToggleButton
import com.artemchep.keyguard.ui.shimmer.shimmer
import com.artemchep.keyguard.ui.skeleton.SkeletonText
import com.artemchep.keyguard.ui.toolbar.LargeToolbar
import org.jetbrains.compose.resources.stringResource

@Composable
fun SendAddScreen(
    args: SendAddRoute.Args,
) {
    val loadableState = produceSendAddScreenState(
        args = args,
    )

    val addScreenScope = remember {
        val addScreenBehavior = args.behavior
        AddScreenScope(
            initialFocusRequested = !addScreenBehavior.autoShowKeyboard,
        )
    }
    SendAddScreen(
        addScreenScope = addScreenScope,
        loadableState = loadableState,
    )
}

@Composable
fun SendAddScreen(
    addScreenScope: AddScreenScope,
    loadableState: Loadable<SendAddState>,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    ScaffoldLazyColumn(
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topAppBarScrollBehavior = scrollBehavior,
        topBar = {
            LargeToolbar(
                title = {
                    val title = loadableState.getOrNull()?.title
                    if (title != null) {
                        Text(title)
                    } else {
                        SkeletonText(
                            modifier = Modifier
                                .fillMaxWidth(0.4f),
                        )
                    }
                },
                navigationIcon = {
                    NavigationIcon()
                },
                actions = {
                    val actions = loadableState.getOrNull()?.actions.orEmpty()
                    OptionsButton(
                        actions = actions,
                    )
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionState = run {
            val fabOnClick = loadableState.getOrNull()?.onSave
            val fabState = if (fabOnClick != null) {
                FabState(
                    onClick = fabOnClick,
                    model = null,
                )
            } else {
                null
            }
            rememberUpdatedState(newValue = fabState)
        },
        floatingActionButton = {
            DefaultFab(
                icon = {
                    Icon(
                        imageVector = Icons.Outlined.Save,
                        contentDescription = null,
                    )
                },
                text = {
                    Text(
                        text = stringResource(Res.string.save),
                    )
                },
            )
        },
        listVerticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        populateItems(
            addScreenScope = addScreenScope,
            loadableState = loadableState,
        )
    }
}

private fun LazyListScope.populateItems(
    addScreenScope: AddScreenScope,
    loadableState: Loadable<SendAddState>,
) = loadableState.fold(
    ifLoading = {
        populateItemsSkeleton(
            addScreenScope = addScreenScope,
        )
    },
    ifOk = { state ->
        populateItemsContent(
            addScreenScope = addScreenScope,
            state = state,
        )
    },
)

private fun LazyListScope.populateItemsSkeleton(
    addScreenScope: AddScreenScope,
) {
    item("ownership") {
        AddScreenToolbarSkeletonItem()
    }
    item("ownership.section") {
        Section()
    }
    item("items") {
        Spacer(
            modifier = Modifier
                .height(24.dp),
        )
    }
    with(addScreenScope) {
        AddScreenItems()
    }
}

private fun LazyListScope.populateItemsContent(
    addScreenScope: AddScreenScope,
    state: SendAddState,
) {
    item("ownership") {
        AddScreenToolbarItem(
            state = state,
        )
    }
    item("ownership.section") {
        Section()
    }
    item("items") {
        Spacer(
            modifier = Modifier
                .height(24.dp),
        )
    }
    items(
        items = state.items,
        key = { it.id },
    ) { item ->
        with(addScreenScope) {
            AnyField(
                modifier = Modifier,
                item = item,
            )
        }
    }
}

@Composable
private fun AddScreenToolbarItem(
    modifier: Modifier = Modifier,
    state: SendAddState,
) {
    ToolbarContent(
        modifier = modifier,
        account = state.ownership.ui.account,
        organization = state.ownership.ui.organization,
        collection = state.ownership.ui.collection,
        folder = state.ownership.ui.folder,
        onClick = state.ownership.ui.onClick,
    )
}

@Composable
private fun AddScreenToolbarSkeletonItem(
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = 8.dp,
                vertical = 2.dp,
            )
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        ToolbarContentItemErrSkeleton(
            modifier = Modifier
                .padding(end = 8.dp),
            fraction = 0.5f,
        )
    }
}
