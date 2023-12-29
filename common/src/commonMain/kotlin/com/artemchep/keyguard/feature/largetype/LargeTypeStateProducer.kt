package com.artemchep.keyguard.feature.largetype

import androidx.compose.runtime.Composable
import arrow.core.partially1
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.feature.navigation.state.navigatePopSelf
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.ui.asCodePointsSequence
import kotlinx.coroutines.flow.map

@Composable
fun produceLargeTypeScreenState(
    args: LargeTypeRoute.Args,
): Loadable<LargeTypeState> = produceScreenState(
    key = "largetype",
    args = arrayOf(
        args,
    ),
    initial = Loadable.Loading,
) {
    val selectedPositionSink = mutablePersistedFlow("index") {
        -1
    }

    fun onClose() {
        navigatePopSelf()
    }

    fun onClickCodePoint(index: Int) {
        selectedPositionSink.value = index
    }

    val text = if (args.text.any { it.isSurrogate() }) {
        "Text contains composite Unicode symbols!"
    } else {
        null
    }

    val items = args.text
        .asCodePointsSequence()
        .mapIndexed { index, codePoint ->
            LargeTypeState.Item(
                text = codePoint,
                colorize = args.colorize,
                onClick = ::onClickCodePoint.partially1(index),
            )
        }
        .toList()
    selectedPositionSink
        .map { index ->
            val state = LargeTypeState(
                text = text,
                index = index,
                items = items,
                onClose = ::onClose,
            )
            Loadable.Ok(state)
        }
}
