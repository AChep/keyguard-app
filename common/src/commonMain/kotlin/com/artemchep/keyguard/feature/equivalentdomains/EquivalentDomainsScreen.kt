@file:OptIn(ExperimentalMaterial3Api::class)

package com.artemchep.keyguard.feature.equivalentdomains

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Domain
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.feature.EmptyView
import com.artemchep.keyguard.feature.home.vault.component.Section
import com.artemchep.keyguard.feature.navigation.NavigationIcon
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.FlatDropdown
import com.artemchep.keyguard.ui.FlatItemTextContent
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.ScaffoldLazyColumn
import com.artemchep.keyguard.ui.skeleton.SkeletonItemPilled
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.toolbar.LargeToolbar
import com.artemchep.keyguard.ui.toolbar.util.ToolbarBehavior
import org.jetbrains.compose.resources.stringResource

@Composable
fun EquivalentDomainsScreen(
    args: EquivalentDomainsRoute.Args,
) {
    val state = produceEquivalentDomainsScreenState(
        args = args,
    )
    EquivalentDomainsContent(
        state = state,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EquivalentDomainsContent(
    state: EquivalentDomainsState,
) {
    val scrollBehavior = ToolbarBehavior.behavior()
    ScaffoldLazyColumn(
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topAppBarScrollBehavior = scrollBehavior,
        topBar = {
            LargeToolbar(
                title = {
                    Column {
                        Text(
                            text = stringResource(Res.string.account),
                            style = MaterialTheme.typography.labelSmall,
                            color = LocalContentColor.current
                                .combineAlpha(MediumEmphasisAlpha),
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 2,
                        )
                        Text(
                            text = stringResource(Res.string.equivalent_domains),
                            style = MaterialTheme.typography.titleMedium,
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 1,
                        )
                    }
                },
                navigationIcon = {
                    NavigationIcon()
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) {
        when (val contentState = state.content) {
            is Loadable.Loading -> {
                for (i in 1..3) {
                    item("skeleton.$i") {
                        SkeletonItemPilled()
                    }
                }
            }

            is Loadable.Ok -> {
                val items = contentState.value.items
                if (items.isEmpty()) {
                    item("empty") {
                        EmptyView(
                            icon = {
                                Icon(Icons.Outlined.Domain, null)
                            },
                            text = {
                                Text(
                                    text = stringResource(Res.string.equivalent_domains_empty_label),
                                )
                            },
                        )
                    }
                }

                items(
                    items = items,
                    key = { it.key },
                ) {
                    EquivalentDomainsItem(
                        modifier = Modifier
                            .animateItem(),
                        item = it,
                    )
                }
            }
        }
    }
}

@Composable
private fun EquivalentDomainsItem(
    modifier: Modifier = Modifier,
    item: EquivalentDomainsState.Content.Item,
) = when (item) {
    is EquivalentDomainsState.Content.Item.Section -> EquivalentDomainsSectionItem(modifier, item)
    is EquivalentDomainsState.Content.Item.Content -> EquivalentDomainsContentItem(modifier, item)
}

@Composable
private fun EquivalentDomainsSectionItem(
    modifier: Modifier = Modifier,
    item: EquivalentDomainsState.Content.Item.Section,
) {
    Section(
        modifier = modifier,
        text = item.text,
    )
}

@Composable
private fun EquivalentDomainsContentItem(
    modifier: Modifier = Modifier,
    item: EquivalentDomainsState.Content.Item.Content,
) {
    FlatDropdown(
        modifier = modifier,
        content = {
            FlatItemTextContent(
                title = {
                    Text(item.title)
                },
            )
        },
        onClick = item.onClick,
        enabled = !item.excluded,
    )
}
