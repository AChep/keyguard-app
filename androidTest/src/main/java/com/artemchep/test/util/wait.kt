package com.artemchep.test.util

import androidx.test.uiautomator.UiDevice

/** Always waits for a given time */
fun UiDevice.wait(ms: Long) {
    wait(
        { null },
        ms,
    )
}
