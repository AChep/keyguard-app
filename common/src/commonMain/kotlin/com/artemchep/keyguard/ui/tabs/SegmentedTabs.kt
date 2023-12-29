package com.artemchep.keyguard.ui.tabs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha
import com.artemchep.keyguard.ui.theme.selectedContainer
import com.artemchep.keyguard.ui.util.DividerColor
import dev.icerock.moko.resources.compose.stringResource
import kotlinx.collections.immutable.ImmutableList

@Composable
fun <T : TabItem> SegmentedButtonGroup(
    tabState: State<T>,
    tabs: ImmutableList<T>,
    onClick: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val updatedOnClick by rememberUpdatedState(onClick)
    SegmentedButtonGroup(
        modifier = modifier,
    ) {
        tabs.forEachIndexed { index, tab ->
            if (index > 0) {
                SegmentedButtonDivider()
            }

            val selectedState = remember(tabState, tab) {
                derivedStateOf {
                    tabState.value == tab
                }
            }
            SegmentedButtonItem(
                modifier = Modifier
                    .weight(1f),
                selectedProvider = {
                    selectedState.value
                },
                onClick = {
                    updatedOnClick.invoke(tab)
                },
            ) {
                Text(
                    text = stringResource(tab.title),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun SegmentedButtonGroup(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .height(40.dp),
    ) {
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .fillMaxHeight(),
        ) {
            content()
        }
        Box(
            modifier = Modifier
                .border(1.dp, DividerColor, CircleShape)
                .matchParentSize(),
        )
    }
}

@Composable
fun SegmentedButtonDivider(
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

@Composable
fun SegmentedButtonItem(
    selectedProvider: () -> Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val updatedOnClick by rememberUpdatedState(onClick)

    val selected = selectedProvider()
    val backgroundTarget = run {
        val bg = MaterialTheme.colorScheme.selectedContainer
        if (selected) bg else bg.copy(alpha = 0f)
    }
    val background = animateColorAsState(backgroundTarget, label = "BackgroundColor")
    Row(
        modifier = modifier
            .fillMaxHeight()
            .background(background.value)
            .alpha(if (updatedOnClick != null) 1f else DisabledEmphasisAlpha)
            .clickable(enabled = updatedOnClick != null) {
                updatedOnClick?.invoke()
            }
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        AnimatedVisibility(
            selected,
            enter = fadeIn() + expandHorizontally() + scaleIn(),
            exit = fadeOut() + shrinkHorizontally() + scaleOut(),
        ) {
            Icon(
                modifier = Modifier
                    .padding(end = 8.dp)
                    .size(18.dp),
                imageVector = Icons.Outlined.Check,
                contentDescription = null,
            )
        }
        ProvideTextStyle(
            MaterialTheme.typography.labelLarge,
        ) {
            content()
        }
    }
}
