package com.artemchep.test.feature

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.util.regex.Pattern

@JvmInline
value class FeatureCore(
    val device: UiDevice,
)

val UiDevice.coreFeature get() = FeatureCore(this)

enum class RootScreen(
    val res: String,
) {
    SETUP("setup"),
    UNLOCK("unlock"),
    MAIN("main");

    companion object {
        const val RES_PREFIX = "nav:"
    }
}

fun RootScreen.resourceName() = RootScreen.RES_PREFIX + res

fun FeatureCore.waitForRootScreen(
    vararg screens: RootScreen,
): UiObject2? = kotlin.run {
    require(screens.isNotEmpty()) {
        "You must provide at least one screen to wait for!"
    }

    val selector = kotlin.run {
        val variants = screens
            .joinToString(separator = "|") { it.res }
        val pattern = Pattern.compile("nav:($variants)")
        val selector = By.res(pattern)
        Until.findObject(selector)
    }
    device.wait(selector, 30_000)
}

fun FeatureCore.ensureMainScreen(): UiObject2 = kotlin.run {
    val screen = waitForRootScreen(
        RootScreen.MAIN,
        RootScreen.UNLOCK,
        RootScreen.SETUP,
    )
    requireNotNull(screen) {
        "Could not find root screen! " +
                "Is the app really open?"
    }

    when (screen.resourceName) {
        RootScreen.UNLOCK.resourceName(),
        RootScreen.SETUP.resourceName(),
            -> {
            // Create or unlock existing vault
            val password = screen
                .findObject(By.res("field:password"))
            password.text = "111111"
            val btn = screen
                .findObject(By.res("btn:go"))
            btn.wait(Until.clickable(true), 3000L)
            btn.click()

            // Wait till main screen is loaded
            val mainScreen = waitForRootScreen(
                RootScreen.MAIN,
            )
            requireNotNull(mainScreen)
        }

        else -> screen
    }
}

/**
 * Starts the default activity of the app, waiting for
 * it to become visible.
 */
fun FeatureCore.launchDefaultActivityAndWait(
    packageName: String,
) {
    // Launch the app
    val context = ApplicationProvider.getApplicationContext<Context>()
    val intent = context.packageManager.getLaunchIntentForPackage(packageName)?.apply {
        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }
    context.startActivity(intent)

    // Wait for the app to appear
    device.wait(
        Until.hasObject(By.pkg(packageName).depth(0)),
        10_000,
    )
}
