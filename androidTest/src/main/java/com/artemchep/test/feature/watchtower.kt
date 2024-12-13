package com.artemchep.test.feature

import androidx.test.uiautomator.UiDevice

@JvmInline
value class FeatureWatchtower(
    val device: UiDevice,
)

val UiDevice.watchtowerFeature get() = FeatureWatchtower(this)

fun FeatureWatchtower.ensureWatchtowerScreen() =
    device.coreFeature.launchScreen(
        actionButtonResource = "nav_bar:watchtower",
    )
