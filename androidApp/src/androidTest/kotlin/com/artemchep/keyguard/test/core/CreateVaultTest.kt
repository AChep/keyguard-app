package com.artemchep.keyguard.test.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.artemchep.keyguard.test.BaseTest
import com.artemchep.keyguard.test.PACKAGE_NAME
import com.artemchep.test.feature.coreFeature
import com.artemchep.test.feature.ensureMainScreen
import com.artemchep.test.feature.launchDefaultActivityAndWait
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
class CreateVaultTest : BaseTest() {
    @Test
    @Throws(Exception::class)
    fun createVaultTest() {
        device.coreFeature.launchDefaultActivityAndWait(PACKAGE_NAME)
        device.coreFeature.ensureMainScreen()
    }
}
