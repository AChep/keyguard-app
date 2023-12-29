package com.artemchep.keyguard.feature.colorpicker

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.util.hue
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.feature.navigation.state.navigatePopSelf
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.ui.icons.generateAccentColors
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.map

@Composable
fun produceColorPickerState(
    args: ColorPickerRoute.Args,
    transmitter: RouteResultTransmitter<ColorPickerResult>,
): Loadable<ColorPickerState> = produceScreenState(
    key = "color_picker",
    initial = Loadable.Loading,
    args = arrayOf(),
) {
    // Give a user a bunch of different colors
    // to choose from. This should be enough for
    // most of the users.
    val length = 64

    val indexSink = mutablePersistedFlow("index") {
        val color = args.color
            ?: return@mutablePersistedFlow -1
        val hue = color.hue()
        val index = hue / 360f * length
        index.toInt()
            .rem(length)
    }

    val hues = 0 until length
    val items = hues
        .asSequence()
        .map { index ->
            val hue = 360f * index.toFloat() / length.toFloat()
            generateAccentColors(hue = hue)
        }
        .mapIndexed { index, colors ->
            ColorPickerState.Item(
                key = index,
                color = colors,
                onClick = {
                    indexSink.value = index
                },
            )
        }
        .toPersistentList()

    indexSink
        .map { index ->
            val content = ColorPickerState.Content(
                index = index.takeIf { it >= 0 },
                items = items,
            )
            val state = ColorPickerState(
                content = content,
                onDeny = {
                    transmitter(ColorPickerResult.Deny)
                    navigatePopSelf()
                },
                onConfirm = {
                    val item = items.getOrNull(index)
                        ?: return@ColorPickerState
                    val result = ColorPickerResult.Confirm(
                        color = item.color.dark,
                    )
                    transmitter(result)
                    navigatePopSelf()
                },
            )
            Loadable.Ok(state)
        }
}
