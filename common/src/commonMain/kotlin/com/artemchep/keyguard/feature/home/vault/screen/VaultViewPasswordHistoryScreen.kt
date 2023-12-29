package com.artemchep.keyguard.feature.home.vault.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import com.artemchep.keyguard.feature.EmptyView
import com.artemchep.keyguard.feature.home.vault.component.VaultPasswordHistoryItem
import com.artemchep.keyguard.feature.home.vault.model.VaultPasswordHistoryItem
import com.artemchep.keyguard.feature.navigation.NavigationIcon
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.DefaultSelection
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.OptionsButton
import com.artemchep.keyguard.ui.ScaffoldLazyColumn
import com.artemchep.keyguard.ui.skeleton.SkeletonItem
import com.artemchep.keyguard.ui.skeleton.SkeletonText
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.toolbar.LargeToolbar
import dev.icerock.moko.resources.compose.stringResource
import kotlinx.collections.immutable.ImmutableList

private const val SKELETON_ITEMS_COUNT = 2

@Composable
fun VaultViewPasswordHistoryScreen(
    itemId: String,
) {
    val state = vaultViewPasswordHistoryScreenState(
        itemId = itemId,
    )
    VaultViewPasswordHistoryScreen(
        state = state,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun VaultViewPasswordHistoryScreen(
    state: VaultViewPasswordHistoryState,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    ScaffoldLazyColumn(
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topAppBarScrollBehavior = scrollBehavior,
        topBar = {
            LargeToolbar(
                title = {
                    ToolbarTitle(
                        state = state,
                    )
                },
                navigationIcon = {
                    NavigationIcon()
                },
                actions = {
                    val actions =
                        (state.content as? VaultViewPasswordHistoryState.Content.Cipher)?.actions.orEmpty()
                    OptionsButton(
                        actions = actions,
                    )
                },
                scrollBehavior = scrollBehavior,
            )
        },
        bottomBar = {
            val selectionOrNull =
                (state.content as? VaultViewPasswordHistoryState.Content.Cipher)?.selection
            DefaultSelection(
                state = selectionOrNull,
            )
        },
    ) {
        when (state.content) {
            is VaultViewPasswordHistoryState.Content.Loading -> {
                // Show a bunch of skeleton items, so it makes an impression of a
                // fully loaded screen.
                populateItemsSkeleton()
            }

            is VaultViewPasswordHistoryState.Content.Cipher -> {
                populateItemsContent(
                    items = state.content.items,
                )
            }

            else -> {
                // Do nothing.
            }
        }
    }
}

private fun LazyListScope.populateItemsSkeleton() {
    for (i in 0 until SKELETON_ITEMS_COUNT) {
        item("skeleton.$i") {
            SkeletonItem()
        }
    }
}

private fun LazyListScope.populateItemsContent(
    items: ImmutableList<VaultPasswordHistoryItem>,
) {
    if (items.isEmpty()) {
        item("empty") {
            EmptyView()
        }
    }
    items(
        items = items,
        key = { model -> model.id },
    ) { model ->
        VaultPasswordHistoryItem(
            item = model,
        )
    }
}

@Composable
private fun ToolbarTitle(
    state: VaultViewPasswordHistoryState,
) = Column {
    when (state.content) {
        is VaultViewPasswordHistoryState.Content.Loading -> {
            SkeletonText(
                modifier = Modifier
                    .fillMaxWidth(0.33f),
                style = MaterialTheme.typography.labelSmall,
                emphasis = MediumEmphasisAlpha,
            )
        }

        is VaultViewPasswordHistoryState.Content.Cipher -> {
            val name = state.content.data.name
            Text(
                text = name,
                style = MaterialTheme.typography.labelSmall,
                color = LocalContentColor.current
                    .combineAlpha(MediumEmphasisAlpha),
                overflow = TextOverflow.Ellipsis,
                maxLines = 2,
            )
        }

        is VaultViewPasswordHistoryState.Content.NotFound -> {
            Text(
                text = "Not found",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
    Text(
        text = stringResource(Res.strings.passwordhistory_header_title),
        style = MaterialTheme.typography.titleMedium,
        overflow = TextOverflow.Ellipsis,
        maxLines = 1,
    )
}
