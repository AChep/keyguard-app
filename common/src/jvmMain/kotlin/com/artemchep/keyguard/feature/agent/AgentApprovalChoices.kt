package com.artemchep.keyguard.feature.agent

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import com.artemchep.keyguard.common.service.agent.MAX_AGENT_CALLER_NAME_LENGTH
import com.artemchep.keyguard.common.service.agent.sanitizedAgentDisplayValue
import com.artemchep.keyguard.feature.home.vault.component.FlatItemLayoutExpressive
import com.artemchep.keyguard.ui.FlatItemTextContent

/**
 * An item of an approval prompt, sanitized for display: its [name], and
 * its [username] and [accountName] as the subtitle, if either is set.
 */
fun agentApprovalChoiceItem(
    name: String,
    username: String?,
    accountName: String?,
) = AgentApprovalChoiceItem(
    title = name.sanitizedAgentDisplayValue(MAX_AGENT_CALLER_NAME_LENGTH).orEmpty(),
    text = listOfNotNull(username, accountName)
        .mapNotNull { it.sanitizedAgentDisplayValue(MAX_AGENT_CALLER_NAME_LENGTH) }
        .joinToString(separator = " • ")
        .takeIf { it.isNotEmpty() },
)

/**
 * The [items] of an approval prompt, one of which approving answers with:
 * a radio list with the [selected] item, or a plain row with the [icon] if
 * there is only one item.
 *
 * @param onSelect Selects an item by its index; `null` while the selection
 *   can't change.
 */
@Composable
fun ColumnScope.AgentApprovalChoiceList(
    items: List<AgentApprovalChoiceItem>,
    selected: Int,
    onSelect: ((Int) -> Unit)?,
    icon: ImageVector = Icons.Outlined.Key,
) {
    val single = items.singleOrNull()
    if (single != null) {
        // As an approval prompt shows the one item it releases.
        AgentApprovalChoiceRow(
            item = single,
            leading = {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                )
            },
        )
        return
    }

    Column(
        modifier = Modifier
            .selectableGroup(),
    ) {
        items.forEachIndexed { index, item ->
            val isSelected = index == selected
            AgentApprovalChoiceRow(
                item = item,
                rowModifier = Modifier
                    .semantics {
                        this.selected = isSelected
                        role = Role.RadioButton
                    },
                leading = {
                    RadioButton(
                        selected = isSelected,
                        onClick = null,
                        enabled = onSelect != null,
                    )
                },
                onClick = onSelect?.let { select ->
                    { select(index) }
                },
            )
        }
    }
}

@Composable
private fun AgentApprovalChoiceRow(
    item: AgentApprovalChoiceItem,
    leading: @Composable () -> Unit,
    rowModifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    FlatItemLayoutExpressive(
        rowModifier = rowModifier,
        leading = {
            leading()
        },
        content = {
            FlatItemTextContent(
                title = {
                    Text(
                        text = item.title,
                    )
                },
                text = item.text?.let { text ->
                    // composable
                    {
                        Text(
                            text = text,
                        )
                    }
                },
            )
        },
        onClick = onClick,
    )
}
