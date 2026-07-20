package com.artemchep.test.feature

import androidx.test.uiautomator.UiAutomatorTestScope

@JvmInline
value class FeatureGenerator(
    val scope: UiAutomatorTestScope,
)

val UiAutomatorTestScope.generatorFeature get() = FeatureGenerator(this)

fun FeatureGenerator.ensureGeneratorScreen() =
    scope.coreFeature.launchScreen(
        actionButtonResource = "nav_bar:generator",
    )
