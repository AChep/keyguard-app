@file:OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)

package com.artemchep.keyguard.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import androidx.navigationevent.NavigationEventInput
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.artemchep.keyguard.ui.wizard.WizardPreview
import java.util.WeakHashMap
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers

// Shared helpers for keyboard tests that drive an ImageComposeScene.
// The scene starts in keyboard input mode; a mouse press switches it
// to touch mode.

private const val FRAME_NANOS = 16_000_000L

private class SceneState {
    /** It only moves in [advanceTimeBy]. */
    var frameTimeNanos = 0L

    /** A desktop window sends an Escape that nothing consumed to Back. */
    val backInput = EscapeBackInput()

    /** Recorded by the content of the scene. */
    var inputModeManager: InputModeManager? = null
}

/** Mirrors the desktop window's internal BackNavigationEventInput. */
private class EscapeBackInput : NavigationEventInput() {
    fun onKeyEvent(event: KeyEvent) {
        if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
            dispatchOnBackCompleted()
        }
    }
}

private val sceneStates = WeakHashMap<ImageComposeScene, SceneState>()

private val ImageComposeScene.state: SceneState
    get() = checkNotNull(sceneStates[this]) { "Not a scene from keyboardScene" }

/**
 * A scene that hosts [content] like a desktop window: an Escape that
 * nothing consumed goes Back. The helpers below need a scene from here.
 */
internal fun keyboardScene(
    width: Int,
    height: Int,
    density: Density = Density(1f),
    coroutineContext: CoroutineContext = Dispatchers.Unconfined,
    content: @Composable () -> Unit,
): ImageComposeScene {
    val state = SceneState()
    val scene = ImageComposeScene(width, height, density, coroutineContext) {
        state.inputModeManager = LocalInputModeManager.current
        val dispatcher = LocalNavigationEventDispatcherOwner.current?.navigationEventDispatcher
        DisposableEffect(dispatcher) {
            dispatcher?.addInput(state.backInput)
            onDispose {
                dispatcher?.removeInput(state.backInput)
            }
        }
        content()
    }
    sceneStates[scene] = state
    return scene
}

/** Runs [test] against [content] on the Swing thread, inside the preview theme. */
internal fun withScene(
    content: @Composable () -> Unit,
    coroutineContext: CoroutineContext = Dispatchers.Unconfined,
    test: ImageComposeScene.() -> Unit,
) {
    SwingUtilities.invokeAndWait {
        keyboardScene(900, 700, coroutineContext = coroutineContext) {
            WizardPreview { content() }
        }.use { scene ->
            scene.draw()
            scene.test()
        }
    }
}

/** The input mode of a scene from [keyboardScene]. */
internal val ImageComposeScene.inputMode: InputMode
    get() = assertNotNull(sceneStates[this]?.inputModeManager).inputMode

/** Renders a few frames without moving the frame time, so animations stay put. */
internal fun ImageComposeScene.draw() {
    repeat(4) { render(state.frameTimeNanos).close() }
}

/** Renders frames until the frame time has moved by [millis], so animations run. */
internal fun ImageComposeScene.advanceTimeBy(millis: Long) {
    val state = state
    val end = state.frameTimeNanos + millis * 1_000_000L
    while (state.frameTimeNanos < end) {
        state.frameTimeNanos = minOf(end, state.frameTimeNanos + FRAME_NANOS)
        render(state.frameTimeNanos).close()
    }
    draw()
}

/** Presses and releases [key]. Like a desktop window, an unconsumed Escape goes Back. */
internal fun ImageComposeScene.press(key: Key, shift: Boolean = false, alt: Boolean = false) {
    sendKeyPress(key, shift = shift, alt = alt)
    draw()
}

/** Presses and releases the primary mouse button at [position]. */
internal fun ImageComposeScene.mouseClick(position: Offset) {
    sendClick(position)
    draw()
}

/** Like [press], but renders nothing. */
internal fun ImageComposeScene.sendKeyPress(key: Key, shift: Boolean = false, alt: Boolean = false) {
    listOf(KeyEventType.KeyDown, KeyEventType.KeyUp).forEach { type ->
        val event = KeyEvent(key, type, isAltPressed = alt, isShiftPressed = shift)
        if (!sendKeyEvent(event)) {
            state.backInput.onKeyEvent(event)
        }
    }
}

/** Like [mouseClick], but renders nothing. */
internal fun ImageComposeScene.sendClick(position: Offset) {
    sendPointerEvent(
        eventType = PointerEventType.Press,
        position = position,
        buttons = PointerButtons(isPrimaryPressed = true),
        button = PointerButton.Primary,
    )
    sendPointerEvent(
        eventType = PointerEventType.Release,
        position = position,
        buttons = PointerButtons(),
        button = PointerButton.Primary,
    )
}

internal fun ImageComposeScene.enterText(text: String) {
    assertTrue(focused()[SemanticsActions.SetText].action!!.invoke(AnnotatedString(text)))
    draw()
}
