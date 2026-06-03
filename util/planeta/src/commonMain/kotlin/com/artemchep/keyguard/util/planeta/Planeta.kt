package com.artemchep.keyguard.util.planeta

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import com.artemchep.keyguard.util.planeta.render.drawPlanetaScene

@Composable
fun Planeta(
    fingerprint: String,
    modifier: Modifier = Modifier,
    options: PlanetaOptions = PlanetaOptions(),
) {
    val spec = rememberPlanetaSpec(fingerprint)
    Planeta(
        spec = spec,
        modifier = modifier,
        options = options,
    )
}

@Composable
fun Planeta(
    spec: PlanetaSpec,
    modifier: Modifier = Modifier,
    options: PlanetaOptions = PlanetaOptions(),
) {
    var frameNanos by remember(spec.seed) {
        mutableLongStateOf(0L)
    }

    LaunchedEffect(spec.seed, options.animationSpeed) {
        val start = withFrameNanos { it }
        while (true) {
            frameNanos = withFrameNanos { frame ->
                ((frame - start) * options.animationSpeed).toLong()
            }
        }
    }

    Box(
        modifier = modifier
            .aspectRatio(1f),
    ) {
        Canvas(
            modifier = Modifier.fillMaxSize(),
        ) {
            val seconds = frameNanos / 1_000_000_000f
            drawPlanetaScene(
                spec = spec,
                options = options,
                seconds = seconds,
            )
        }
    }
}

@Composable
fun rememberPlanetaSpec(fingerprint: String): PlanetaSpec =
    remember(fingerprint) {
        PlanetaSpec.fromFingerprint(fingerprint)
    }
