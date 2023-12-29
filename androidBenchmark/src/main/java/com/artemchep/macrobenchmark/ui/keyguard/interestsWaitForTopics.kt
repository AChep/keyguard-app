package com.artemchep.macrobenchmark.ui.keyguard

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import java.util.regex.Pattern

fun MacrobenchmarkScope.waitForMainScreen() = kotlin.run {
    val search = kotlin.run {
        val pattern = Pattern.compile("nav:(setup|unlock|main)")
        val selector = By.res(pattern)
        Until.findObject(selector)
    }
    device.wait(search, 30_000)
}

fun MacrobenchmarkScope.createVaultAndWait() = kotlin.run {
    val screen = waitForMainScreen()
    when (screen.resourceName) {
        "nav:setup",
        "nav:unlock",
        -> {
            // Create or unlock existing vault
            val password = screen
                .findObject(By.res("field:password"))
            password.text = "111111"
            val btn = screen
                .findObject(By.res("btn:go"))
            btn.wait(Until.clickable(true), 1000L)
            btn.click()
            // wait till main screen is loaded
            device.wait(Until.findObject(By.res("nav:main")), 30_000)
        }
        else -> screen
    }
}
