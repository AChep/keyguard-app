package com.artemchep.macrobenchmark.baselineprofile

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.benchmark.macro.junit4.BaselineProfileRule
import com.artemchep.macrobenchmark.PACKAGE_NAME
import com.artemchep.test.feature.coreFeature
import com.artemchep.test.feature.ensureMainScreen
import org.junit.Rule
import org.junit.Test

/**
 * Generates a baseline profile which
 * can be copied to `app/src/main/baseline-prof.txt`.
 */
@RequiresApi(Build.VERSION_CODES.P)
class BaselineProfileGenerator {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun generate() = baselineProfileRule.collect(
        packageName = PACKAGE_NAME,
        includeInStartupProfile = true,
    ) {
        // This block defines the app's critical user journey. Here we are interested in
        // optimizing for app startup. But you can also navigate and scroll
        // through your most important UI.

        pressHome()
        startActivityAndWait()

        device.coreFeature.ensureMainScreen()
    }
}
