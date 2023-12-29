package com.artemchep.keyguard.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.expandIn
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntSize

/**
 * A container that expands if the [valueOrNull] is
 * not `null`.
 */
@Suppress("FunctionName")
@Composable
fun <T : Any> ExpandedIfNotEmpty(
    valueOrNull: T?,
    modifier: Modifier = Modifier,
    enter: EnterTransition = fadeIn() + expandIn(
        initialSize = { IntSize(it.width, 0) },
    ),
    exit: ExitTransition = shrinkOut(
        targetSize = { IntSize(it.width, 0) },
    ) + fadeOut(),
    content: @Composable (T) -> Unit,
) {
    AnimatedVisibility(
        modifier = modifier,
        visible = valueOrNull != null,
        enter = enter,
        exit = exit,
    ) {
        var value by remember {
            mutableStateOf<T?>(null)
        }
        valueOrNull?.let {
            value = it
        }
        content(value!!)
    }
}

@Composable
fun <T : Any> ExpandedIfNotEmptyForRow(
    valueOrNull: T?,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
) = ExpandedIfNotEmpty(
    valueOrNull = valueOrNull,
    modifier = modifier,
    enter = fadeIn() + scaleIn() + expandIn(
        initialSize = { IntSize(0, it.height) },
    ),
    exit = shrinkOut(
        targetSize = { IntSize(0, it.height) },
    ) + fadeOut() + scaleOut(),
    content = content,
)
