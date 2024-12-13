package com.artemchep.test.feature

import androidx.test.uiautomator.UiDevice

@JvmInline
value class FeatureSettings(
    val device: UiDevice,
)

val UiDevice.settingsFeature get() = FeatureSettings(this)

fun FeatureSettings.ensureSettingsScreen() =
    device.coreFeature.launchScreen(
        actionButtonResource = "nav_bar:settings",
    )
