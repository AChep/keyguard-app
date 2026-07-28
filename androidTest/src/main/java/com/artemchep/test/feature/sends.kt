package com.artemchep.test.feature

import androidx.test.uiautomator.UiAutomatorTestScope

@JvmInline
value class FeatureSends(
    val scope: UiAutomatorTestScope,
)

val UiAutomatorTestScope.sendsFeature get() = FeatureSends(this)

// Launches the sends screen if the sends tab
// is enabled.
fun FeatureSends.trySendsScreen() = kotlin.run {
    val actionButtonResource = "nav_bar:sends"
    val actionButton = scope.onElementOrNull(timeoutMs = 5_000L) {
        viewIdResourceName == actionButtonResource && isEnabled && isClickable
    }
    if (actionButton == null) {
        return@run false
    }

    actionButton.click()
    scope.onElement(timeoutMs = 10_000L) {
        viewIdResourceName == actionButtonResource && isSelected
    }
    scope.waitForDestinationScreenToStabilize()
    return@run true
}
