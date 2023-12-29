package com.artemchep.keyguard.feature

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import dev.icerock.moko.resources.compose.stringResource

@Composable
fun EmptySearchView(
    modifier: Modifier = Modifier,
    text: @Composable () -> Unit = {
        DefaultEmptyViewText()
    },
) {
    EmptyView(
        modifier = modifier,
        icon = {
            Icon(
                imageVector = Icons.Outlined.SearchOff,
                contentDescription = null,
            )
        },
        text = text,
    )
}

@Composable
fun EmptyView(
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null,
    text: @Composable () -> Unit = {
        DefaultEmptyViewText()
    },
) {
    Row(
        modifier = modifier
            .padding(
                vertical = Dimens.verticalPadding,
                horizontal = Dimens.horizontalPadding,
            )
            .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides LocalContentColor.current
                .combineAlpha(MediumEmphasisAlpha),
            LocalTextStyle provides MaterialTheme.typography.labelLarge,
        ) {
            if (icon != null) {
                icon()
                Spacer(
                    modifier = Modifier
                        .width(Dimens.buttonIconPadding),
                )
            }
            Box(
                modifier = Modifier
                    .widthIn(max = 196.dp),
            ) {
                text()
            }
        }
    }
}

@Composable
private fun DefaultEmptyViewText(
    modifier: Modifier = Modifier,
) {
    Text(
        modifier = modifier,
        text = stringResource(Res.strings.items_empty_label),
    )
}
