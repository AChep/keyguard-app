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

private const val PROMO_DURATION_MS = 500L

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
            val inAnimation = fadeIn(animationSpec = tween(180, delayMillis = 80)) +
                    slideIn(
                        animationSpec = tween(180, delayMillis = 80),
                        initialOffset = {
                            IntOffset(
                                x = (8 * density.density).toInt(),
                                y = 0,
                            )
                        },
                    ) +
                    scaleIn(
                        initialScale = 0.97f,
                        animationSpec = tween(80, delayMillis = 80),
                    )
            val outAnimation = fadeOut(animationSpec = tween(180)) +
                    slideOut(
                        animationSpec = tween(180),
                        targetOffset = {
                            IntOffset(
                                x = -(8 * density.density).toInt(),
                                y = 0,
                            )
                        },
                    ) +
                    scaleOut(
                        targetScale = 0.97f,
                        animationSpec = tween(80),
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
