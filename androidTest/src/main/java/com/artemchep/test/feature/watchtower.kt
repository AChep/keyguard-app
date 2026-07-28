package com.artemchep.test.feature

import androidx.test.uiautomator.UiAutomatorTestScope

@JvmInline
value class FeatureWatchtower(
    val scope: UiAutomatorTestScope,
)

val UiAutomatorTestScope.watchtowerFeature get() = FeatureWatchtower(this)

fun FeatureWatchtower.ensureWatchtowerScreen() =
    scope.coreFeature.launchScreen(
        actionButtonResource = "nav_bar:watchtower",
    )
