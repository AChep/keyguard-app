package com.artemchep.keyguard.feature.colorpicker

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ColorLens
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.feature.dialog.Dialog
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.icons.icon
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.theme.isDark
import dev.icerock.moko.resources.compose.stringResource

@Composable
fun ColorPickerScreen(
    args: ColorPickerRoute.Args,
    transmitter: RouteResultTransmitter<ColorPickerResult>,
) {
    val loadableState = produceColorPickerState(
        args = args,
        transmitter = transmitter,
    )
    Dialog(
        icon = icon(Icons.Outlined.ColorLens),
        title = {
            Text(stringResource(Res.strings.colorpicker_title))
        },
        content = {
            Column {
                val state = loadableState.getOrNull()
                    ?: return@Column
                Content(
                    state = state,
                )
            }
        },
        contentScrollable = false,
        actions = {
            val state = loadableState.getOrNull()

            val updatedOnClose by rememberUpdatedState(state?.onDeny)
            val updatedOnOk by rememberUpdatedState(state?.onConfirm)
            TextButton(
                enabled = updatedOnClose != null,
                onClick = {
                    updatedOnClose?.invoke()
                },
            ) {
                Text(stringResource(Res.strings.close))
            }
            TextButton(
                enabled = updatedOnOk != null,
                onClick = {
                    updatedOnOk?.invoke()
                },
            ) {
                Text(stringResource(Res.strings.ok))
            }
        },
    )
}

@Composable
private fun ColumnScope.Content(
    state: ColorPickerState,
) {
    val listState = rememberLazyListState()
    LazyRow(
        modifier = Modifier
            .fillMaxWidth(),
        state = listState,
    ) {
        itemsIndexed(state.content.items) { index, item ->
            val backgroundColor = if (MaterialTheme.colorScheme.isDark) {
                item.color.dark
            } else {
                item.color.light
            }
            val isSelected = state.content.index == index
            ColorItem(
                modifier = Modifier
                    .sizeIn(
                        minWidth = 16.dp,
                        minHeight = 64.dp,
                    ),
                selected = isSelected,
                backgroundColor = backgroundColor,
                onClick = item.onClick,
            )
        }
    }

    LaunchedEffect(Unit) {
        val index = state.content.index
            ?: return@LaunchedEffect
        val finalIndex = index.minus(4).coerceAtLeast(0)
        listState.scrollToItem(finalIndex)
    }
}

@Composable
private fun ColorItem(
    modifier: Modifier = Modifier,
    selected: Boolean,
    backgroundColor: Color,
    onClick: () -> Unit,
) {
    val updatedOnClick by rememberUpdatedState(onClick)
    Box(
        modifier = modifier
            .background(color = backgroundColor)
            .clickable {
                updatedOnClick.invoke()
            },
    ) {
        androidx.compose.animation.AnimatedVisibility(
            modifier = Modifier
                .align(Alignment.Center),
            visible = selected,
        ) {
            val contentColor =
                if (backgroundColor.luminance() > 0.5f) Color.Black else Color.White
            Box(
                modifier = Modifier
                    .padding(16.dp)
                    .background(
                        color = contentColor
                            .combineAlpha(alpha = 0.09f),
                        shape = CircleShape,
                    ),
            ) {
                Icon(
                    Icons.Outlined.Check,
                    contentDescription = null,
                    tint = contentColor,
                )
            }
        }
    }
}
