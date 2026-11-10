package com.artemchep.keyguard.feature.auth.keyguard.pairing

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.LaptopMac
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.PhoneIphone
import androidx.compose.material.icons.outlined.Watch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.vector.ImageVector
import com.artemchep.keyguard.platform.LocalAnimationFactor

// The pieces the linked devices screens build with: their motion clock and the icons of
// the devices.

/** `true` if the app may animate; it may not while the system turned animations off. */
@Composable
internal fun rememberMotionEnabled(): Boolean = LocalAnimationFactor > 0f

/**
 * Returns a clock that goes from 0 to 1 every [durationMillis], over and over; it stays
 * at 0 while the app may not animate.
 */
@Composable
internal fun rememberLoop(
    durationMillis: Int,
    enabled: Boolean = rememberMotionEnabled(),
): State<Float> {
    if (!enabled) {
        return remember { mutableFloatStateOf(0f) }
    }
    val transition = rememberInfiniteTransition(label = "loop")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "loop",
    )
}

/** Returns the icon of the [platform] a device record names, see `keyguardPlatformName`. */
internal fun keyguardPlatformIcon(platform: String): ImageVector = when (platform) {
    "android" -> Icons.Outlined.PhoneAndroid
    "wear" -> Icons.Outlined.Watch
    "ios" -> Icons.Outlined.PhoneIphone
    "macos" -> Icons.Outlined.LaptopMac
    "windows" -> Icons.Outlined.DesktopWindows
    // Material has no icon of its own for Linux.
    "linux",
    "desktop",
    -> Icons.Outlined.Computer

    else -> Icons.Outlined.Devices
}
