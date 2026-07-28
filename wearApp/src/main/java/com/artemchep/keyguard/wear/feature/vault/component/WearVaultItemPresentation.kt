package com.artemchep.keyguard.wear.feature.vault.component

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.wear.compose.material3.LocalContentColor
import androidx.wear.compose.material3.LocalTextStyle
import androidx.wear.compose.material3.Text
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.feature.home.vault.model.VaultItemPresentation
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.empty_value
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha
import com.artemchep.keyguard.ui.theme.combineAlpha
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun WearVaultItemPresentationTitle(
    item: VaultItemPresentation,
    style: TextStyle = LocalTextStyle.current,
) {
    val title = item.title
        .takeUnless { it.isEmpty() }
    if (title != null) {
        Text(
            text = title,
            overflow = TextOverflow.Ellipsis,
            maxLines = 1,
            style = style,
        )
    } else {
        Text(
            text = stringResource(Res.string.empty_value),
            color = LocalContentColor.current
                .combineAlpha(DisabledEmphasisAlpha),
            overflow = TextOverflow.Ellipsis,
            maxLines = 1,
            style = style,
        )
    }
}

internal fun wearVaultItemPresentationText(
    item: VaultItemPresentation,
    maxLines: Int = if (item.source.type == DSecret.Type.SecureNote) 4 else 2,
): (@Composable RowScope.() -> Unit)? = item.text
    ?.takeIf { it.isNotEmpty() }
    ?.let { text ->
        // composable
        {
            Text(
                text = text,
                overflow = TextOverflow.Ellipsis,
                maxLines = maxLines,
            )
        }
    }
