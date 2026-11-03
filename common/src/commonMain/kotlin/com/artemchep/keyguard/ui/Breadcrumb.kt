package com.artemchep.keyguard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.feature.home.vault.component.surfaceColorAtElevationSemi
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha

/** A node of a folder path; the current folder has no [onClick]. */
data class Breadcrumb(
    val name: String,
    val onClick: (() -> Unit)?,
)

/** Builds the breadcrumbs of a `/`-separated [path] relative to the root. */
fun pathBreadcrumbs(
    path: String,
    rootName: String = "/",
    onClick: (String) -> Unit,
): List<Breadcrumb> {
    val parts = path
        .split('/')
        .filter { part -> part.isNotEmpty() }
    val paths = parts.runningFold("") { parent, part ->
        if (parent.isEmpty()) part else "$parent/$part"
    }
    return paths.mapIndexed { index, itemPath ->
        Breadcrumb(
            name = if (index == 0) rootName else parts[index - 1],
            onClick = if (index == paths.lastIndex) {
                null
            } else {
                {
                    onClick(itemPath)
                }
            },
        )
    }
}

/** The folder path as a list header. */
fun LazyListScope.breadcrumbsItem(
    breadcrumbs: List<Breadcrumb>,
) {
    item("breadcrumbs") {
        BreadcrumbTree(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.contentPadding),
            breadcrumbs = breadcrumbs,
        )
    }
    item("breadcrumbs.spacer") {
        Spacer(
            modifier = Modifier
                .height(8.dp),
        )
    }
}

/** Parent folders as chips, followed by the current folder. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BreadcrumbTree(
    breadcrumbs: List<Breadcrumb>,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.Start),
    ) {
        // A lone node has no chips to line up with.
        val currentPadding = if (breadcrumbs.size > 1) BreadcrumbPadding else PaddingValues()
        breadcrumbs.forEachIndexed { index, s ->
            if (index != breadcrumbs.lastIndex) {
                BreadcrumbChip(
                    breadcrumb = s,
                )
            } else {
                Text(
                    modifier = Modifier
                        .align(Alignment.CenterVertically)
                        .padding(currentPadding),
                    text = s.name,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 5,
                )
            }
        }
    }
}

/** A parent folder; opens the folder on click. */
@Composable
private fun BreadcrumbChip(
    breadcrumb: Breadcrumb,
    modifier: Modifier = Modifier,
) {
    val updatedOnClick by rememberUpdatedState(breadcrumb.onClick)
    val tintColor = MaterialTheme.colorScheme
        .surfaceColorAtElevationSemi(1.dp)
    Row(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(tintColor)
            .clickable(enabled = breadcrumb.onClick != null) {
                updatedOnClick?.invoke()
            }
            .padding(BreadcrumbPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            modifier = Modifier
                .weight(1f, fill = false),
            text = breadcrumb.name,
            overflow = TextOverflow.Ellipsis,
            maxLines = 5,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Icon(
            Icons.Outlined.ChevronRight,
            null,
            modifier = Modifier
                .size(18.dp),
            tint = LocalContentColor.current
                .combineAlpha(DisabledEmphasisAlpha),
        )
    }
}

private val BreadcrumbPadding = PaddingValues(
    horizontal = 8.dp,
    vertical = 4.dp,
)
