package com.artemchep.keyguard.ui

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.theme.info
import com.artemchep.keyguard.ui.theme.infoContainer
import com.artemchep.keyguard.ui.theme.ok
import com.artemchep.keyguard.ui.theme.okContainer
import com.artemchep.keyguard.ui.theme.onInfoContainer
import com.artemchep.keyguard.ui.theme.onOkContainer
import com.artemchep.keyguard.ui.theme.onWarningContainer
import com.artemchep.keyguard.ui.theme.warning
import com.artemchep.keyguard.ui.theme.warningContainer

@Immutable
data class SimpleNote(
    val text: String,
    val type: Type,
) {
    @Immutable
    enum class Type {
        OK,
        INFO,
        WARNING,
        ERROR,
    }
}

@Composable
fun FlatSimpleNote(
    modifier: Modifier = Modifier,
    note: SimpleNote,
) {
    FlatSimpleNote(
        modifier = modifier,
        type = note.type,
        text = note.text,
    )
}

@Composable
fun FlatSimpleNote(
    modifier: Modifier = Modifier,
    type: SimpleNote.Type,
    title: String? = null,
    text: String? = null,
    leading: (@Composable RowScope.() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    val tintColor = when (type) {
        SimpleNote.Type.OK -> MaterialTheme.colorScheme.ok
        SimpleNote.Type.INFO -> MaterialTheme.colorScheme.info
        SimpleNote.Type.WARNING -> MaterialTheme.colorScheme.warning
        SimpleNote.Type.ERROR -> MaterialTheme.colorScheme.error
    }
    val surfaceColor = when (type) {
        SimpleNote.Type.OK -> MaterialTheme.colorScheme.okContainer
        SimpleNote.Type.INFO -> MaterialTheme.colorScheme.infoContainer
        SimpleNote.Type.WARNING -> MaterialTheme.colorScheme.warningContainer
        SimpleNote.Type.ERROR -> MaterialTheme.colorScheme.errorContainer
    }
    val contentColor = when (type) {
        SimpleNote.Type.OK -> MaterialTheme.colorScheme.onOkContainer
        SimpleNote.Type.INFO -> MaterialTheme.colorScheme.onInfoContainer
        SimpleNote.Type.WARNING -> MaterialTheme.colorScheme.onWarningContainer
        SimpleNote.Type.ERROR -> MaterialTheme.colorScheme.onErrorContainer
    }
    FlatItemLayout(
        modifier = modifier,
        backgroundColor = surfaceColor
            .combineAlpha(DisabledEmphasisAlpha),
        contentColor = contentColor,
        leading = {
            if (leading != null) {
                leading.invoke(this)
                return@FlatItemLayout
            }

            val imageVector = when (type) {
                SimpleNote.Type.OK -> Icons.Outlined.Check
                SimpleNote.Type.INFO -> Icons.Outlined.Info
                SimpleNote.Type.WARNING -> Icons.Outlined.Warning
                SimpleNote.Type.ERROR -> Icons.Outlined.ErrorOutline
            }
            Icon(
                imageVector = imageVector,
                contentDescription = null,
                tint = tintColor,
            )
        },
        content = {
            if (title != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = contentColor,
                )
            }
            if (text != null) {
                val textColor = if (title != null) {
                    LocalContentColor.current
                        .combineAlpha(MediumEmphasisAlpha)
                } else {
                    LocalContentColor.current
                }
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = textColor,
                )
            }
        },
        trailing = trailing,
        onClick = onClick,
        enabled = enabled,
    )
}
