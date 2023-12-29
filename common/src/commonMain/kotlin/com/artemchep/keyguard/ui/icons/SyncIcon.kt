package com.artemchep.keyguard.ui.icons

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import kotlin.math.absoluteValue

@Composable
fun SyncIcon(
    modifier: Modifier = Modifier,
    rotating: Boolean,
) {
    AnimatedRotation(
        modifier = modifier,
        rotating = rotating,
    ) {
        Icon(
            imageVector = Icons.Outlined.Sync,
            contentDescription = null,
        )
    }
}

@Composable
fun AnimatedRotation(
    modifier: Modifier = Modifier,
    rotating: Boolean,
    content: @Composable () -> Unit,
) {
    var lastRotation by remember { mutableStateOf(0f) }
    var targetRotation by remember { mutableStateOf(0f) }

    val rotation by animateFloatAsState(
        targetValue = targetRotation,
        animationSpec = spring(
            stiffness = Spring.StiffnessVeryLow,
        ),
        finishedListener = {
            lastRotation = it
        },
    )

    val dr = (lastRotation - targetRotation).absoluteValue
    if (dr < 0.1f && rotating) {
        // Increase the target rotation for the next
        // full cycle.
        SideEffect {
            targetRotation += 360f
        }
    }

    Box(
        modifier = modifier
            .rotate(rotation),
    ) {
        content()
    }
}
