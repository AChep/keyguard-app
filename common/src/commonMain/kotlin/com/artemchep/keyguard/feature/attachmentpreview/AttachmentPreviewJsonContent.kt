package com.artemchep.keyguard.feature.attachmentpreview

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.getShapeState
import com.artemchep.keyguard.feature.home.vault.component.FlatItemSimpleExpressive
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.localization.wrap
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.ProvideScaffoldLocalValues
import com.artemchep.keyguard.ui.tabs.SegmentedButtonGroup
import com.artemchep.keyguard.ui.tabs.TabItem
import com.artemchep.keyguard.ui.theme.Dimens
import kotlinx.collections.immutable.persistentListOf
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** In-memory state belongs to the parsed preview, not to its highlighted source. */
@Stable
internal class AttachmentPreviewJsonViewState(
    preview: AttachmentPreviewJsonPreview,
) {
    var mode by mutableStateOf(
        if (preview.tree != null) AttachmentPreviewJsonMode.Tree else AttachmentPreviewJsonMode.Source,
    )
    var expandedIds by mutableStateOf(setOfNotNull(preview.tree?.rootId))
        private set

    val treeScrollState = LazyListState()
    val sourceScrollState = LazyListState()
    val sourceHorizontalScrollState = ScrollState(0)

    fun toggle(id: String) {
        expandedIds = if (id in expandedIds) expandedIds - id else expandedIds + id
    }
}

@Composable
internal fun rememberAttachmentPreviewJsonViewState(
    preview: AttachmentPreviewJsonPreview?,
): AttachmentPreviewJsonViewState? = remember(preview) {
    preview?.let(::AttachmentPreviewJsonViewState)
}

internal enum class AttachmentPreviewJsonMode(
    override val key: String,
    override val title: TextHolder,
) : TabItem {
    Source("source", Res.string.attachment_preview_action_source.wrap()),
    Tree("tree", Res.string.attachment_preview_action_tree.wrap()),
    ;

    companion object {
        val entriesList = persistentListOf(Source, Tree)
    }
}

@Composable
internal fun AttachmentPreviewJsonContent(
    content: AttachmentPreviewContent.Json,
    state: AttachmentPreviewJsonViewState,
    wrapLines: Boolean,
    contentPadding: PaddingValues,
) = ProvideScaffoldLocalValues(expressive = true) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
    ) {
        val tree = content.preview.tree
        if (tree != null) {
            SegmentedButtonGroup(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.contentPadding, vertical = 8.dp),
                tabState = rememberUpdatedState(state.mode),
                tabs = AttachmentPreviewJsonMode.entriesList,
                onClick = { state.mode = it },
                weight = 1f,
            )
        } else {
            content.preview.error?.let { error ->
                Text(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Dimens.contentPadding, vertical = 8.dp),
                    text = error.message(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        val viewerModifier = Modifier.fillMaxWidth().weight(1f)
        if (tree != null && state.mode == AttachmentPreviewJsonMode.Tree) {
            AttachmentPreviewJsonTreeContent(
                modifier = viewerModifier,
                tree = tree,
                state = state,
            )
        } else {
            AttachmentPreviewJsonSource(
                modifier = viewerModifier,
                code = content.code,
                state = state,
                wrapLines = wrapLines,
            )
        }
    }
}

@Composable
private fun AttachmentPreviewJsonTreeContent(
    tree: AttachmentPreviewJsonTree,
    state: AttachmentPreviewJsonViewState,
    modifier: Modifier = Modifier,
) {
    val expandedIds = state.expandedIds
    val rows = remember(tree, expandedIds) { tree.visibleRows(expandedIds) }
    LazyColumn(
        modifier = modifier,
        state = state.treeScrollState,
        contentPadding = PaddingValues(bottom = 8.dp),
    ) {
        itemsIndexed(
            items = rows,
            key = { _, node -> node.id },
            contentType = { _, node -> node.expandable },
        ) { index, node ->
            AttachmentPreviewJsonRow(
                node = node,
                expanded = node.id in expandedIds,
                shapeState = getShapeState(rows, index) { _, _ -> true },
                onToggle = { state.toggle(node.id) },
            )
        }
    }
}

@Composable
private fun AttachmentPreviewJsonRow(
    node: AttachmentPreviewJsonNode,
    expanded: Boolean,
    shapeState: Int,
    onToggle: () -> Unit,
) {
    val summary = node.summary()
    val expansion = stringResource(
        if (expanded) Res.string.attachment_preview_json_expanded else Res.string.attachment_preview_json_collapsed,
    )
    val parentPath = node.parentPath.takeIf { node.depth > JSON_MAX_INDENT_DEPTH }
    FlatItemSimpleExpressive(
        modifier = Modifier.clearAndSetSemantics {
            contentDescription = listOfNotNull(parentPath, node.key, summary).joinToString(". ")
            if (node.expandable) {
                role = Role.Button
                stateDescription = expansion
                onClick {
                    onToggle()
                    true
                }
            }
        },
        shapeState = shapeState,
        title = {
            AttachmentPreviewJsonTitle(node.key, parentPath)
        },
        text = {
            Text(
                text = summary,
                fontFamily = FontFamily.Monospace,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leading = {
            AttachmentPreviewJsonLeading(node, expanded)
        },
        onClick = onToggle.takeIf { node.expandable },
        enabled = true,
    )
}

@Composable
private fun AttachmentPreviewJsonTitle(key: String, parentPath: String?) {
    Column {
        if (parentPath != null) {
            Text(
                text = parentPath,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = key,
            fontFamily = FontFamily.Monospace,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AttachmentPreviewJsonLeading(node: AttachmentPreviewJsonNode, expanded: Boolean) {
    Spacer(Modifier.width(Dimens.contentPadding * node.depth.coerceAtMost(JSON_MAX_INDENT_DEPTH)))
    if (node.expandable) {
        Icon(
            imageVector = if (expanded) {
                Icons.Outlined.KeyboardArrowDown
            } else {
                Icons.AutoMirrored.Outlined.KeyboardArrowRight
            },
            contentDescription = null,
        )
    } else {
        Spacer(Modifier.size(24.dp))
    }
}

@Composable
fun AttachmentPreviewJsonType.label(): String = stringResource(
    when (this) {
        AttachmentPreviewJsonType.Object -> Res.string.attachment_preview_json_object
        AttachmentPreviewJsonType.Array -> Res.string.attachment_preview_json_array
        AttachmentPreviewJsonType.String -> Res.string.attachment_preview_json_string
        AttachmentPreviewJsonType.Number -> Res.string.attachment_preview_json_number
        AttachmentPreviewJsonType.Boolean -> Res.string.attachment_preview_json_boolean
        AttachmentPreviewJsonType.Null -> Res.string.attachment_preview_json_null
    },
)

@Composable
fun AttachmentPreviewJsonNode.summary(): String {
    val detail = when (type) {
        AttachmentPreviewJsonType.Object -> pluralStringResource(
            Res.plurals.attachment_preview_json_properties,
            childCount,
            childCount,
        )
        AttachmentPreviewJsonType.Array -> pluralStringResource(
            Res.plurals.attachment_preview_json_items,
            childCount,
            childCount,
        )
        else -> value
    }
    return listOfNotNull(type.label(), detail).joinToString(" · ")
}

@Composable
fun AttachmentPreviewJsonError.message(): String = stringResource(
    when (this) {
        AttachmentPreviewJsonError.Invalid -> Res.string.attachment_preview_json_error_invalid
        AttachmentPreviewJsonError.TooLarge -> Res.string.attachment_preview_json_error_too_large
        AttachmentPreviewJsonError.TooDeep -> Res.string.attachment_preview_json_error_too_deep
        AttachmentPreviewJsonError.TooManyNodes -> Res.string.attachment_preview_json_error_too_many_nodes
        AttachmentPreviewJsonError.DuplicateKeys -> Res.string.attachment_preview_json_error_duplicate_keys
    },
)

private const val JSON_MAX_INDENT_DEPTH = 3
