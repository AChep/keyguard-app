package com.artemchep.keyguard.feature.agent

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.artemchep.keyguard.feature.home.vault.component.FlatItemLayoutExpressive
import com.artemchep.keyguard.ui.ExpandedIfNotEmpty
import com.artemchep.keyguard.ui.FlatItemTextContent
import com.artemchep.keyguard.ui.animation.animateContentHeight
import com.artemchep.keyguard.ui.icons.DropdownIcon

/**
 * The main item of an approval prompt: its [title], and its [text] cut
 * to two lines. It never looks disabled. A click expands it only if
 * something is hidden: the rest of the [text], or the [details].
 */
@Composable
fun AgentApprovalItem(
    leading: @Composable RowScope.() -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    text: String? = null,
    details: (@Composable ColumnScope.() -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    // Nothing to expand if the text fits and there are no details.
    var overflowed by remember(text) { mutableStateOf(false) }
    // The dropdown icon narrows the text, so it can only overflow more.
    val expandable = expanded || overflowed || details != null
    FlatItemLayoutExpressive(
        modifier = modifier,
        rowModifier = Modifier
            .expandSemantics(
                expandable = expandable,
                expanded = expanded,
                onExpandedChange = { expanded = it },
            ),
        leading = leading,
        content = {
            FlatItemTextContent(
                title = {
                    Text(
                        text = title,
                    )
                },
                text = text?.let { value ->
                    // composable
                    {
                        AgentApprovalItemText(
                            text = value,
                            expanded = expanded,
                            onOverflowChange = { overflowed = it },
                        )
                    }
                },
            )
            ExpandedIfNotEmpty(
                details.takeIf { expanded },
            ) { content ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth(),
                ) {
                    content()
                }
            }
        },
        trailing = if (expandable) {
            // composable
            {
                DropdownIcon(
                    expanded = expanded,
                )
            }
        } else {
            null
        },
        onClick = if (expandable) {
            {
                expanded = !expanded
            }
        } else {
            null
        },
        enabled = true,
    )
}

/** The [text], cut to two lines unless [expanded]; reports whether it is cut. */
@Composable
private fun AgentApprovalItemText(
    text: String,
    expanded: Boolean,
    onOverflowChange: (Boolean) -> Unit,
) {
    Text(
        modifier = Modifier
            .animateContentHeight(),
        text = text,
        maxLines = if (expanded) Int.MAX_VALUE else 2,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { layout ->
            if (!expanded) {
                onOverflowChange(layout.hasVisualOverflow)
            }
        },
    )
}

/** Tells accessibility services that the item expands, and whether it is [expanded]. */
private fun Modifier.expandSemantics(
    expandable: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
): Modifier {
    if (!expandable) return this
    return semantics {
        if (expanded) {
            collapse {
                onExpandedChange(false)
                true
            }
        } else {
            expand {
                onExpandedChange(true)
                true
            }
        }
    }
}
