package com.artemchep.macrobenchmark

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice

private const val ENABLE_SCREEN_RECORDING_ACTION =
    "com.artemchep.keyguard.benchmark.ENABLE_SCREEN_RECORDING"

private const val SCREEN_RECORDING_RECEIVER =
    "com.artemchep.keyguard.benchmark.BenchmarkScreenRecordingReceiver"

/**
 * Convenience parameter to use proper package name
 * with regards to build type and build flavor.
 */
val PACKAGE_NAME = StringBuilder("com.artemchep.keyguard").apply {
    val hasSuffix = when (BuildConfig.BUILD_TYPE) {
        "debug" -> true
        else -> false
    }
    if (hasSuffix) {
        append(".${BuildConfig.BUILD_TYPE}")
    }
}.toString()

fun enableBenchmarkScreenRecording() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val device = UiDevice.getInstance(instrumentation)
    val result = device.executeShellCommand(
        "am broadcast -W " +
            "-a $ENABLE_SCREEN_RECORDING_ACTION " +
            "-n $PACKAGE_NAME/$SCREEN_RECORDING_RECEIVER",
    )
    require("result=-1" in result && "data=\"limited\"" in result) {
        "Could not enable benchmark screen recording: $result"
    }
    device.executeShellCommand("am force-stop $PACKAGE_NAME")
}
