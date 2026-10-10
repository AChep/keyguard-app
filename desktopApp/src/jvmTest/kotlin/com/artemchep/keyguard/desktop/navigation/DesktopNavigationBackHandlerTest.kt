package com.artemchep.keyguard.desktop.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.artemchep.keyguard.feature.navigation.BackHandler
import com.artemchep.keyguard.feature.navigation.NavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.ui.LeMOdelBottomSheet
import java.awt.Component
import java.awt.Container
import java.awt.GraphicsEnvironment
import java.awt.event.InputEvent
import java.awt.event.KeyEvent as AwtKeyEvent
import java.util.concurrent.FutureTask
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import org.junit.Assume.assumeFalse

@OptIn(ExperimentalComposeUiApi::class)
class DesktopNavigationBackHandlerTest {
    @Test
    fun `Escape dismisses the preview and preserves the editor draft`() {
        var previewVisible by mutableStateOf(false)
        var previewReady = false
        var renderedDraft = ""
        var editDraft: (String) -> Unit = {}
        var editorVisible by mutableStateOf(true)
        val handler = BackHandler()
        var pops = 0
        handler.register(controller {
            pops++
            editorVisible = false
        }, emptyList()) { editorVisible }

        withWindow(handler) {
            if (editorVisible) {
                var draft by remember { mutableStateOf("search default") }
                SideEffect {
                    renderedDraft = draft
                    editDraft = { draft = it }
                }
                Text(draft)
                LeMOdelBottomSheet(
                    visible = previewVisible,
                    onDismissRequest = { previewVisible = false },
                ) {
                    Text(draft)
                    LaunchedEffect(Unit) {
                        withFrameNanos { }
                        previewReady = true
                    }
                    DisposableEffect(Unit) {
                        onDispose { previewReady = false }
                    }
                }
            }
        }.use { window ->
            awaitCondition { renderedDraft == "search default" }
            onEdt {
                editDraft("Distinct name\n# Unsaved note")
                previewVisible = true
            }
            awaitCondition { previewReady }
            // Recompose the editor after the popup has registered.
            onEdt { editDraft("Updated name\n# Unsaved note") }
            awaitCondition { renderedDraft.startsWith("Updated name") }

            window.pressKey()
            awaitCondition { !previewReady }
            onEdt {
                assertFalse(previewVisible)
                assertTrue(editorVisible)
                assertEquals("Updated name\n# Unsaved note", renderedDraft)
                assertEquals(0, pops)
            }

            window.pressKey()
            onEdt {
                assertFalse(editorVisible)
                assertEquals(1, pops)
            }
            // A subsequent event before router re-registration cannot pop at root.
            window.pressKey()
            onEdt { assertEquals(1, pops) }
        }
    }

    @Test
    fun `nested popups dismiss one layer per Escape`() {
        val handler = BackHandler()
        var pops = 0
        var outerVisible by mutableStateOf(true)
        var innerVisible by mutableStateOf(true)
        var innerReady = false
        handler.register(controller { pops++ }, emptyList()) { true }
        withWindow(handler) {
            if (outerVisible) {
                Popup(
                    onDismissRequest = { outerVisible = false },
                    properties = PopupProperties(focusable = true),
                ) {
                    Text("Outer popup")
                    if (innerVisible) {
                        Popup(
                            onDismissRequest = { innerVisible = false },
                            properties = PopupProperties(focusable = true),
                        ) {
                            Text("Inner popup")
                            DisposableEffect(Unit) {
                                innerReady = true
                                onDispose { innerReady = false }
                            }
                        }
                    }
                }
            }
        }.use { window ->
            awaitCondition { innerReady }
            window.pressKey()
            awaitCondition { !innerReady }
            onEdt {
                assertTrue(outerVisible)
                assertFalse(innerVisible)
                assertEquals(0, pops)
            }
            window.pressKey()
            awaitCondition { !outerVisible }
            onEdt { assertEquals(0, pops) }
        }
    }

    @Test
    fun `modified Escape other keys and consumed shortcuts do not navigate`() {
        val handler = BackHandler()
        var pops = 0
        var consumeShortcut = false
        handler.register(controller { pops++ }, emptyList()) { true }
        withWindow(handler, onKeyEvent = { consumeShortcut }) { Text("Editor") }.use { window ->
            for (modifier in listOf(
                InputEvent.CTRL_DOWN_MASK,
                InputEvent.META_DOWN_MASK,
                InputEvent.ALT_DOWN_MASK,
                InputEvent.SHIFT_DOWN_MASK,
            )) {
                window.pressKey(modifiers = modifier)
            }
            window.pressKey(key = AwtKeyEvent.VK_ENTER)
            onEdt {
                assertEquals(0, pops)
                consumeShortcut = true
            }
            window.pressKey()
            onEdt {
                assertEquals(0, pops)
                consumeShortcut = false
            }
            window.pressKey()
            onEdt { assertEquals(1, pops) }
        }
    }

    private fun controller(onBack: () -> Unit) = object : NavigationController {
        override val scope = CoroutineScope(Dispatchers.Unconfined)
        override fun canPop() = flowOf(true)
        override fun queue(intent: NavigationIntent) {
            assertEquals(NavigationIntent.Pop, intent)
            onBack()
        }
    }

    private fun withWindow(
        handler: BackHandler,
        onKeyEvent: (KeyEvent) -> Boolean = { false },
        content: @Composable () -> Unit,
    ): TestWindow {
        assumeFalse("Native Compose window tests require a display", GraphicsEnvironment.isHeadless())
        var ready = false
        val window = onEdt {
            ComposeWindow().apply {
                setSize(640, 480)
                setContent(onKeyEvent = onKeyEvent) {
                    DesktopNavigationBackHandler(handler)
                    MaterialTheme {
                        Column { content() }
                    }
                    LaunchedEffect(Unit) {
                        withFrameNanos { }
                        ready = true
                    }
                }
                isVisible = true
            }
        }
        return TestWindow(window).also {
            try {
                awaitCondition { ready }
            } catch (e: Throwable) {
                it.close()
                throw e
            }
        }
    }

    private class TestWindow(private val window: ComposeWindow) : AutoCloseable {
        fun pressKey(key: Int = AwtKeyEvent.VK_ESCAPE, modifiers: Int = 0) = onEdt {
            // Invoke the native listener to exercise ComposeSceneMediator's full
            // dispatch order, including Window.onKeyEvent and popup Back dispatch.
            // Semantics performKeyInput bypasses that window-level path.
            val component = descendants(window).first { it.keyListeners.isNotEmpty() }
            for (id in listOf(AwtKeyEvent.KEY_PRESSED, AwtKeyEvent.KEY_RELEASED)) {
                val event = AwtKeyEvent(component, id, 0, modifiers, key, AwtKeyEvent.CHAR_UNDEFINED)
                component.keyListeners.forEach { listener ->
                    if (id == AwtKeyEvent.KEY_PRESSED) {
                        listener.keyPressed(event)
                    } else {
                        listener.keyReleased(event)
                    }
                }
            }
        }

        override fun close() = onEdt { window.dispose() }
    }

    companion object {
        private fun descendants(component: Component): Sequence<Component> = sequence {
            yield(component)
            if (component is Container) {
                component.components.forEach { yieldAll(descendants(it)) }
            }
        }

        private fun <T> onEdt(block: () -> T): T {
            val task = FutureTask(block)
            SwingUtilities.invokeAndWait(task)
            return task.get()
        }

        private fun awaitCondition(condition: () -> Boolean) {
            val deadline = System.nanoTime() + 10_000_000_000L
            while (!onEdt(condition)) {
                check(System.nanoTime() < deadline) { "Timed out waiting for Compose window" }
                Thread.sleep(10)
            }
        }
    }
}
