package com.artemchep.keyguard.ui.animation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

@Composable
fun animateFloatStateOneWayAsState(
    targetValue: State<Float>,
) = animateValueStateOneWayAsState(
    targetValue = targetValue,
    emptyConverter = { it <= 0.01f },
    typeConverter = Float.VectorConverter,
)

@Composable
fun <T, V : AnimationVector> animateValueStateOneWayAsState(
    targetValue: State<T>,
    emptyConverter: (T) -> Boolean,
    typeConverter: TwoWayConverter<T, V>,
    animationSpec: AnimationSpec<T> = remember { spring() },
    visibilityThreshold: T? = null,
    label: String = "ValueAnimation",
): State<T> {
    val animatable = remember {
        Animatable(
            initialValue = targetValue.value,
            typeConverter = typeConverter,
            visibilityThreshold = visibilityThreshold,
            label = label,
        )
    }
    val animSpec: AnimationSpec<T> by rememberUpdatedState(
        animationSpec.run {
            if (
                visibilityThreshold != null &&
                this is SpringSpec &&
                this.visibilityThreshold != visibilityThreshold
            ) {
                spring(dampingRatio, stiffness, visibilityThreshold)
            } else {
                this
            }
        },
    )
    val flow = remember {
        snapshotFlow {
            targetValue.value
        }
    }
    LaunchedEffect(flow) {
        flow
            .onEach { newTarget ->
                // Launch a new animation that goes to the
                // target value.
                launch {
                    if (newTarget != animatable.targetValue) {
                        val shouldAnimate = emptyConverter(newTarget)
                        if (shouldAnimate) {
                            animatable.animateTo(newTarget, animSpec)
                        } else animatable.snapTo(newTarget)
                    }
                }
            }
            .collect()
    }
    return animatable.asState()
}
