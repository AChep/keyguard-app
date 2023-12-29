package com.artemchep.keyguard.feature.license

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.service.license.model.License
import com.artemchep.keyguard.feature.EmptySearchView
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIcon
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha
import com.artemchep.keyguard.ui.FlatItemLayout
import com.artemchep.keyguard.ui.FlatItemTextContent
import com.artemchep.keyguard.ui.FlatTextFieldBadge
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.ScaffoldLazyColumn
import com.artemchep.keyguard.ui.icons.ChevronIcon
import com.artemchep.keyguard.ui.skeleton.SkeletonItem
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.theme.infoContainer
import com.artemchep.keyguard.ui.toolbar.LargeToolbar
import dev.icerock.moko.resources.compose.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicenseScreen() {
    val loadableState = produceLicenseState()

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    ScaffoldLazyColumn(
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topAppBarScrollBehavior = scrollBehavior,
        topBar = {
            LargeToolbar(
                title = {
                    Text(
                        text = stringResource(Res.strings.settings_open_source_licenses_header_title),
                    )
                },
                navigationIcon = {
                    NavigationIcon()
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) {
        when (loadableState) {
            is Loadable.Ok -> {
                val list = loadableState.value.content.items
                if (list.isEmpty()) {
                    item("empty") {
                        NoItemsPlaceholder()
                    }
                }

                items(list) { item ->
                    LicenseItem(
                        item = item,
                    )
                }
            }

            is Loadable.Loading -> {
                item {
                    repeat(3) {
                        SkeletonItem()
                    }
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
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LicenseItem(
    modifier: Modifier = Modifier,
    item: License,
) {
    val navigationController by rememberUpdatedState(LocalNavigationController.current)
    val scmUrl by rememberUpdatedState(item.scm?.url)
    FlatItemLayout(
        modifier = modifier,
        content = {
            FlatItemTextContent(
                title = {
                    Text(
                        text = item.name
                            ?: stringResource(Res.strings.empty_value),
                        color = LocalContentColor.current
                            .let { color ->
                                // If the name doesn't exist, then show it with
                                // a different accent.
                                if (item.name != null) {
                                    color
                                } else {
                                    color.combineAlpha(DisabledEmphasisAlpha)
                                }
                            },
                    )
                },
            )
            val dependency =
                "${item.groupId}:${item.artifactId}:${item.version}"
            Text(
                text = dependency,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelSmall,
                color = LocalContentColor.current
                    .combineAlpha(MediumEmphasisAlpha),
            )
            Spacer(
                modifier = Modifier
                    .height(8.dp),
            )
            FlowRow(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item.spdxLicenses.forEach { license ->
                    FlatTextFieldBadge(
                        backgroundColor = MaterialTheme.colorScheme.infoContainer,
                        text = license.name,
                    )
                }
            }
        },
        trailing = if (scmUrl != null) {
            // composable
            {
                ChevronIcon()
            }
        } else {
            null
        },
        enabled = true,
        onClick = if (scmUrl != null) {
            // lambda
            {
                val url = scmUrl
                if (url != null) {
                    val intent = NavigationIntent.NavigateToBrowser(
                        url = url,
                    )
                    navigationController.queue(intent)
                }
            }
        } else {
            null
        },
    )
}
