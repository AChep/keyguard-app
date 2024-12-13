package com.artemchep.test.feature

import androidx.test.uiautomator.UiDevice

@JvmInline
value class FeatureGenerator(
    val device: UiDevice,
)

val UiDevice.generatorFeature get() = FeatureGenerator(this)

fun FeatureGenerator.ensureGeneratorScreen() =
    device.coreFeature.launchScreen(
        actionButtonResource = "nav_bar:generator",
    )
