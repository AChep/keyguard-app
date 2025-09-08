package com.artemchep.keyguard.feature.generator.history

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ForwardToInbox
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.fold
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.feature.EmptyView
import com.artemchep.keyguard.feature.home.vault.component.FlatDropdownSimpleExpressive
import com.artemchep.keyguard.feature.home.vault.component.Section
import com.artemchep.keyguard.feature.navigation.NavigationIcon
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.DefaultSelection
import com.artemchep.keyguard.ui.ExpandedIfNotEmptyForRow
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.FlatItemTextContent
import com.artemchep.keyguard.ui.OptionsButton
import com.artemchep.keyguard.ui.ScaffoldLazyColumn
import com.artemchep.keyguard.ui.colorizePassword
import com.artemchep.keyguard.ui.icons.IconBox
import com.artemchep.keyguard.ui.icons.KeyguardSshKey
import com.artemchep.keyguard.ui.icons.Stub
import com.artemchep.keyguard.ui.skeleton.SkeletonItem
import com.artemchep.keyguard.ui.skeleton.SkeletonSection
import com.artemchep.keyguard.ui.skeleton.skeletonItems
import com.artemchep.keyguard.ui.theme.monoFontFamily
import com.artemchep.keyguard.ui.toolbar.LargeToolbar
import com.artemchep.keyguard.ui.toolbar.util.ToolbarBehavior
import org.jetbrains.compose.resources.stringResource

@Composable
fun GeneratorHistoryScreen() {
    val loadableState = produceGeneratorHistoryState()
    GeneratorPaneMaster(
        modifier = Modifier,
        loadableState = loadableState,
    )
}

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterialApi::class,
)
@Composable
private fun GeneratorPaneMaster(
    modifier: Modifier,
    loadableState: Loadable<GeneratorHistoryState>,
) {
    val scrollBehavior = ToolbarBehavior.behavior()
    ScaffoldLazyColumn(
        modifier = modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        expressive = true,
        topAppBarScrollBehavior = scrollBehavior,
        topBar = {
            LargeToolbar(
                title = {
                    Text(stringResource(Res.string.generatorhistory_header_title))
                },
                navigationIcon = {
                    NavigationIcon()
                },
                actions = {
                    loadableState.fold(
                        ifLoading = {
                        },
                        ifOk = { state ->
                            val actions = state.options
                            OptionsButton(actions)
                        },
                    )
                },
                scrollBehavior = scrollBehavior,
            )
        },
        bottomBar = {
            val selectionOrNull = loadableState.getOrNull()?.selection
            DefaultSelection(
                state = selectionOrNull,
            )
        },
        provideContentUserScrollEnabled = {
            loadableState !is Loadable.Loading
        },
    ) {
        loadableState.fold(
            ifLoading = {
                populateGeneratorPaneMasterSkeleton()
            },
            ifOk = { state ->
                populateGeneratorPaneMasterContent(
                    state = state,
                )
            },
        )
    }
}

private fun LazyListScope.populateGeneratorPaneMasterSkeleton() {
    item("skeleton.section") {
        SkeletonSection()
    }
    skeletonItems(
        count = 20,
    )
}

@OptIn(ExperimentalFoundationApi::class)
private fun LazyListScope.populateGeneratorPaneMasterContent(
    state: GeneratorHistoryState,
) {
    if (state.items.isEmpty()) {
        item("empty") {
            EmptyView()
        }
    }
    items(state.items, key = { it.id }) { item ->
        GeneratorHistoryItem(
            modifier = Modifier
                .animateItem(),
            item = item,
        )
    }
}

@Composable
fun GeneratorHistoryItem(
    modifier: Modifier,
    item: GeneratorHistoryItem,
    shapeMaskOr: Int = 0,
) = when (item) {
    is GeneratorHistoryItem.Section -> GeneratorHistoryItem(
        modifier = modifier,
        item = item,
    )

    is GeneratorHistoryItem.Value -> GeneratorHistoryItem(
        modifier = modifier,
        item = item,
        shapeMaskOr = shapeMaskOr,
    )
}

@Composable
private fun GeneratorHistoryItem(
    modifier: Modifier,
    item: GeneratorHistoryItem.Section,
) {
    Section(
        modifier = modifier,
        text = item.text,
        caps = item.caps,
    )
}

@Composable
private fun GeneratorHistoryItem(
    modifier: Modifier,
    item: GeneratorHistoryItem.Value,
    shapeMaskOr: Int,
) {
    val selectableState by item.selectableState.collectAsState()
    val backgroundColor = when {
        selectableState.selected -> MaterialTheme.colorScheme.primaryContainer
        else -> Color.Unspecified
    }
    FlatDropdownSimpleExpressive(
        modifier = modifier,
        backgroundColor = backgroundColor,
        shapeState = item.shapeState or shapeMaskOr,
        leading = {
            Crossfade(targetState = item.type) { type ->
                val primaryIcon = when (type) {
                    GeneratorHistoryItem.Value.Type.USERNAME -> Icons.Outlined.AlternateEmail
                    GeneratorHistoryItem.Value.Type.EMAIL -> Icons.Outlined.Email
                    GeneratorHistoryItem.Value.Type.EMAIL_RELAY -> Icons.AutoMirrored.Outlined.ForwardToInbox
                    GeneratorHistoryItem.Value.Type.PASSWORD -> Icons.Outlined.Password
                    GeneratorHistoryItem.Value.Type.SSH_KEY -> Icons.Outlined.Terminal
                    null -> Icons.Stub
                }
                val secondaryIcon = when (type) {
                    GeneratorHistoryItem.Value.Type.SSH_KEY -> Icons.Outlined.KeyguardSshKey
                    else -> null
                }
                IconBox(
                    main = primaryIcon,
                    secondary = secondaryIcon,
                )
            }
        },
        content = {
            FlatItemTextContent(
                title = {
                    when (item.type) {
                        GeneratorHistoryItem.Value.Type.USERNAME,
                        GeneratorHistoryItem.Value.Type.EMAIL,
                        GeneratorHistoryItem.Value.Type.EMAIL_RELAY,
                        ->
                            Text(
                                text = item.title,
                                fontFamily = monoFontFamily,
                            )

                        else ->
                            Text(
                                text = colorizePassword(item.title, LocalContentColor.current),
                                fontFamily = monoFontFamily,
                            )
                    }
                },
                text = if (item.text.isNotBlank()) {
                    // composable
                    {
                        Text(item.text)
                    }
                } else {
                    null
                },
            )
        },
        dropdown = item.dropdown,
        trailing = {
            val onCopyAction = remember(item.dropdown) {
                item.dropdown
                    .firstNotNullOfOrNull {
                        val action = it as? FlatItemAction
                        action?.takeIf { it.type == FlatItemAction.Type.COPY }
                    }
            }
            if (onCopyAction != null) {
                val onCopy = onCopyAction.onClick
                IconButton(
                    enabled = onCopy != null,
                    onClick = {
                        onCopy?.invoke()
                    },
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = null,
                    )
                }
            }
            ExpandedIfNotEmptyForRow(
                selectableState.selected.takeIf { selectableState.selecting },
            ) { selected ->
                Checkbox(
                    checked = selected,
                    onCheckedChange = null,
                )
            }
        },
        onClick = selectableState.onClick,
        onLongClick = selectableState.onLongClick,
        enabled = true,
    )
}
