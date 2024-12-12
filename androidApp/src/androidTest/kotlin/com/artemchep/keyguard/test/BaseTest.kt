package com.artemchep.keyguard.test

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.artemchep.test.util.ScreenRecorderTestWatcher
import org.junit.Before
import org.junit.Rule

abstract class BaseTest {
    @get:Rule
    val screenRecorder = ScreenRecorderTestWatcher()

    lateinit var device: UiDevice

    lateinit var context: Context

    @Before
    fun init() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()

        device = UiDevice.getInstance(instrumentation)
        context = ApplicationProvider.getApplicationContext<Context>()
    }
}
