package com.artemchep.test.feature

import android.content.Intent
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.uiautomator.UiAutomatorTestScope
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.waitForStable

private const val PASSWORD_FIELD_RESOURCE_NAME = "field:password"
private const val GO_BUTTON_RESOURCE_NAME = "btn:go"

private const val ROOT_SCREEN_TIMEOUT_MS = 30_000L
private const val SCREEN_CONTENT_TIMEOUT_MS = 30_000L
private const val APP_VISIBILITY_TIMEOUT_MS = 10_000L
private const val NAVIGATION_TIMEOUT_MS = 10_000L
private const val DESTINATION_STABILITY_TIMEOUT_MS = 10_000L
private const val INPUT_METHOD_TIMEOUT_MS = 1_000L

@JvmInline
value class FeatureCore(
    val scope: UiAutomatorTestScope,
)

val UiAutomatorTestScope.coreFeature get() = FeatureCore(this)

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

    val resourceNames = screens
        .mapTo(mutableSetOf(), RootScreen::resourceName)
    scope.onElementOrNull(timeoutMs = ROOT_SCREEN_TIMEOUT_MS) {
        viewIdResourceName in resourceNames
    }
}

fun FeatureCore.ensureMainScreen(): UiObject2 = kotlin.run {
    val screen = waitForRootScreen(
        RootScreen.MAIN,
        RootScreen.UNLOCK,
        RootScreen.SETUP,
    )
    requireNotNull(screen) { missingRootScreenMessage() }

    when (screen.resourceName) {
        RootScreen.UNLOCK.resourceName(),
        RootScreen.SETUP.resourceName(),
            -> {
            // Create or unlock existing vault
            enterPasswordAndFindButton(password = "111111").click()

            // Wait till main screen is loaded
            val mainScreen = waitForRootScreen(
                RootScreen.MAIN,
            )
            requireNotNull(mainScreen) { missingRootScreenMessage(RootScreen.MAIN) }
        }

        else -> screen
    }.also { mainScreen ->
        mainScreen.waitForStable(requireStableScreenshot = false)
    }
}

fun FeatureCore.enterPasswordAndFindButton(
    password: String,
): UiObject2 {
    scope
        .onElement(timeoutMs = SCREEN_CONTENT_TIMEOUT_MS) {
            viewIdResourceName == PASSWORD_FIELD_RESOURCE_NAME
        }
        .setText(password)

    // A focused IME owns the active accessibility window on some emulators. Dismiss it before
    // waiting for the Compose navigation transition, but only when it is actually visible.
    val inputMethodVisible = scope.onElementOrNull(timeoutMs = INPUT_METHOD_TIMEOUT_MS) {
        window?.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD
    } != null
    if (inputMethodVisible) {
        scope.pressBack()
    }

    return scope
        .onElement(timeoutMs = SCREEN_CONTENT_TIMEOUT_MS) {
            viewIdResourceName == GO_BUTTON_RESOURCE_NAME && isEnabled && isClickable
        }
}

/**
 * Starts the default activity of the app, waiting for
 * it to become visible.
 */
fun FeatureCore.launchDefaultActivityAndWait(
    packageName: String,
) {
    scope.startApp(
        packageName = packageName,
        intentFlags = listOf(Intent.FLAG_ACTIVITY_CLEAR_TASK),
    )
    check(scope.waitForAppToBeVisible(packageName, APP_VISIBILITY_TIMEOUT_MS)) {
        "App '$packageName' did not become visible. ${windowSummary()}"
    }
}

fun FeatureCore.launchScreen(
    actionButtonResource: String,
) {
    scope
        .onElement(timeoutMs = NAVIGATION_TIMEOUT_MS) {
            viewIdResourceName == actionButtonResource && isEnabled && isClickable
        }
        .click()
    scope
        .onElement(timeoutMs = NAVIGATION_TIMEOUT_MS) {
            viewIdResourceName == actionButtonResource && isSelected
        }
    scope.waitForDestinationScreenToStabilize()
}

internal fun UiAutomatorTestScope.waitForDestinationScreenToStabilize() {
    waitForStableInActiveWindow(
        stableTimeoutMs = DESTINATION_STABILITY_TIMEOUT_MS,
        requireStableScreenshot = false,
    )
}

private fun FeatureCore.missingRootScreenMessage(
    vararg expected: RootScreen,
): String {
    val expectedText = if (expected.isNotEmpty()) {
        expected.joinToString(transform = RootScreen::resourceName)
    } else {
        RootScreen.entries.joinToString(transform = RootScreen::resourceName)
    }
    return "Could not find root screen [$expectedText]. ${windowSummary()}"
}

private fun FeatureCore.windowSummary(): String {
    val windows = scope.windows()
        .joinToString(prefix = "Windows=[", postfix = "]") { window ->
            "title=${window.title},type=${window.type}," +
                    "active=${window.isActive},focused=${window.isFocused},root=${window.root != null}"
        }
    return windows
}