package com.artemchep.keyguard.feature.largetype

import androidx.compose.runtime.Composable
import arrow.core.partially1
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.feature.navigation.state.navigatePopSelf
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.common.util.asCodePointsSequence
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
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

    val hasUnicodeSurrogates = args.phrases
        .any { phrase ->
            phrase.any { it.isSurrogate() }
        }
    val text = if (hasUnicodeSurrogates) {
        translate(Res.string.largetype_unicode_surrogate_note)
    } else {
        null
    }

    val groups = args.phrases
        .mapIndexed { phraseIndex, phrase ->
            val offset = args.phrases
                .take(phraseIndex)
                .sumOf { it.length }
            phrase
                .asCodePointsSequence()
                .mapIndexed { index, codePoint ->
                    val finalIndex = offset + index
                    LargeTypeState.Item(
                        text = codePoint,
                        index = finalIndex,
                        colorize = args.colorize,
                        onClick = ::onClickCodePoint
                            .partially1(finalIndex),
                    )
                }
                .toList()
        }
    selectedPositionSink
        .map { index ->
            val state = LargeTypeState(
                text = text,
                index = index,
                groups = groups,
                onClose = ::onClose,
            )
            Loadable.Ok(state)
        }
}
