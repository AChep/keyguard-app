package com.artemchep.keyguard.wear.feature.vault.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.LocalContentColor
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.learn_more
import com.artemchep.keyguard.ui.ExpandedIfNotEmpty
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.animation.animateContentHeight
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.theme.info
import com.artemchep.keyguard.wear.ui.surfaceTransformation
import org.jetbrains.compose.resources.stringResource

@Composable
fun WearVaultViewInfoItem(
    modifier: Modifier = Modifier,
    item: VaultViewItem.Info,
    transformation: SurfaceTransformation? = null,
) {
    val expandable = item.message.orEmpty().length >= 200
    val expandedState = remember(expandable) {
        mutableStateOf(!expandable)
    }

    val contentColor = androidx.compose.material3.MaterialTheme.colorScheme.info
    Box(
        modifier = modifier
            .fillMaxWidth()
            .surfaceTransformation(transformation),
    ) {
        Column(
            modifier = Modifier,
        ) {
            Spacer(
                modifier = Modifier
                    .height(16.dp),
            )
            Icon(
                modifier = Modifier
                    .padding(horizontal = 8.dp),
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = contentColor,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                text = item.name,
                style = MaterialTheme.typography.titleSmall,
            )
            ExpandedIfNotEmpty(
                valueOrNull = item.message,
            ) { message ->
                Text(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .padding(horizontal = 8.dp)
                        .animateContentHeight(),
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalContentColor.current
                        .combineAlpha(MediumEmphasisAlpha),
                    overflow = TextOverflow.Ellipsis,
                    maxLines = if (expandedState.value) Int.MAX_VALUE else 4,
                )
            }
            Spacer(
                modifier = Modifier
                    .height(24.dp),
            )
        }
        AnimatedVisibility(
            modifier = Modifier
                .matchParentSize(),
            enter = fadeIn(),
            exit = fadeOut(),
            visible = !expandedState.value,
        ) {
            LearnOverlay(
                modifier = Modifier
                    .matchParentSize(),
                onClick = {
                    expandedState.value = true
                },
            )
        }
    }
}

@Composable
private fun LearnOverlay(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = 8.dp,
                    end = 8.dp,
                    top = 48.dp,
                    bottom = 8.dp,
                ),
            contentAlignment = Alignment.BottomStart,
        ) {
            LearnButton(
                elevation = 1.dp,
                onClick = onClick,
            )
        }
    }
}

@Composable
private fun LearnButton(
    modifier: Modifier = Modifier,
    backgroundColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    color: Color = MaterialTheme.colorScheme.primary,
    shape: Shape = CircleShape,
    elevation: Dp = 0.dp,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .shadow(elevation, shape)
            .clip(shape)
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .padding(
                horizontal = 16.dp,
                vertical = 12.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Icon(
            modifier = Modifier
                .size(16.dp),
            tint = color,
            imageVector = Icons.Outlined.ArrowDownward,
            contentDescription = null,
        )
        Spacer(
            modifier = Modifier
                .width(Dimens.buttonIconPadding),
        )
        Text(
            text = stringResource(Res.string.learn_more),
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}
