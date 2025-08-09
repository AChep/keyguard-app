package com.artemchep.keyguard.feature

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.util.DividerColor
import com.artemchep.keyguard.ui.util.HorizontalDivider
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

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
    Column(
        modifier = modifier
            .padding(
                vertical = Dimens.verticalPadding,
                horizontal = Dimens.horizontalPadding,
            )
            .padding(top = 128.dp)
            .fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            modifier = Modifier
                .width(256.dp)
                .height(196.dp)
                .clip(MaterialTheme.shapes.large),
            painter = painterResource(Res.drawable.artwork_no_items),
            contentScale = ContentScale.Crop,
            contentDescription = null,
        )
        Spacer(
            modifier = Modifier
                .height(Dimens.verticalPadding),
        )
        Row(
            modifier = Modifier,
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
}

@Composable
private fun EmptyCircle(
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(DividerColor, CircleShape)
            .widthIn(min = 8.dp)
            .heightIn(min = 8.dp),
    )
}

@Composable
private fun DefaultEmptyViewText(
    modifier: Modifier = Modifier,
) {
    Text(
        modifier = modifier,
        text = stringResource(Res.string.items_empty_label),
    )
}
