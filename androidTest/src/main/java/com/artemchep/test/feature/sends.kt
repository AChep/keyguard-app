package com.artemchep.test.feature

import androidx.test.uiautomator.UiDevice

@JvmInline
value class FeatureSends(
    val device: UiDevice,
)

val UiDevice.sendsFeature get() = FeatureSends(this)

fun FeatureSends.ensureSendsScreen() =
    device.coreFeature.launchScreen(
        actionButtonResource = "nav_bar:sends",
    )
