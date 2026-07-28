package com.artemchep.keyguard.test

import com.artemchep.test.util.ScreenRecorderTestWatcher
import org.junit.Rule

abstract class BaseTest {
    @get:Rule
    val screenRecorder = ScreenRecorderTestWatcher()
}
