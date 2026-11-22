package com.artemchep.keyguard.feature.agent

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.feature.auth.common.TextFieldModel
import com.artemchep.keyguard.common.model.BiometricPurpose
import com.artemchep.keyguard.feature.biometric.BiometricPromptHost
import com.artemchep.keyguard.feature.biometric.BiometricPromptRequest
import com.artemchep.keyguard.platform.LeBiometricCipher
import com.artemchep.keyguard.feature.confirmation.elevatedaccess.ElevatedAccessState
import com.artemchep.keyguard.feature.keyguard.unlock.UnlockState
import com.artemchep.keyguard.ui.LocalComposeWindow
import com.artemchep.keyguard.ui.draw
import com.artemchep.keyguard.ui.focusedOrNull
import com.artemchep.keyguard.ui.nodeWithText
import com.artemchep.keyguard.ui.nodeWithTextOrNull
import com.artemchep.keyguard.ui.press
import com.artemchep.keyguard.ui.withScene
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import java.awt.Window
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import org.koin.compose.KoinContext
import org.koin.core.Koin
import org.koin.dsl.module
import sun.misc.Unsafe


/**
 * A window for [LocalComposeWindow], which the biometric prompt and the
 * skeletons read. The scene has no real one, and AWT may be headless, so
 * it is allocated without its constructor.
 */
private val sceneWindow: Window by lazy {
    val field = Unsafe::class.java.getDeclaredField("theUnsafe")
    field.isAccessible = true
    (field.get(null) as Unsafe).allocateInstance(Window::class.java) as Window
}

/** The biometric prompt of a test, which never shows. */
private object NoBiometricPromptHost : BiometricPromptHost {
    override suspend fun createCipher(purpose: BiometricPurpose): LeBiometricCipher = error("No biometrics")

    override suspend fun materialize(
        request: BiometricPromptRequest,
        cipher: LeBiometricCipher,
    ) = error("No biometrics")
}

class AgentApprovalConfirmAccessTest {
    private fun state(
        password: String = "",
        onConfirm: (() -> Unit)? = null,
    ) = ElevatedAccessState(
        content = Loadable.Ok(
            ElevatedAccessState.Content(
                sideEffects = UnlockState.SideEffects(
                    showBiometricPromptFlow = emptyFlow(),
                    showYubiKeyPromptFlow = emptyFlow(),
                ),
                password = TextFieldModel(
                    text = password,
                    onChange = {},
                ),
                biometric = ElevatedAccessState.Biometric(onClick = {}),
            ),
        ),
        onConfirm = onConfirm,
    )

    /**
     * Shows the step with an armed guard, as after the Approve button of
     * the request, and runs [test] against it.
     */
    private fun withConfirmAccess(
        state: () -> ElevatedAccessState,
        busy: () -> Boolean = { false },
        subject: AgentApprovalChoiceItem? = AgentApprovalChoiceItem("GitHub", "artem • Personal"),
        onBack: () -> Unit = {},
        test: ImageComposeScene.() -> Unit,
    ) {
        val guard = AgentApprovalInputGuard()
        val scheduler = TestCoroutineScheduler()
        val scope = CoroutineScope(StandardTestDispatcher(scheduler))
        scope.launch { guard.run(focused = true) }
        scheduler.advanceTimeBy(1.seconds)
        scheduler.runCurrent()
        assertTrue(guard.armed)

        val koin = Koin().apply {
            loadModules(
                listOf(
                    module {
                        single<BiometricPromptHost> { NoBiometricPromptHost }
                    },
                ),
            )
        }
        try {
            withScene(
                // Virtual time, on this thread: a delay that ends on a
                // timer thread would touch the scene from there.
                coroutineContext = UnconfinedTestDispatcher(scheduler),
                content = {
                    KoinContext(koin) {
                        CompositionLocalProvider(
                            LocalAgentApprovalInputGuard provides guard,
                            LocalComposeWindow provides sceneWindow,
                        ) {
                            AgentApprovalConfirmAccess(
                                state = state(),
                                subject = subject,
                                expiresAt = null,
                                busy = busy(),
                                onBack = onBack,
                            )
                        }
                    }
                },
            ) {
                // The password field asks for the focus after a short delay.
                scheduler.advanceTimeBy(1.seconds)
                scheduler.runCurrent()
                draw()
                assertNotNull(focusedOrNull())
                // The step keeps the guard armed.
                assertTrue(guard.armed)
                test()
            }
        } finally {
            scope.cancel()
            koin.close()
        }
    }

    @Test
    fun `back returns to the request`() {
        var backs = 0
        withConfirmAccess(
            state = { state() },
            onBack = { backs++ },
        ) {
            click("Back")
            assertEquals(1, backs)
        }
    }

    @Test
    fun `confirm is disabled until the form can confirm`() {
        var state by mutableStateOf(state())
        var busy by mutableStateOf(false)
        var confirms = 0
        withConfirmAccess(
            state = { state },
            busy = { busy },
        ) {
            assertFalse(isEnabled("Confirm"))

            state = state(password = "secret", onConfirm = { confirms++ })
            draw()
            assertTrue(isEnabled("Confirm"))

            busy = true
            draw()
            assertFalse(isEnabled("Confirm"))

            busy = false
            draw()
            click("Confirm")
            assertEquals(1, confirms)
        }
    }

    @Test
    fun `the password field takes the focus and Enter confirms`() {
        var confirms = 0
        withConfirmAccess(
            state = { state(password = "secret", onConfirm = { confirms++ }) },
        ) {
            val focused = assertNotNull(focusedOrNull())
            assertNotNull(focused.getOrNull(SemanticsProperties.Password))

            press(Key.Enter)
            assertEquals(1, confirms)
        }
    }

    @Test
    fun `names the subject, or nothing without one`() {
        withConfirmAccess(state = { state() }) {
            assertNotNull(nodeWithTextOrNull("GitHub"))
            assertNotNull(nodeWithTextOrNull("Confirm access"))
        }
        withConfirmAccess(state = { state() }, subject = null) {
            assertEquals(null, nodeWithTextOrNull("GitHub"))
        }
    }

    private fun ImageComposeScene.isEnabled(text: String): Boolean =
        nodeWithText(text).config.getOrNull(SemanticsProperties.Disabled) == null

    private fun ImageComposeScene.click(text: String) {
        val action = nodeWithText(text).config.getOrNull(SemanticsActions.OnClick)
        assertTrue(assertNotNull(action).action!!.invoke())
        draw()
    }
}
