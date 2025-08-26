package com.artemchep.keyguard.feature

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideIn
import androidx.compose.animation.slideOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow

private const val PROMO_DURATION_MS = 1500L

enum class PromoViewStatus {
    PROMO,
    CONTENT,
}

@Composable
fun rememberPromoViewStatus(
    playPromo: Boolean,
): State<PromoViewStatus> {
    val initialState = if (playPromo) PromoViewStatus.PROMO else PromoViewStatus.CONTENT
    return remember {
        flow {
            delay(PROMO_DURATION_MS)
            emit(PromoViewStatus.CONTENT)
        }
    }.collectAsState(initial = initialState)
}

@Composable
fun PromoView(
    modifier: Modifier = Modifier,
    state: State<PromoViewStatus> = rememberPromoViewStatus(true),
    promo: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val density by rememberUpdatedState(LocalDensity.current)
    AnimatedContent(
        modifier = modifier,
        transitionSpec = {
            val animationDurationMultiplier = 2

            fun mul(duration: Int): Int = duration * animationDurationMultiplier

            val inAnimation = fadeIn(animationSpec = tween(mul(180), delayMillis = mul(60))) +
                    slideIn(
                        animationSpec = tween(mul(180), delayMillis = mul(60)),
                        initialOffset = {
                            IntOffset(
                                x = (4 * density.density).toInt(),
                                y = 0,
                            )
                        },
                    )
            val outAnimation = fadeOut(animationSpec = tween(mul(180))) +
                    slideOut(
                        animationSpec = tween(mul(180)),
                        targetOffset = {
                            IntOffset(
                                x = -(4 * density.density).toInt(),
                                y = 0,
                            )
                        },
                    )
            (inAnimation)
                .togetherWith(outAnimation)
        },
        targetState = state.value,
    ) { curState ->
        when (curState) {
            PromoViewStatus.PROMO -> promo()
            PromoViewStatus.CONTENT -> content()
        }
    }
}
