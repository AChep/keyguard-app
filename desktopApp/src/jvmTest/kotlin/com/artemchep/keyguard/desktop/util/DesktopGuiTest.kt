package com.artemchep.keyguard.desktop.util

import java.awt.GraphicsEnvironment
import org.junit.Assume.assumeFalse

/** The required GUI CI lane must fail, rather than silently skip, when its display is missing. */
internal fun requireDesktopTestDisplay() {
    val headless = GraphicsEnvironment.isHeadless()
    check(System.getenv("KEYGUARD_REQUIRE_GUI_TESTS") != "1" || !headless) {
        "KEYGUARD_REQUIRE_GUI_TESTS requires a working desktop display"
    }
    assumeFalse("Native window tests require a display", headless)
}
