package com.artemchep.keyguard.util.planeta.generation

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.artemchep.keyguard.util.planeta.PlanetaPalette
import com.artemchep.keyguard.util.planeta.PlanetaSurface
import com.artemchep.keyguard.util.planeta.internal.PlanetaRandom

internal fun paletteFor(
    surface: PlanetaSurface,
    random: PlanetaRandom,
): PlanetaPalette {
    val palette = when (surface) {
        PlanetaSurface.Terrestrial -> random.pick(
            PlanetaPalette(
                shadow = Color(0xFF152119),
                base = Color(0xFF44745A),
                highlight = Color(0xFF9BD08D),
                accent = Color(0xFFB28F55),
                atmosphere = Color(0xFF83D7FF),
                cloud = Color(0xFFEAF7FF),
            ),
            PlanetaPalette(
                shadow = Color(0xFF1E2234),
                base = Color(0xFF436C88),
                highlight = Color(0xFF7EBCB3),
                accent = Color(0xFFCEB373),
                atmosphere = Color(0xFFB4E4FF),
                cloud = Color(0xFFF2F8FF),
            ),
        )

        PlanetaSurface.Oceanic -> random.pick(
            PlanetaPalette(
                shadow = Color(0xFF071B35),
                base = Color(0xFF0D5C8C),
                highlight = Color(0xFF37BBD0),
                accent = Color(0xFF73D1A7),
                atmosphere = Color(0xFF79DCFF),
                cloud = Color(0xFFE3FAFF),
            ),
            PlanetaPalette(
                shadow = Color(0xFF071B29),
                base = Color(0xFF0D6A6E),
                highlight = Color(0xFF45D7C7),
                accent = Color(0xFFFFD48A),
                atmosphere = Color(0xFF74F2E5),
                cloud = Color(0xFFE8FFFB),
            ),
        )

        PlanetaSurface.GasGiant -> random.pick(
            PlanetaPalette(
                shadow = Color(0xFF2D1B2F),
                base = Color(0xFF9D6048),
                highlight = Color(0xFFF2B56F),
                accent = Color(0xFF6E9BCB),
                atmosphere = Color(0xFFFFC77D),
                cloud = Color(0xFFFFE2B7),
            ),
            PlanetaPalette(
                shadow = Color(0xFF1A1E3D),
                base = Color(0xFF6569A9),
                highlight = Color(0xFFB3A8E8),
                accent = Color(0xFFD8C27B),
                atmosphere = Color(0xFFB9B7FF),
                cloud = Color(0xFFEDEAFF),
            ),
        )

        PlanetaSurface.Ice -> random.pick(
            PlanetaPalette(
                shadow = Color(0xFF172339),
                base = Color(0xFF88A9C7),
                highlight = Color(0xFFE9F8FF),
                accent = Color(0xFF537DA0),
                atmosphere = Color(0xFFCDEFFF),
                cloud = Color(0xFFFFFFFF),
            ),
            PlanetaPalette(
                shadow = Color(0xFF263044),
                base = Color(0xFFA9B6BF),
                highlight = Color(0xFFF7F3E8),
                accent = Color(0xFF6A8FA0),
                atmosphere = Color(0xFFE5FBFF),
                cloud = Color(0xFFFFFFFF),
            ),
        )

        PlanetaSurface.Volcanic -> random.pick(
            PlanetaPalette(
                shadow = Color(0xFF170D0B),
                base = Color(0xFF57342D),
                highlight = Color(0xFFB8593A),
                accent = Color(0xFFFFB14F),
                atmosphere = Color(0xFFFF7F4F),
                cloud = Color(0xFFFFD0A8),
            ),
            PlanetaPalette(
                shadow = Color(0xFF190F16),
                base = Color(0xFF55334C),
                highlight = Color(0xFF9B5268),
                accent = Color(0xFFFF6D47),
                atmosphere = Color(0xFFFF8A5E),
                cloud = Color(0xFFFFD6B6),
            ),
        )
    }

    val tint = random.nextFloat(-0.08f, 0.1f)
    return palette.copy(
        shadow = palette.shadow.shift(tint * 0.6f),
        base = palette.base.shift(tint),
        highlight = palette.highlight.shift(tint),
        accent = palette.accent.shift(tint),
        atmosphere = palette.atmosphere.shift(tint * 0.5f),
    )
}

internal fun Color.shift(amount: Float): Color =
    if (amount >= 0f) {
        lerp(this, Color.White, amount.coerceIn(0f, 1f))
    } else {
        lerp(this, Color.Black, (-amount).coerceIn(0f, 1f))
    }
