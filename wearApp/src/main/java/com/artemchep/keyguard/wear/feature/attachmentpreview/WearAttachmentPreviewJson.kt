package com.artemchep.keyguard.wear.feature.attachmentpreview

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.LocalContentColor
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.artemchep.keyguard.feature.attachmentpreview.AttachmentPreviewContent
import com.artemchep.keyguard.feature.attachmentpreview.AttachmentPreviewJsonNode
import com.artemchep.keyguard.feature.attachmentpreview.message
import com.artemchep.keyguard.feature.attachmentpreview.summary
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.attachment_preview_json_collapsed
import com.artemchep.keyguard.res.attachment_preview_json_expanded
import com.artemchep.keyguard.res.attachment_preview_json_tree_view
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.wear.ui.WearListCard
import com.artemchep.keyguard.wear.ui.WearListLabel
import com.artemchep.keyguard.wear.ui.WearScaffoldLoader
import com.artemchep.keyguard.wear.ui.WearScaffoldScreen
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun WearAttachmentPreviewJson(
    fileName: String,
    content: AttachmentPreviewContent.Json,
    busy: Boolean,
) {
    val tree = content.preview.tree
    var showTree by remember { mutableStateOf(tree != null) }
    var expandedIds by remember {
        mutableStateOf(tree?.let { setOf(it.rootId) }.orEmpty())
    }
    val rows = remember(tree, expandedIds) {
        tree?.visibleRows(expandedIds).orEmpty()
    }
    // Keep each view's scroll position in memory without saving document paths.
    CompositionLocalProvider(LocalSaveableStateRegistry provides null) {
        val holder = rememberSaveableStateHolder()
        holder.SaveableStateProvider(if (showTree) "tree" else "source") {
            WearScaffoldScreen(
                title = fileName,
                overlay = { WearScaffoldLoader(visible = busy) },
            ) { transformationSpec ->
                if (tree != null) {
                    wearAttachmentPreviewJsonSwitch(
                        checked = showTree,
                        onCheckedChange = { showTree = it },
                        transformationSpec = transformationSpec,
                    )
                }
                if (showTree) {
                    items(rows, key = { "json.node.${it.id}" }) { node ->
                        WearAttachmentPreviewJsonNode(
                            modifier = Modifier
                                .fillMaxWidth()
                                .minimumVerticalContentPadding(
                                    if (node.expandable) {
                                        ButtonDefaults.minimumVerticalListContentPadding
                                    } else {
                                        CardDefaults.minimumVerticalListContentPadding
                                    },
                                )
                                .transformedHeight(this, transformationSpec),
                            node = node,
                            expanded = node.id in expandedIds,
                            onToggle = {
                                expandedIds = if (node.id in expandedIds) {
                                    expandedIds - node.id
                                } else {
                                    expandedIds + node.id
                                }
                            },
                            transformation = SurfaceTransformation(transformationSpec),
                        )
                    }
                } else {
                    wearAttachmentPreviewJsonSource(content, transformationSpec)
                }
            }
        }
    }
}

private fun TransformingLazyColumnScope.wearAttachmentPreviewJsonSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    transformationSpec: TransformationSpec,
) {
    item("json.view") {
        SwitchButton(
            modifier = Modifier
                .fillMaxWidth()
                .minimumVerticalContentPadding(ButtonDefaults.minimumVerticalListContentPadding)
                .transformedHeight(this, transformationSpec),
            checked = checked,
            onCheckedChange = onCheckedChange,
            label = {
                Text(
                    text = stringResource(Res.string.attachment_preview_json_tree_view),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            transformation = SurfaceTransformation(transformationSpec),
        )
    }
}

private fun TransformingLazyColumnScope.wearAttachmentPreviewJsonSource(
    content: AttachmentPreviewContent.Json,
    transformationSpec: TransformationSpec,
) {
    content.preview.error?.let { error ->
        item("json.error") {
            WearListLabel(
                modifier = Modifier.transformedHeight(this, transformationSpec),
                text = error.message(),
                transformation = SurfaceTransformation(transformationSpec),
            )
        }
    }
    item("json.source") {
        WearAttachmentPreviewText(
            modifier = Modifier.transformedHeight(this, transformationSpec),
            text = content.text,
            transformation = SurfaceTransformation(transformationSpec),
        )
    }
}

@Composable
private fun WearAttachmentPreviewJsonNode(
    modifier: Modifier,
    node: AttachmentPreviewJsonNode,
    expanded: Boolean,
    onToggle: () -> Unit,
    transformation: SurfaceTransformation?,
) {
    if (node.expandable) {
        val expansionLabel = stringResource(
            if (expanded) Res.string.attachment_preview_json_expanded else Res.string.attachment_preview_json_collapsed,
        )
        Button(
            modifier = modifier.semantics { stateDescription = expansionLabel },
            onClick = onToggle,
            label = { WearAttachmentPreviewJsonKey(node) },
            secondaryLabel = { WearAttachmentPreviewJsonDetails(node) },
            icon = {
                Icon(
                    modifier = Modifier.size(ButtonDefaults.IconSize),
                    imageVector = if (expanded) Icons.Outlined.ExpandMore else Icons.Outlined.ChevronRight,
                    contentDescription = null,
                )
            },
            transformation = transformation,
        )
    } else {
        val layoutDirection = LocalLayoutDirection.current
        val leadingSpace = ButtonDefaults.IconSize + ButtonDefaults.IconSpacing +
            ButtonDefaults.ContentPadding.calculateStartPadding(layoutDirection) -
            CardDefaults.ContentPadding.calculateStartPadding(layoutDirection)
        val labelModifier = Modifier.padding(start = leadingSpace)
        WearListCard(
            modifier = modifier,
            title = { WearAttachmentPreviewJsonKey(node, labelModifier) },
            text = { WearAttachmentPreviewJsonDetails(node, labelModifier) },
            transformation = transformation,
        )
    }
}

@Composable
private fun WearAttachmentPreviewJsonKey(
    node: AttachmentPreviewJsonNode,
    modifier: Modifier = Modifier,
) {
    Text(
        modifier = modifier.padding(start = 8.dp * node.depth.coerceAtMost(2)),
        text = node.key,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun WearAttachmentPreviewJsonDetails(
    node: AttachmentPreviewJsonNode,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(start = 8.dp * node.depth.coerceAtMost(2))) {
        Text(text = node.summary(), maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (node.depth > 2) {
            Text(
                text = node.parentPath,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall,
                color = LocalContentColor.current.combineAlpha(MediumEmphasisAlpha),
            )
        }
    }
}

