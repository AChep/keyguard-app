package com.artemchep.keyguard.feature.duplicates

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import com.artemchep.keyguard.common.model.fold
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.feature.EmptySearchView
import com.artemchep.keyguard.feature.home.vault.component.VaultListItem
import com.artemchep.keyguard.feature.navigation.NavigationIcon
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.DefaultSelection
import com.artemchep.keyguard.ui.DropdownMenuItemFlat
import com.artemchep.keyguard.ui.DropdownMinWidth
import com.artemchep.keyguard.ui.DropdownScopeImpl
import com.artemchep.keyguard.ui.ExpandedIfNotEmpty
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.ScaffoldLazyColumn
import com.artemchep.keyguard.ui.icons.DropdownIcon
import com.artemchep.keyguard.ui.skeleton.SkeletonItem
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.toolbar.LargeToolbar
import dev.icerock.moko.resources.compose.stringResource

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterialApi::class,
    ExperimentalFoundationApi::class,
)
@Composable
fun DuplicatesScreen(
    args: DuplicatesRoute.Args,
) {
    val loadableState = produceDuplicatesState(args)
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    ScaffoldLazyColumn(
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topAppBarScrollBehavior = scrollBehavior,
        topBar = {
            LargeToolbar(
                title = {
                    Column() {
                        Text(
                            text = stringResource(Res.strings.watchtower_header_title),
                            style = MaterialTheme.typography.labelSmall,
                            color = LocalContentColor.current
                                .combineAlpha(MediumEmphasisAlpha),
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 2,
                        )
                        Text(
                            text = stringResource(Res.strings.watchtower_item_duplicate_items_title),
                            style = MaterialTheme.typography.titleMedium,
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 1,
                        )
                    }
                },
                navigationIcon = {
                    NavigationIcon()
                },
                actions = {
                    var isAutofillWindowShowing by remember {
                        mutableStateOf(false)
                    }

                    val normalContentColor = LocalContentColor.current
                    TextButton(
                        onClick = {
                            isAutofillWindowShowing = true
                        },
                    ) {
                        Column {
                            Text(text = "Sensitivity")
                            val textOrNull =
                                loadableState.getOrNull()?.sensitivity?.name
                                    ?.lowercase()
                                    ?.capitalize()
                            ExpandedIfNotEmpty(
                                valueOrNull = textOrNull,
                            ) { text ->
                                Text(
                                    text = text,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = normalContentColor
                                        .combineAlpha(MediumEmphasisAlpha),
                                )
                            }
                        }
                        Spacer(
                            modifier = Modifier
                                .width(Dimens.buttonIconPadding),
                        )
                        DropdownIcon()

                        // Inject the dropdown popup to the bottom of the
                        // content.
                        val onDismissRequest = {
                            isAutofillWindowShowing = false
                        }
                        DropdownMenu(
                            modifier = Modifier
                                .widthIn(min = DropdownMinWidth),
                            expanded = isAutofillWindowShowing,
                            onDismissRequest = onDismissRequest,
                        ) {
                            val scope = DropdownScopeImpl(this, onDismissRequest = onDismissRequest)
                            loadableState.getOrNull()?.sensitivities.orEmpty()
                                .forEachIndexed { index, action ->
                                    scope.DropdownMenuItemFlat(
                                        action = action,
                                    )
                                }
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        bottomBar = {
            val screenState = loadableState.getOrNull()
                ?: return@ScaffoldLazyColumn
            val selectionState = screenState.selectionStateFlow.collectAsState()
            DefaultSelection(
                state = selectionState.value,
            )
        },
    ) {
        loadableState.fold(
            ifLoading = {
                for (i in 0..2) {
                    item(i) {
                        SkeletonItem()
                    }
                }
            },
            ifOk = { state ->
                val items = state.items
                if (items.isEmpty()) {
                    item("header.empty") {
                        NoItemsPlaceholder()
                    }
                }
                items(
                    items = items,
                    key = { model -> model.id },
                ) { model ->
                    VaultListItem(
                        modifier = Modifier
                            .animateItemPlacement(),
                        item = model,
                    )
                }
            },
        )
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
                text = stringResource(Res.strings.duplicates_empty_label),
            )
        },
    )
}
