package com.artemchep.keyguard.ui.theme.monet

import dev.kdrag0n.colorkt.Color
import dev.kdrag0n.colorkt.cam.Zcam
import dev.kdrag0n.colorkt.data.Illuminants
import dev.kdrag0n.colorkt.rgb.Srgb
import dev.kdrag0n.colorkt.tristimulus.CieXyzAbs.Companion.toAbs
import dev.kdrag0n.colorkt.ucs.lab.CieLab

interface ColorSchemeFactory {
    fun getColor(color: Color): ColorScheme

    companion object {
        fun getFactory(
            // For all models
            chromaFactor: Double,
            accurateShades: Boolean,
            useComplementColor: Boolean,
            complementColorHex: Int,
            // ZCAM only
            whiteLuminance: Double,
            useLinearLightness: Boolean,
        ) = object : ColorSchemeFactory {
            private val cond = createZcamViewingConditions(whiteLuminance)
            private val complementColor = if (!useComplementColor || complementColorHex == 0) {
                null
            } else {
                Srgb(complementColorHex)
            }

            override fun getColor(color: Color) = DynamicColorScheme(
                targets = MaterialYouTargets(
                    chromaFactor = chromaFactor,
                    useLinearLightness = useLinearLightness,
                    cond = cond,
                ),
                seedColor = color,
                chromaFactor = chromaFactor,
                cond = cond,
                accurateShades = accurateShades,
                complementColor = complementColor,
            )
        }

        fun getFactory() = getFactory(
            chromaFactor = 1.0,
            accurateShades = true,
            useComplementColor = false,
            complementColorHex = 0,
            whiteLuminance = 425.0,
            useLinearLightness = false,
        )

        fun createZcamViewingConditions(whiteLuminance: Double) = Zcam.ViewingConditions(
            surroundFactor = Zcam.ViewingConditions.SURROUND_AVERAGE,
            // sRGB
            adaptingLuminance = 0.4 * whiteLuminance,
            // Gray world
            backgroundLuminance = CieLab(
                L = 50.0,
                a = 0.0,
                b = 0.0,
            ).toXyz().y * whiteLuminance,
            referenceWhite = Illuminants.D65.toAbs(whiteLuminance),
        )
    }
}
