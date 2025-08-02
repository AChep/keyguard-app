package com.artemchep.keyguard.ui.surface

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.theme.isDark

data class SurfaceElevation(
    val from: Float,
    val to: Float,
)

val LocalSurfaceElevation = staticCompositionLocalOf {
    SurfaceElevation(
        from = 0f,
        to = 1f,
    )
}

/**
 * Returns a surface color that should be used for a given
 * surface elevation.
 */
val SurfaceElevation.color: Color
    @Composable
    @ReadOnlyComposable
    get() = surfaceElevationColor(to)

@Composable
@ReadOnlyComposable
fun surfaceElevationColor(elevation: Float): Color {
    when (elevation) {
        1.0f -> return MaterialTheme.colorScheme.background
        0.75f -> return MaterialTheme.colorScheme.surfaceContainerLow
        // Makes sense on practice after you have seen
        // how these containers are positioned. If we just
        // use the code below, then the difference between these
        // containers becomes too subtle.
        0.625f -> return MaterialTheme.colorScheme.surfaceContainer
        0.5f -> return MaterialTheme.colorScheme.surfaceContainer
        0.25f -> return MaterialTheme.colorScheme.surfaceContainerHigh
    }

    val min = MaterialTheme.colorScheme.surfaceContainerHigh
    val max = MaterialTheme.colorScheme.background
    return max
        .combineAlpha(elevation)
        .compositeOver(min)
}

@Composable
@ReadOnlyComposable
fun surfaceNextGroupColorToElevationColor(elevation: Float): Color {
    return if (MaterialTheme.colorScheme.isDark) {
        surfaceNextGroupColorToElevationColorDarkTheme(elevation)
    } else {
        surfaceNextGroupColorToElevationColorLightTheme(elevation)
    }
}

@Composable
@ReadOnlyComposable
fun surfaceNextGroupColorToElevationColorLightTheme(elevation: Float): Color {
    when (elevation) {
        1.0f -> return MaterialTheme.colorScheme.surfaceContainerLow
        0.75f -> return MaterialTheme.colorScheme.background
        // Makes sense on practice after you have seen
        // how these containers are positioned. If we just
        // use the code below, then the difference between these
        // containers becomes too subtle.
        0.625f -> return MaterialTheme.colorScheme.surfaceContainerLow
        0.5f -> return MaterialTheme.colorScheme.surfaceContainerLow
        0.25f -> return MaterialTheme.colorScheme.surfaceContainer
    }
    return surfaceElevationColor(elevation.plus(0.25f).coerceIn(0f, 1f))
}

@Composable
@ReadOnlyComposable
fun surfaceNextGroupColorToElevationColorDarkTheme(elevation: Float): Color {
    when (elevation) {
        1.0f -> return MaterialTheme.colorScheme.surfaceContainerLow
        0.75f -> return MaterialTheme.colorScheme.surfaceContainer
        // Makes sense on practice after you have seen
        // how these containers are positioned. If we just
        // use the code below, then the difference between these
        // containers becomes too subtle.
        0.625f -> return MaterialTheme.colorScheme.surfaceContainerHigh
        0.5f -> return MaterialTheme.colorScheme.surfaceContainerHigh
        0.25f -> return MaterialTheme.colorScheme.surfaceContainerHighest
    }
    return surfaceElevationColor(elevation.minus(0.25f).coerceIn(0f, 1f))
}

val SurfaceElevation.width get() = to - from

fun SurfaceElevation.splitLow(): SurfaceElevation {
    val to = to - width / 2f
    return copy(to = to)
}

fun SurfaceElevation.splitHigh(): SurfaceElevation {
    val from = from + width / 2f
    return copy(from = from)
}
