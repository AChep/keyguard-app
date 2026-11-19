package com.artemchep.keyguard.feature.colorpicker

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ColorLens
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.common.util.toHex
import com.artemchep.keyguard.feature.dialog.Dialog
import com.artemchep.keyguard.feature.dialog.dialogArrowNavigation
import com.artemchep.keyguard.feature.dialog.dialogTabTargets
import com.artemchep.keyguard.feature.dialog.rememberInitialItemFocus
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.icons.icon
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.theme.isDark
import org.jetbrains.compose.resources.stringResource

@Composable
fun ColorPickerScreen(
    args: ColorPickerRoute.Args,
    transmitter: RouteResultTransmitter<ColorPickerResult>,
) {
    val loadableState = produceColorPickerState(
        args = args,
        transmitter = transmitter,
    )
    ColorPickerDialog(loadableState)
}

@Composable
internal fun ColorPickerDialog(loadableState: Loadable<ColorPickerState>) {
    val close = remember { FocusRequester() }
    val ok = remember { FocusRequester() }
    Dialog(
        icon = icon(Icons.Outlined.ColorLens),
        title = {
            Text(stringResource(Res.string.colorpicker_title))
        },
        content = {
            Column {
                val state = loadableState.getOrNull()
                    ?: return@Column
                Content(
                    state = state,
                    close = close,
                    ok = ok,
                )
            }
        },
        contentScrollable = false,
        actions = {
            val state = loadableState.getOrNull()

            val updatedOnClose by rememberUpdatedState(state?.onDeny)
            val updatedOnOk by rememberUpdatedState(state?.onConfirm)
            TextButton(
                modifier = Modifier.focusRequester(close),
                enabled = updatedOnClose != null,
                onClick = {
                    updatedOnClose?.invoke()
                },
            ) {
                Text(stringResource(Res.string.close))
            }
            TextButton(
                modifier = Modifier.focusRequester(ok),
                enabled = updatedOnOk != null,
                onClick = {
                    updatedOnOk?.invoke()
                },
            ) {
                Text(stringResource(Res.string.ok))
            }
        },
    )
}

@Composable
private fun ColumnScope.Content(
    state: ColorPickerState,
    close: FocusRequester,
    ok: FocusRequester,
) {
    val initialIndex = remember { state.content.index ?: 0 }
    // Keep a few colors before the selection in view.
    val contextIndex = (initialIndex - 4).coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = contextIndex)
    val initialFocus = listState.rememberInitialItemFocus(
        index = initialIndex.takeIf { state.content.items.isNotEmpty() },
        contextIndex = contextIndex,
    )
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .selectableGroup()
            .dialogArrowNavigation()
            .dialogTabTargets(
                previous = if (state.onConfirm != null) ok else close,
                next = close,
            ),
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
                    .then(if (index == initialIndex) Modifier.focusRequester(initialFocus) else Modifier)
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
            .semantics { contentDescription = backgroundColor.toHex() }
            .selectable(selected = selected, role = Role.RadioButton, onClick = { updatedOnClick() }),
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
