package com.artemchep.test.feature

import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until

@JvmInline
value class FeatureSends(
    val device: UiDevice,
)

val UiDevice.sendsFeature get() = FeatureSends(this)

// Launches the sends screen if the sends tab
// is enabled.
fun FeatureSends.trySendsScreen() = kotlin.run {
    val actionButtonResource = "nav_bar:sends"
    val actionButtonSelector = By.res(actionButtonResource)
    val actionButton = device.wait(
        Until.findObject(actionButtonSelector),
        5_000L,
    )
    if (actionButton == null) {
        return@run false
    }

    actionButton.click()
    device.waitForIdle()
    return@run true
}
