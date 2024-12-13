package com.artemchep.macrobenchmark.baselineprofile

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import com.artemchep.macrobenchmark.PACKAGE_NAME
import com.artemchep.test.feature.coreFeature
import com.artemchep.test.feature.ensureGeneratorScreen
import com.artemchep.test.feature.ensureMainScreen
import com.artemchep.test.feature.ensureSendsScreen
import com.artemchep.test.feature.ensureSettingsScreen
import com.artemchep.test.feature.ensureWatchtowerScreen
import com.artemchep.test.feature.generatorFeature
import com.artemchep.test.feature.sendsFeature
import com.artemchep.test.feature.settingsFeature
import com.artemchep.test.feature.watchtowerFeature
import com.artemchep.test.util.ScreenRecorderTestWatcher
import com.artemchep.test.util.wait
import org.junit.Rule
import org.junit.Test

/**
 * Generates a baseline profile which
 * can be copied to `app/src/main/baseline-prof.txt`.
 */
@RequiresApi(Build.VERSION_CODES.P)
class BaselineProfileGenerator {
    @get:Rule
    val screenRecorder = ScreenRecorderTestWatcher()

    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun generateBaselineProfile() = baselineProfileRule.collect(
        packageName = PACKAGE_NAME,
    ) {
        // This block defines the app's critical user journey. Here we are interested in
        // optimizing for app startup. But you can also navigate and scroll
        // through your most important UI.

        pressHome()
        startActivityAndWait()

        device.coreFeature.ensureMainScreen()
        device.waitForIdle()

        walkThroughSends()
        walkThroughGenerator()
        walkThroughWatchtower()
        walkThroughSettings()
    }

    private fun MacrobenchmarkScope.walkThroughSends() {
        device.sendsFeature.ensureSendsScreen()
        device.wait(2_000L)
    }

    private fun MacrobenchmarkScope.walkThroughGenerator() {
        device.generatorFeature.ensureGeneratorScreen()
        device.wait(2_000L)
    }

    private fun MacrobenchmarkScope.walkThroughWatchtower() {
        device.watchtowerFeature.ensureWatchtowerScreen()
        device.wait(5_000L)
    }

    private fun MacrobenchmarkScope.walkThroughSettings() {
        device.settingsFeature.ensureSettingsScreen()
        device.wait(2_000L)
    }

    @Test
    fun generateStartupProfile() = baselineProfileRule.collect(
        packageName = PACKAGE_NAME,
        includeInStartupProfile = true,
    ) {
        pressHome()
        startActivityAndWait()

        device.coreFeature.ensureMainScreen()
        device.waitForIdle()
    }
}
