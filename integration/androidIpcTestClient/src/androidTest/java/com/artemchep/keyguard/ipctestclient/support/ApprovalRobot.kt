package com.artemchep.keyguard.ipctestclient.support

import android.app.PendingIntent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.artemchep.keyguard.ipctestclient.IpcTestClientActivity
import com.artemchep.keyguard.ipctestclient.ipc.ApprovalHost
import com.artemchep.keyguard.ipctestclient.ipc.ApprovalOutcome
import org.junit.Assert.assertNotNull
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Drives Keyguard's approval dialog with UI Automator.
 *
 * The button labels are the English `ipc_approval_approve` and
 * `ipc_approval_deny` strings, so the device has to run in English. Every
 * timeout dumps the window hierarchy and a screenshot next to the test results:
 * a bare "the object was null" tells you nothing about which dialog, if any, was
 * on screen.
 */
class ApprovalRobot(
    private val clientPackage: String,
    private val keyguardPackage: String,
) {
    enum class Action { APPROVE, APPROVE_ALL_CANDIDATES, DENY, DISMISS }

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice = UiDevice.getInstance(instrumentation)

    /** An [ApprovalHost] that answers every prompt the same way. */
    fun host(action: Action = Action.APPROVE): ApprovalHost = object : ApprovalHost {
        override fun launch(
            pendingIntent: PendingIntent,
            timeoutMs: Long,
        ): ApprovalOutcome = perform(pendingIntent, action, timeoutMs)
    }

    fun perform(
        pendingIntent: PendingIntent,
        action: Action,
        timeoutMs: Long = UI_TIMEOUT_MS,
    ): ApprovalOutcome {
        IpcTestClientActivity.results.clear()
        ActivityScenario.launch(IpcTestClientActivity::class.java).use { scenario ->
            scenario.onActivity { it.launchApproval(pendingIntent) }
            awaitPrompt()
            when (action) {
                Action.APPROVE -> approve(selectAll = false)
                Action.APPROVE_ALL_CANDIDATES -> approve(selectAll = true)
                Action.DENY -> click("Deny")
                Action.DISMISS -> device.pressBack()
            }
            val result = IpcTestClientActivity.results.poll(timeoutMs, TimeUnit.MILLISECONDS)
                ?: dumpAndFail("no-approval-result", "The approval activity never returned")
            return ApprovalOutcome(result.resultCode, result.data)
        }
    }

    /**
     * Waits for Keyguard's dialog and asserts it attributes the request to this
     * client rather than to whichever app happens to be in the foreground.
     */
    fun awaitPrompt() {
        val window = device.wait(
            Until.findObject(By.pkg(keyguardPackage).depth(0)),
            UI_TIMEOUT_MS,
        )
        if (window == null) {
            dumpAndFail("no-approval-window", "No $keyguardPackage window appeared")
        }
        assertNotNull(
            "The approval dialog does not name $clientPackage",
            device.wait(Until.findObject(By.text(clientPackage)), UI_TIMEOUT_MS),
        )
    }

    private fun approve(selectAll: Boolean) {
        val approve = find("Approve")
        if (!approve.isEnabled || selectAll) {
            val candidates = device.wait(
                Until.findObjects(By.checkable(true)),
                UI_TIMEOUT_MS,
            ).orEmpty()
            if (candidates.isEmpty()) {
                dumpAndFail("no-approval-candidates", "No selectable key in the dialog")
            }
            if (selectAll) candidates.forEach { it.click() } else candidates.first().click()
        }
        find("Approve").click()
    }

    private fun click(label: String) = find(label).click()

    private fun find(label: String): UiObject2 =
        device.wait(Until.findObject(By.text(label)), UI_TIMEOUT_MS)
            ?: dumpAndFail("no-$label", "No \"$label\" control in the approval dialog")

    fun dump(tag: String) {
        val directory = outputDirectory() ?: return
        runCatching { device.dumpWindowHierarchy(File(directory, "$tag-hierarchy.xml")) }
        runCatching { device.takeScreenshot(File(directory, "$tag-screen.png")) }
    }

    private fun dumpAndFail(tag: String, message: String): Nothing {
        dump(tag)
        failWithExchangeLog(message)
    }

    private fun outputDirectory(): File? {
        val argument = InstrumentationRegistry.getArguments()
            .getString("additionalTestOutputDir")
        val directory = argument?.let(::File)
            ?: instrumentation.targetContext.externalCacheDir
            ?: instrumentation.targetContext.cacheDir
        return directory.takeIf { it.exists() || it.mkdirs() }
    }

    companion object {
        const val UI_TIMEOUT_MS = 30_000L
    }
}
