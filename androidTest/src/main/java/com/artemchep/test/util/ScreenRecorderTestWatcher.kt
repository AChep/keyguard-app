package com.artemchep.test.util

import android.os.Environment
import androidx.annotation.WorkerThread
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.AssumptionViolatedException
import org.junit.Rule
import org.junit.rules.TestName
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date

class ScreenRecorderTestWatcher : TestWatcher() {
    @JvmField
    @Rule
    var testName = TestName()

    override fun starting(description: Description) {
        super.starting(description)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)

        val date = kotlin.run {
            val simpleDateFormat = SimpleDateFormat("HHmmss")
            simpleDateFormat.format(Date())
        }
        val fileName = kotlin.run {
            val className = description.className
                .removePrefix("com.artemchep.keyguard.")
                .replace(".", "_")
            val methodName = description.methodName
            "${date}_${className}_$methodName.mp4"
        }
        val filePath = Environment.getExternalStorageDirectory()
            .resolve("Movies/")
            .resolve(fileName)
            .absolutePath
        Thread {
            device.startScreenRecord(filePath)
        }.start()
    }

    override fun skipped(
        e: AssumptionViolatedException,
        description: Description,
    ) {
        super.skipped(e, description)
    }

    override fun finished(description: Description) {
        super.finished(description)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        Thread {
            device.stopScreenRecord()
        }.start()
    }
}

@WorkerThread
fun UiDevice.startScreenRecord(fileName: String) {
    kotlin.runCatching {
        executeShellCommand("rm $fileName")
    }

    try {
        executeShellCommand("screenrecord --bit-rate 4000000 $fileName")
    } catch (e: IOException) {
        e.printStackTrace()
    }
}

@WorkerThread
fun UiDevice.stopScreenRecord(
) {
    // Send Ctrl+C command to the process. This stop the screen
    // recorder gracefully.
    kotlin.runCatching {
        executeShellCommand("pkill -2 screenrecord")
    }
}
