package com.artemchep.keyguard.feature.attachments

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.items
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.feature.EmptySearchView
import com.artemchep.keyguard.feature.attachments.compose.ItemAttachment
import com.artemchep.keyguard.feature.watchtower.VaultHomeScreenFilterButton2
import com.artemchep.keyguard.feature.watchtower.VaultHomeScreenFilterPaneCard2
import com.artemchep.keyguard.feature.home.vault.component.Section
import com.artemchep.keyguard.feature.navigation.NavigationIcon
import com.artemchep.keyguard.feature.twopane.TwoPaneScreen
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.DefaultSelection
import com.artemchep.keyguard.ui.ScaffoldLazyColumn
import com.artemchep.keyguard.ui.skeleton.SkeletonItem
import com.artemchep.keyguard.ui.toolbar.LargeToolbar
import com.artemchep.keyguard.ui.toolbar.SmallToolbar
import dev.icerock.moko.resources.compose.stringResource

@Composable
fun AttachmentsScreen() {
    val loadableState = produceAttachmentsScreenState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    TwoPaneScreen(
        header = { modifier ->
            SmallToolbar(
                modifier = modifier,
                title = {
                    Text(
                        text = stringResource(Res.strings.downloads),
                    )
                },
                navigationIcon = {
                    NavigationIcon()
                },
            )

            SideEffect {
                if (scrollBehavior.state.heightOffsetLimit != 0f) {
                    scrollBehavior.state.heightOffsetLimit = 0f
                }
            }
        },
        detail = { modifier ->
            VaultHomeScreenFilterPaneCard2(
                modifier = modifier,
                items = loadableState.getOrNull()?.filter?.items.orEmpty(),
                onClear = loadableState.getOrNull()?.filter?.onClear,
            )
        },
    ) { modifier, tabletUi ->
        AttachmentsScreen(
            modifier = modifier,
            state = loadableState,
            tabletUi = tabletUi,
            scrollBehavior = scrollBehavior,
        )
    }
}

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalFoundationApi::class,
    ExperimentalMaterialApi::class,
)
@Composable
fun AttachmentsScreen(
    modifier: Modifier,
    state: Loadable<AttachmentsState>,
    tabletUi: Boolean,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    ScaffoldLazyColumn(
        modifier = modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topAppBarScrollBehavior = scrollBehavior,
        topBar = {
            if (tabletUi) {
                return@ScaffoldLazyColumn
            }

            LargeToolbar(
                title = {
                    Text(
                        text = stringResource(Res.strings.downloads),
                    )
                },
                navigationIcon = {
                    NavigationIcon()
                },
                actions = {
                    VaultHomeScreenFilterButton2(
                        modifier = modifier,
                        items = state.getOrNull()?.filter?.items.orEmpty(),
                        onClear = state.getOrNull()?.filter?.onClear,
                        onSave = state.getOrNull()?.filter?.onSave,
                    )
                },
                scrollBehavior = scrollBehavior,
            )
        },
        bottomBar = {
            val selectionOrNull = state.getOrNull()?.selection
            DefaultSelection(
                state = selectionOrNull,
            )
        },
    ) {
        when (state) {
            is Loadable.Loading -> {
                for (i in 1..3) {
                    item("skeleton.$i") {
                        SkeletonItem()
                    }
                }
            }

            is Loadable.Ok -> {
                val items = state.getOrNull()?.items.orEmpty()
                if (items.isEmpty()) {
                    item("header.empty") {
                        NoItemsPlaceholder()
                    }
                }

                items(items, key = { it.key }) { item ->
                    Item(
                        modifier = Modifier
                            .animateItemPlacement(),
                        item = item,
                    )
                }
            }
        }
    }
}

@Composable
private fun NoItemsPlaceholder(
    modifier: Modifier = Modifier,
) {
    EmptySearchView(
        modifier = modifier,
        text = {
            Text(
                text = stringResource(Res.strings.downloads_empty_label),
            )
        },
    )
}

@Composable
private fun Item(
    modifier: Modifier,
    item: AttachmentsState.Item,
) = when (item) {
    is AttachmentsState.Item.Section ->
        ItemSection(
            modifier = modifier,
            item = item,
        )

    is AttachmentsState.Item.Attachment ->
        ItemAttachment(
            modifier = modifier,
            item = item.item,
        )
}

@Composable
private fun ItemSection(
    modifier: Modifier,
    item: AttachmentsState.Item.Section,
) {
    Section(
        modifier = modifier,
        text = item.name,
    )
}
