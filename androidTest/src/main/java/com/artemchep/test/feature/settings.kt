package com.artemchep.test.feature

import androidx.test.uiautomator.UiAutomatorTestScope

@JvmInline
value class FeatureSettings(
    val scope: UiAutomatorTestScope,
)

val UiAutomatorTestScope.settingsFeature get() = FeatureSettings(this)

fun FeatureSettings.ensureSettingsScreen() =
    scope.coreFeature.launchScreen(
        actionButtonResource = "nav_bar:settings",
    )
