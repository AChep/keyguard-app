package com.artemchep.keyguard.feature.home.vault.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.icons.IconSmallBox
import com.artemchep.keyguard.ui.theme.combineAlpha

/**
 * A small badge with an icon, a title and a secondary text,
 * shown under an item of the vault view screen.
 */
@Composable
fun VaultViewBadge(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    title: String,
    text: String?,
    textFontFamily: FontFamily? = null,
    error: Boolean = false,
    onClick: (() -> Unit)?,
) {
    val updatedOnClick by rememberUpdatedState(onClick)
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.small,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier
                .clickable(
                    enabled = onClick != null,
                    role = Role.Button,
                ) {
                    updatedOnClick?.invoke()
                }
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val contentColor = if (error) {
                MaterialTheme.colorScheme.error
            } else {
                LocalContentColor.current
            }
            Box(
                modifier = Modifier
                    .size(16.dp),
            ) {
                IconSmallBox(
                    main = icon,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                modifier = Modifier
                    .widthIn(max = 128.dp)
                    .alignByBaseline(),
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (text != null) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    modifier = Modifier
                        .widthIn(max = 128.dp)
                        .alignByBaseline(),
                    text = text,
                    color = contentColor
                        .combineAlpha(MediumEmphasisAlpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontFamily = textFontFamily,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
