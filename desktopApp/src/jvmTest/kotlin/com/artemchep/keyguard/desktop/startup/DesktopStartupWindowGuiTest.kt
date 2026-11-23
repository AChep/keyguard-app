package com.artemchep.keyguard.desktop.startup

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.launchApplication
import com.artemchep.keyguard.desktop.util.requireDesktopTestDisplay
import com.artemchep.keyguard.presentation.startup.DesktopLaunchReason
import java.awt.Window as AwtWindow
import java.awt.event.WindowEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.jetbrains.skiko.MainUIDispatcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Real Compose application/window, using the production startup composition gate. No vault is loaded. */
class DesktopStartupWindowGuiTest {
    @Test
    fun loginWaitsForPreferencesThenReopensAndClosesWithoutReconstructingMainContent() = guiTest {
        val fixture = Fixture(DesktopLaunchReason.LOGIN)
        val app = fixture.start(this)
        try {
            val state = fixture.ready.await()
            withContext(MainUIDispatcher) { assertFalse(state.created); assertEquals(0, fixture.constructions) }
            fixture.preference.subscriptionCount.first { it == 1 }
            fixture.preference.emit(true)
            fixture.preference.subscriptionCount.first { it == 0 }
            withContext(MainUIDispatcher) {
                assertFalse(state.created)
                assertEquals(0, fixture.constructions)
                state.open() // Same callback used by tray, launcher activation and Dock reopen.
            }
            val window = fixture.window.await()
            withContext(MainUIDispatcher) {
                snapshotFlow { state.visible }.first { it }
                window.dispatchEvent(WindowEvent(window, WindowEvent.WINDOW_CLOSING))
                snapshotFlow { state.visible }.first { !it }
                assertTrue(state.created)
                state.open()
                snapshotFlow { state.visible }.first { it }
                assertEquals(1, fixture.constructions)
                fixture.closeToTray.value = false
            }
            fixture.trayDisabled.await()
            withContext(MainUIDispatcher) { window.dispatchEvent(WindowEvent(window, WindowEvent.WINDOW_CLOSING)) }
            app.join()
            assertEquals(1, fixture.exits)
        } finally { app.cancelAndJoin() }
    }

    @Test
    fun loginWithForegroundPreferenceCreatesMainWindow() = guiTest {
        val fixture = Fixture(DesktopLaunchReason.LOGIN)
        val app = fixture.start(this)
        try {
            fixture.ready.await()
            fixture.preference.emit(false)
            fixture.window.await()
            withContext(MainUIDispatcher) { assertEquals(1, fixture.constructions) }
        } finally { app.cancelAndJoin() }
    }

    @Test
    fun backgroundAndPasskeyStartsNeedNoTrayAndNeverConstructMainContent() = guiTest {
        for (reason in listOf(DesktopLaunchReason.BACKGROUND, DesktopLaunchReason.PASSKEY)) {
            val fixture = Fixture(reason)
            val app = fixture.start(this)
            try {
                val state = fixture.ready.await()
                withContext(MainUIDispatcher) { fixture.closeToTray.value = false }
                fixture.trayDisabled.await()
                withContext(MainUIDispatcher) {
                    assertFalse(state.created, reason.name)
                    assertEquals(0, fixture.constructions)
                    assertEquals(0, fixture.preference.subscriptionCount.value)
                    state.open()
                }
                fixture.window.await()
                withContext(MainUIDispatcher) { assertEquals(1, fixture.constructions) }
            } finally { app.cancelAndJoin() }
        }
    }

    @Test
    fun explicitUserLaunchDoesNotWaitForPreferenceOrTray() = guiTest {
        val fixture = Fixture(DesktopLaunchReason.USER)
        val app = fixture.start(this)
        try {
            fixture.window.await()
            withContext(MainUIDispatcher) {
                assertEquals(1, fixture.constructions)
                assertEquals(0, fixture.preference.subscriptionCount.value)
            }
        } finally { app.cancelAndJoin() }
    }

    @Test
    fun backgroundWithoutTrayHostCreatesMainWindowOnce() = guiTest {
        val fixture = Fixture(DesktopLaunchReason.BACKGROUND, trayHost = false)
        val app = fixture.start(this)
        try {
            val state = fixture.ready.await()
            fixture.window.await()
            withContext(MainUIDispatcher) {
                snapshotFlow { state.visible }.first { it }
                assertEquals(1, fixture.constructions)
                assertEquals(0, fixture.preference.subscriptionCount.value)
            }
        } finally { app.cancelAndJoin() }
    }

    @Test
    fun backgroundWithTrayHostNeverCreatesMainWindow() = guiTest {
        val fixture = Fixture(DesktopLaunchReason.BACKGROUND)
        val app = fixture.start(this)
        try {
            val state = fixture.ready.await()
            fixture.awaitTrayHostObserved()
            delay(fixture.grace * 3)
            withContext(MainUIDispatcher) {
                assertFalse(state.created)
                assertEquals(0, fixture.constructions)
            }
        } finally { app.cancelAndJoin() }
    }

    @Test
    fun backgroundCreatesMainWindowWhenTrayHostGoesAway() = guiTest {
        val fixture = Fixture(DesktopLaunchReason.BACKGROUND)
        val app = fixture.start(this)
        try {
            val state = fixture.ready.await()
            fixture.awaitTrayHostObserved()
            delay(fixture.grace * 3)
            withContext(MainUIDispatcher) {
                assertFalse(state.created)
                assertEquals(0, fixture.constructions)
            }
            // For example, the GNOME tray extension was turned off.
            fixture.trayHost.value = false
            fixture.window.await()
            withContext(MainUIDispatcher) {
                snapshotFlow { state.visible }.first { it }
                assertEquals(1, fixture.constructions)
            }
        } finally { app.cancelAndJoin() }
    }

    @Test
    fun backgroundWaitsForATrayHostThatIsNotKnownYet() = guiTest {
        val fixture = Fixture(DesktopLaunchReason.BACKGROUND, trayHost = null)
        val app = fixture.start(this)
        try {
            val state = fixture.ready.await()
            fixture.awaitTrayHostObserved()
            // An absent host may still register within the grace period.
            fixture.trayHost.value = false
            fixture.trayHost.value = true
            delay(fixture.grace * 3)
            withContext(MainUIDispatcher) {
                assertFalse(state.created)
                assertEquals(0, fixture.constructions)
            }
        } finally { app.cancelAndJoin() }
    }

    @Test
    fun loginInBackgroundWithoutTrayHostCreatesMainWindowAfterPreference() = guiTest {
        val fixture = Fixture(DesktopLaunchReason.LOGIN, trayHost = false)
        val app = fixture.start(this)
        try {
            val state = fixture.ready.await()
            fixture.preference.subscriptionCount.first { it == 1 }
            withContext(MainUIDispatcher) {
                assertFalse(state.created)
                assertEquals(0, fixture.trayHost.subscriptionCount.value)
            }
            fixture.preference.emit(true)
            fixture.window.await()
            withContext(MainUIDispatcher) {
                assertEquals(1, fixture.constructions)
            }
        } finally { app.cancelAndJoin() }
    }

    @Test
    fun loginInBackgroundWithTrayHostNeverCreatesMainWindow() = guiTest {
        val fixture = Fixture(DesktopLaunchReason.LOGIN)
        val app = fixture.start(this)
        try {
            val state = fixture.ready.await()
            fixture.preference.emit(true)
            fixture.awaitTrayHostObserved()
            delay(fixture.grace * 3)
            withContext(MainUIDispatcher) {
                assertFalse(state.created)
                assertEquals(0, fixture.constructions)
            }
        } finally { app.cancelAndJoin() }
    }

    private fun guiTest(block: suspend CoroutineScope.() -> Unit) {
        requireDesktopTestDisplay()
        runBlocking { withTimeout(30.seconds) { block() } }
    }

    private class Fixture(
        val reason: DesktopLaunchReason,
        trayHost: Boolean? = true,
    ) {
        val preference = MutableSharedFlow<Boolean>(replay = 1)
        val trayHost = MutableStateFlow(trayHost)
        val grace = 200.milliseconds
        val closeToTray = mutableStateOf(true)
        val ready = CompletableDeferred<DesktopStartupWindow>()
        val window = CompletableDeferred<AwtWindow>()
        val trayDisabled = CompletableDeferred<Unit>()
        var constructions = 0
        var exits = 0

        suspend fun awaitTrayHostObserved() {
            trayHost.subscriptionCount.first { it > 0 }
        }

        fun start(scope: CoroutineScope): Job = scope.launchApplication {
            LaunchedEffect(Unit) { awaitCancellation() }
            val state = rememberDesktopStartupWindow(
                reason = reason,
                startInBackground = preference,
                closeToTray = closeToTray.value,
                trayHost = trayHost,
                trayHostGrace = grace,
            )
            SideEffect {
                ready.complete(state)
                if (!closeToTray.value) trayDisabled.complete(Unit)
            }
            DesktopStartupWindowHost(
                state,
                closeToTray.value,
                onExit = { exits++; exitApplication() },
            ) { visible, onClose ->
                Window(visible = visible, onCloseRequest = onClose, title = "Keyguard startup test") {
                    DisposableEffect(Unit) {
                        constructions++
                        this@Fixture.window.complete(this@Window.window)
                        onDispose { }
                    }
                }
            }
        }
    }
}
