package com.artemchep.keyguard.ui.wizard

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.feature.home.vault.component.FlatItemLayoutExpressive
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.theme.combineAlpha

/**
 * One option of a single choice. Put the options
 * of a choice in a `Modifier.selectableGroup()`.
 */
@Composable
fun WizardChoice(
    title: String,
    icon: ImageVector,
    selected: Boolean,
    shapeState: Int,
    onClick: () -> Unit,
) {
    val backgroundColor = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else Color.Unspecified
    FlatItemLayoutExpressive(
        rowModifier = Modifier.semantics {
            this.selected = selected
            role = Role.RadioButton
        },
        shapeState = shapeState,
        backgroundColor = backgroundColor,
        leading = { Icon(icon, contentDescription = null) },
        content = {
            Text(title, style = MaterialTheme.typography.titleMedium)
        },
        trailing = { RadioButton(selected = selected, onClick = null) },
        onClick = onClick,
    )
}

/**
 * A row of the summary that a wizard
 * shows before its final action.
 */
@Composable
fun WizardSummaryRow(
    icon: ImageVector,
    title: String,
    value: String?,
) {
    Row(
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            modifier = Modifier
                .weight(1f)
                .widthIn(max = 120.dp),
            text = title,
            fontWeight = FontWeight.Medium,
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            modifier = Modifier
                .weight(1f)
                .widthIn(max = 120.dp),
            text = value.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = LocalContentColor.current.combineAlpha(MediumEmphasisAlpha),
        )
    }
}
