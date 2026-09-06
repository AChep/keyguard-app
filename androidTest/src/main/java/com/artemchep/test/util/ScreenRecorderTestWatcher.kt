package com.artemchep.test.util

import android.app.UiAutomation
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.ResultsReporter
import androidx.test.uiautomator.UiDevice
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class ScreenRecorderTestWatcher : TestWatcher() {
    private companion object {
        val UNSAFE_FILENAME_CHARACTER = Regex("[^A-Za-z0-9_-]")

        const val TAG = "ScreenRecorderWatcher"

        const val BIT_RATE = 4_000_000
        const val SEGMENT_DURATION_SECONDS = 170
        const val RECORDER_START_TIMEOUT_MS = 5_000L
        const val RECORDER_STOP_TIMEOUT_MS = 10_000L
        const val RECORDER_POLL_INTERVAL_MS = 100L

        const val UI_AUTOMATION_CONNECTION_ATTEMPTS = 3
        const val UI_AUTOMATION_CONNECTION_RETRY_INTERVAL_MS = 500L

        const val SHELL_STREAM_COUNT = 3
        const val SHELL_STDOUT_INDEX = 0
        const val SHELL_STDIN_INDEX = 1
        const val SHELL_STDERR_INDEX = 2
    }

    private val instrumentation by lazy {
        InstrumentationRegistry.getInstrumentation()
    }

    private val uiAutomation: UiAutomation by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        acquireUiAutomation()
    }

    private val device by lazy {
        UiDevice.getInstance(instrumentation)
    }

    @Volatile
    private var recordingRequested = false
    private var recordingThread: Thread? = null

    override fun starting(description: Description) {
        // Establish the shared connection before the recorder and polling threads use it.
        executeShellCommand("true")

        val timestamp = SimpleDateFormat("HHmmss", Locale.US).format(Date())
        val testName =
            "${description.className}_${description.methodName}"
                .replace(UNSAFE_FILENAME_CHARACTER, "_")
        val filePrefix =
            Environment
                .getExternalStorageDirectory()
                .resolve("Movies")
                .resolve("${timestamp}_$testName")
                .absolutePath

        recordingRequested = true
        recordingThread =
            thread(name = "keyguard-screen-recorder") {
                var segment = 0
                while (recordingRequested) {
                    val filePath = "$filePrefix-part-${segment.toString().padStart(3, '0')}.mp4"
                    try {
                        executeShellCommand(
                            "screenrecord --time-limit $SEGMENT_DURATION_SECONDS " +
                                "--bit-rate $BIT_RATE $filePath",
                        )
                    } catch (error: Exception) {
                        Log.e(TAG, "Screen-recording segment failed: $filePath", error)
                        break
                    }
                    segment += 1
                }
            }

        if (!waitForRecorderProcess()) {
            stopRecording()
            error("screenrecord did not start within the timeout.")
        }
    }

    override fun failed(
        e: Throwable,
        description: Description,
    ) {
        reportFailureArtifacts(e, description)
    }

    override fun finished(description: Description) {
        stopRecording()
    }

    private fun stopRecording() {
        recordingRequested = false
        val thread = recordingThread ?: return
        stopRecorderProcess()
        thread.join(RECORDER_STOP_TIMEOUT_MS)
        if (thread.isAlive) {
            Log.w(TAG, "Screen-recording worker did not stop within the timeout.")
            thread.interrupt()
        }
        recordingThread = null
    }

    private fun waitForRecorderProcess(): Boolean {
        val deadline = SystemClock.uptimeMillis() + RECORDER_START_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            val pid =
                runCatching {
                    executeShellCommand("pidof screenrecord").trim()
                }.getOrDefault("")
            if (pid.isNotEmpty()) return true
            SystemClock.sleep(RECORDER_POLL_INTERVAL_MS)
        }
        return false
    }

    private fun stopRecorderProcess() {
        // SIGINT lets screenrecord finish writing the current MP4 before it exits.
        runCatching {
            executeShellCommand("pkill -2 screenrecord")
        }.onFailure { error ->
            Log.w(TAG, "Could not stop screenrecord gracefully.", error)
        }
    }

    private fun reportFailureArtifacts(
        error: Throwable,
        description: Description,
    ) {
        runCatching {
            val safeTestName =
                description.displayName.replace(UNSAFE_FILENAME_CHARACTER, "_")
            val reporter = ResultsReporter(description.displayName)
            val screenshot =
                reporter.addNewFile(
                    filename = "$safeTestName-failure.png",
                    title = "Failure screenshot",
                )
            device.takeScreenshot(screenshot)

            val diagnostics =
                reporter.addNewFile(
                    filename = "$safeTestName-window-diagnostics.txt",
                    title = "Window diagnostics",
                )
            diagnostics.writeText(
                buildString {
                    appendLine(error.stackTraceToString())
                    appendLine()
                    appendLine("===== dumpsys window windows =====")
                    appendLine(executeShellCommand("dumpsys window windows"))
                    appendLine("===== dumpsys accessibility =====")
                    appendLine(executeShellCommand("dumpsys accessibility"))
                },
            )
            reporter.reportToInstrumentation()
        }.onFailure { artifactError ->
            Log.e(TAG, "Could not report UI test failure artifacts.", artifactError)
        }
    }

    private fun executeShellCommand(command: String): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return ParcelFileDescriptor.AutoCloseInputStream(
                uiAutomation.executeShellCommand(command),
            ).bufferedReader(Charsets.UTF_8).use { reader -> reader.readText() }
        }

        val descriptors = uiAutomation.executeShellCommandRwe(command)
        if (descriptors.size != SHELL_STREAM_COUNT) {
            descriptors.forEach { descriptor -> runCatching { descriptor.close() } }
            error("Expected stdout, stdin, and stderr descriptors for: $command")
        }
        val stdoutReader = ShellStreamReader(descriptors[SHELL_STDOUT_INDEX], "stdout")
        val stderrReader = ShellStreamReader(descriptors[SHELL_STDERR_INDEX], "stderr")
        ParcelFileDescriptor.AutoCloseOutputStream(
            descriptors[SHELL_STDIN_INDEX],
        ).close()

        val stdoutResult = runCatching { stdoutReader.await() }
        val stderrResult = runCatching { stderrReader.await() }
        val stdout = stdoutResult.getOrThrow()
        val stderr = stderrResult.getOrThrow()
        if (stderr.isNotBlank()) {
            Log.w(TAG, "Shell command wrote to stderr: $command\n${stderr.trimEnd()}")
        }
        return stdout
    }

    private fun acquireUiAutomation(): UiAutomation {
        repeat(UI_AUTOMATION_CONNECTION_ATTEMPTS) { attempt ->
            val candidate: UiAutomation? = instrumentation.uiAutomation
            if (candidate != null) return candidate
            if (attempt < UI_AUTOMATION_CONNECTION_ATTEMPTS - 1) {
                SystemClock.sleep(UI_AUTOMATION_CONNECTION_RETRY_INTERVAL_MS)
            }
        }
        error("Could not connect to UiAutomation.")
    }

    private class ShellStreamReader(
        descriptor: ParcelFileDescriptor,
        streamName: String,
    ) {
        private var result: Result<String>? = null
        private val worker = thread(name = "keyguard-shell-$streamName") {
            result = runCatching {
                ParcelFileDescriptor.AutoCloseInputStream(descriptor)
                    .bufferedReader(Charsets.UTF_8)
                    .use { reader -> reader.readText() }
            }
        }

        fun await(): String {
            worker.join()
            return checkNotNull(result).getOrThrow()
        }
    }
}
