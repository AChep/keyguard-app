@file:OptIn(InternalComposeUiApi::class)

package com.artemchep.keyguard.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.BiometricStatus
import com.artemchep.keyguard.common.model.NavAnimation
import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.service.clipboard.ClipboardEventBus
import com.artemchep.keyguard.common.service.clipboard.ClipboardService
import com.artemchep.keyguard.common.service.keyboard.KeyboardShortcutsService
import com.artemchep.keyguard.common.service.keyboard.KeyboardShortcutsServiceHost
import com.artemchep.keyguard.common.service.keyvalue.impl.JsonKeyValueStore
import com.artemchep.keyguard.common.usecase.BiometricStatusUseCase
import com.artemchep.keyguard.common.usecase.ConfirmAccessByPasswordUseCase
import com.artemchep.keyguard.common.usecase.ConfirmAccessByYubiKeyRequest
import com.artemchep.keyguard.common.usecase.ConfirmAccessByYubiKeyUseCase
import com.artemchep.keyguard.common.usecase.GetBiometricRequireConfirmation
import com.artemchep.keyguard.common.usecase.GetDebugScreenDelay
import com.artemchep.keyguard.common.usecase.GetNavAnimation
import com.artemchep.keyguard.common.usecase.GetScreenState
import com.artemchep.keyguard.common.usecase.PutScreenState
import com.artemchep.keyguard.common.usecase.ShowMessage
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.agent.AgentApprovalChoiceItem
import com.artemchep.keyguard.feature.agent.AgentApprovalInputGuard
import com.artemchep.keyguard.feature.agent.LocalAgentApprovalInputGuard
import com.artemchep.keyguard.common.model.BiometricPurpose
import com.artemchep.keyguard.feature.biometric.BiometricPromptHost
import com.artemchep.keyguard.feature.biometric.BiometricPromptRequest
import com.artemchep.keyguard.platform.LeBiometricCipher
import com.artemchep.keyguard.feature.navigation.DialogRoute
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationController
import com.artemchep.keyguard.feature.navigation.NavigationHost
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.NavigationRestoreRepository
import com.artemchep.keyguard.feature.navigation.NavigationStackPersistence
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.platform.WindowId
import com.artemchep.keyguard.ui.LocalComposeWindow
import java.awt.Window
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.serialization.json.Json
import org.koin.compose.KoinContext
import org.koin.core.Koin
import org.koin.dsl.module
import sun.misc.Unsafe

private const val PASSWORD = "secret"

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

/** Runs [block] on the Swing thread, as the scene expects. */
private fun <T> onEdt(block: () -> T): T {
    if (SwingUtilities.isEventDispatchThread()) return block()
    var result: Result<T>? = null
    try {
        SwingUtilities.invokeAndWait {
            result = runCatching(block)
        }
    } catch (error: InvocationTargetException) {
        throw error.targetException
    }
    return result!!.getOrThrow()
}

private fun ImageComposeScene.hasText(text: String): Boolean =
    semanticsOwners.flatMap { descendants(it.rootSemanticsNode) }.any { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true
    }

private fun ImageComposeScene.node(text: String): SemanticsNode? =
    semanticsOwners.flatMap { it.getAllSemanticsNodes(mergingEnabled = true) }.firstOrNull { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true
    }

private fun ImageComposeScene.focusedNode(): SemanticsNode? =
    semanticsOwners.flatMap { it.getAllSemanticsNodes(mergingEnabled = true) }.firstOrNull { node ->
        node.config.getOrNull(SemanticsProperties.Focused) == true
    }

private fun ImageComposeScene.passwordNode(): SemanticsNode? =
    semanticsOwners.flatMap { it.getAllSemanticsNodes(mergingEnabled = true) }.firstOrNull { node ->
        node.config.getOrNull(SemanticsProperties.Password) != null &&
            node.config.getOrNull(SemanticsActions.SetText) != null
    }

private fun descendants(node: SemanticsNode): List<SemanticsNode> =
    listOf(node) + node.children.flatMap(::descendants)

/** The biometric prompt of a test, which never shows. */
private object NoBiometricPromptHost : BiometricPromptHost {
    override suspend fun createCipher(purpose: BiometricPurpose): LeBiometricCipher = error("No biometrics")

    override suspend fun materialize(
        request: BiometricPromptRequest,
        cipher: LeBiometricCipher,
    ) = error("No biometrics")
}

@OptIn(ExperimentalComposeUiApi::class)
class ApprovalPromptNavigationTest {
    @Test
    fun `standalone approval renders and can open an access verification dialog`() = onEdt {
        renderApproval()
    }

    @Test
    fun `queued approval replaces the previous request and its verification dialog`() = onEdt {
        renderApproval(replaceRequest = true)
    }

    @Test
    fun `approving a request that requires the identity asks for it first`() = withPrompt {
        click("Approve")
        awaitText("Confirm access")
        assertFalse(hasText("Approve"))
        assertTrue(hasText("Passkey 0"))
        assertEquals(0, confirms)
        assertEquals(emptyList(), decisions)
    }

    @Test
    fun `back returns to the request with its state`() = withPrompt {
        click("Select next")
        assertTrue(hasText("Selected 1"))
        click("Approve")
        awaitText("Confirm access")
        assertTrue(hasText("Passkey 1"))

        click("Back")
        awaitText("Selected 1")
        assertFalse(hasText("Confirm access"))
        assertEquals(0, confirms)
        assertEquals(emptyList(), decisions)
    }

    @Test
    fun `a confirmed identity approves the request once`() = withPrompt {
        click("Approve")
        awaitText("Confirm access")
        enterPassword(PASSWORD)
        awaitEnabled("Confirm")
        click("Confirm")
        await { decisions.isNotEmpty() }
        assertEquals(1, confirms)
        assertEquals(listOf(true), verified)
        assertEquals(listOf(true to "approval_dialog_confirm"), decisions)
    }

    @Test
    fun `an incorrect password keeps the step open`() = withPrompt {
        click("Approve")
        awaitText("Confirm access")
        enterPassword("incorrect")
        awaitEnabled("Confirm")
        click("Confirm")
        await { passwordChecks > 0 }
        render()
        assertTrue(hasText("Confirm access"))
        assertEquals(0, confirms)
        assertEquals(emptyList(), decisions)
    }

    @Test
    fun `a new request starts at the request`() = withPrompt {
        click("Approve")
        awaitText("Confirm access")

        requestId = "next-request"
        awaitText("Request next-request")
        assertFalse(hasText("Confirm access"))
    }

    @Test
    fun `a late identity requirement asks for it instead of answering`() = withPrompt(
        requiresIdentity = false,
    ) {
        click("Approve")
        awaitText("Confirm access")
        assertEquals(1, confirms)
        assertEquals(listOf(false), verified)
        assertEquals(emptyList(), decisions)

        enterPassword(PASSWORD)
        awaitEnabled("Confirm")
        click("Confirm")
        await { decisions.isNotEmpty() }
        assertEquals(listOf(false, true), verified)
        assertEquals(listOf(true to "approval_dialog_confirm"), decisions)
    }

    @Test
    fun `the password field takes the focus and Enter confirms`() = withPrompt {
        click("Approve")
        awaitText("Confirm access")
        await { focusedIsPassword() }

        // Typing goes into the focused field.
        enterTextIntoFocused(PASSWORD)
        awaitEnabled("Confirm")
        await { focusedIsPassword() }
        pressEnter()
        await { decisions.isNotEmpty() }
        assertEquals(listOf(true to "approval_dialog_confirm"), decisions)
    }

    private fun renderApproval(replaceRequest: Boolean = false) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val koin = Koin().apply {
            loadModules(listOf(TestModule(scope).module))
        }
        val host = NavigationHost()
        var navigation: NavigationController? = null
        val onNavigation = { controller: NavigationController -> navigation = controller }
        var request by mutableStateOf<Pair<String, Route>?>(
            "test-request" to approvalRoute("Environment approval", onNavigation),
        )
        try {
            ImageComposeScene(
                width = 320,
                height = 420,
                density = Density(1f),
                content = {
                    ApprovalContent(koin, scope, host, request)
                },
            ).use { scene ->
                scene.render(0).close()
                assertTrue(scene.hasText("Environment approval"))

                val verification = object : DialogRoute {
                    @Composable
                    override fun Content() {
                        Text("Verify environment access")
                    }
                }
                assertNotNull(navigation).queue(NavigationIntent.NavigateToRoute(verification))
                scene.render(1_000_000_000).close()
                scene.render(2_000_000_000).close()
                assertTrue(scene.hasText("Verify environment access"))

                if (replaceRequest) {
                    // Advance directly to the next pending request. The approval window
                    // remains in the composition when the queue never becomes empty.
                    request = "next-request" to approvalRoute("Next environment approval", onNavigation)
                    scene.render(3_000_000_000).close()
                    scene.render(4_000_000_000).close()
                    assertTrue(scene.hasText("Next environment approval"))
                    assertFalse(scene.hasText("Environment approval"))
                    assertFalse(scene.hasText("Verify environment access"))

                    assertNotNull(navigation).queue(NavigationIntent.NavigateToRoute(verification))
                    scene.render(5_000_000_000).close()
                    scene.render(6_000_000_000).close()
                    assertTrue(scene.hasText("Verify environment access"))

                    request = null
                    scene.render(7_000_000_000).close()
                    assertFalse(scene.hasText("Next environment approval"))
                    assertFalse(scene.hasText("Verify environment access"))

                    request = "later-request" to approvalRoute("Later environment approval", onNavigation)
                    scene.render(8_000_000_000).close()
                    assertTrue(scene.hasText("Later environment approval"))
                    assertNotNull(navigation).queue(NavigationIntent.NavigateToRoute(verification))
                    scene.render(9_000_000_000).close()
                    scene.render(10_000_000_000).close()
                    assertTrue(scene.hasText("Verify environment access"))
                }
            }
        } finally {
            scope.cancel()
            koin.close()
        }
    }

    @Composable
    private fun ApprovalContent(
        koin: Koin,
        scope: CoroutineScope,
        host: NavigationHost,
        request: Pair<String, Route>?,
    ) {
        KoinContext(koin) {
            MaterialTheme {
                NavigationHost(host) {
                    NavigationController(scope = scope, canPop = flowOf(false), handle = { it }) {
                        request?.let { (id, route) ->
                            ApprovalPromptNavigation(requestId = id, route = route)
                        }
                    }
                }
            }
        }
    }

    private fun approvalRoute(
        label: String,
        onNavigation: (NavigationController) -> Unit,
    ) = object : Route {
        @Composable
        override fun Content() {
            val controller = LocalNavigationController.current
            SideEffect { onNavigation(controller) }
            Text(label)
        }
    }


    /** One prompt of [ApprovalPrompt] in a scene, with fakes of the identity confirmation. */
    private class PromptScene(
        requiresIdentity: Boolean,
    ) {
        var requestId by mutableStateOf("test-request")
        var requiresIdentity by mutableStateOf(requiresIdentity)

        /** The calls of `confirm`. */
        @Volatile
        var confirms = 0

        /** The results of every `verify`. */
        val verified = CopyOnWriteArrayList<Boolean>()
        val decisions = CopyOnWriteArrayList<Pair<Boolean, String>>()

        @Volatile
        var passwordChecks = 0

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val koin = Koin().apply {
            loadModules(
                listOf(
                    TestModule(scope).module,
                    module {
                        single<ConfirmAccessByPasswordUseCase> {
                            object : ConfirmAccessByPasswordUseCase {
                                override fun invoke(password: String): IO<Boolean> = {
                                    passwordChecks++
                                    password == PASSWORD
                                }
                            }
                        }
                    },
                ),
            )
        }
        val host = NavigationHost()
        val guard = AgentApprovalInputGuard()
        private val scheduler = TestCoroutineScheduler()
        private val guardScope = CoroutineScope(StandardTestDispatcher(scheduler))
        private var frameTimeNanos = 0L
        lateinit var scene: ImageComposeScene

        fun start() = onEdt {
            scene = ImageComposeScene(
                width = 320,
                height = 420,
                density = Density(1f),
                content = {
                    KoinContext(koin) {
                        MaterialTheme {
                            NavigationHost(host) {
                                NavigationController(scope = scope, canPop = flowOf(false), handle = { it }) {
                                    CompositionLocalProvider(
                                        LocalAgentApprovalInputGuard provides guard,
                                        LocalComposeWindow provides sceneWindow,
                                    ) {
                                        Prompt()
                                    }
                                }
                            }
                        }
                    }
                },
            )
            guardScope.launch { guard.run(focused = true) }
            renderFrames()
        }

        @Composable
        private fun Prompt() {
            val id = requestId
            // As a host keeps the state of the request's content.
            var selected by remember(id) { mutableStateOf(0) }
            ApprovalPrompt(
                requestId = id,
                requiresIdentity = requiresIdentity,
                expiresAt = Clock.System.now() + 1.minutes,
                confirm = { verify ->
                    confirms++
                    val result = verify()
                    verified += result
                    result
                },
                onDecision = { approved, reason ->
                    decisions += approved to reason
                },
                subject = AgentApprovalChoiceItem("Passkey $selected", null),
            ) { controls ->
                Column {
                    Text("Request $id")
                    Text("Selected $selected")
                    TextButton(onClick = { selected++ }) {
                        Text("Select next")
                    }
                    TextButton(onClick = controls.onDeny) {
                        Text("Deny")
                    }
                    TextButton(enabled = !controls.busy, onClick = controls.onAllow) {
                        Text("Approve")
                    }
                }
            }
        }

        /** Renders frames, and arms the input guard as a second of quiet would. */
        fun render() = onEdt { renderFrames() }

        private fun renderFrames() {
            repeat(3) {
                scheduler.advanceTimeBy(1.seconds)
                scheduler.runCurrent()
                frameTimeNanos += 16_000_000L
                scene.render(frameTimeNanos).close()
            }
        }

        fun hasText(text: String): Boolean = onEdt { scene.hasText(text) }

        private fun texts(): List<String> = onEdt {
            scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(mergingEnabled = true) }
                .flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
        }

        fun click(text: String) {
            onEdt {
                val action = assertNotNull(scene.node(text)).config.getOrNull(SemanticsActions.OnClick)
                assertTrue(assertNotNull(action).action!!.invoke(), "$text is disabled")
            }
            render()
        }

        fun enterPassword(text: String) {
            onEdt {
                val node = assertNotNull(scene.passwordNode())
                val action = node.config.getOrNull(SemanticsActions.SetText)
                assertTrue(assertNotNull(action).action!!.invoke(AnnotatedString(text)))
            }
            render()
        }

        /** Enters the [text] into the focused node, as typing would. */
        fun enterTextIntoFocused(text: String) {
            onEdt {
                val node = assertNotNull(scene.focusedNode())
                val action = node.config.getOrNull(SemanticsActions.SetText)
                assertTrue(assertNotNull(action).action!!.invoke(AnnotatedString(text)))
            }
            render()
        }

        fun pressEnter() {
            onEdt {
                listOf(KeyEventType.KeyDown, KeyEventType.KeyUp).forEach { type ->
                    scene.sendKeyEvent(KeyEvent(Key.Enter, type))
                }
            }
            render()
        }

        fun focusedIsPassword(): Boolean = onEdt {
            scene.passwordNode()?.config?.getOrNull(SemanticsProperties.Focused) == true
        }

        fun isEnabled(text: String): Boolean = onEdt {
            scene.node(text)?.config?.getOrNull(SemanticsProperties.Disabled) == null
        }

        /** Renders until [condition]; the confirmation works on other threads. */
        fun await(condition: () -> Boolean) {
            val deadline = System.nanoTime() + 10_000_000_000L
            while (!condition()) {
                assertTrue(System.nanoTime() < deadline, "Timed out: ${texts()}")
                Thread.sleep(20L)
                render()
            }
        }

        fun awaitText(text: String) = await { hasText(text) }

        fun awaitEnabled(text: String) = await { hasText(text) && isEnabled(text) }

        fun close() {
            runCatching { onEdt { scene.close() } }
            guardScope.cancel()
            scope.cancel()
            koin.close()
        }
    }

    private fun withPrompt(
        requiresIdentity: Boolean = true,
        test: PromptScene.() -> Unit,
    ) {
        val prompt = PromptScene(requiresIdentity)
        try {
            prompt.start()
            prompt.awaitText("Request test-request")
            prompt.test()
        } finally {
            prompt.close()
        }
    }

    private class TestModule(scope: CoroutineScope) {
        val module = module {
            single {
                NavigationRestoreRepository(NavigationStackPersistence(JsonKeyValueStore(), Json), scope)
            }
            single<KeyboardShortcutsServiceHost> {
                object : KeyboardShortcutsServiceHost {
                    override fun register(windowId: WindowId, block: (KeyEvent) -> Boolean): () -> Unit = {}
                }
            }
            single<GetNavAnimation> {
                object : GetNavAnimation {
                    override fun invoke() = MutableStateFlow(NavAnimation.DISABLED)
                }
            }
            // The screen state of the identity confirmation.
            single<WindowCoroutineScope> {
                object : WindowCoroutineScope, CoroutineScope by scope {}
            }
            single<ShowMessage> {
                object : ShowMessage {
                    override fun copy(value: ToastMessage, target: String?) = Unit
                }
            }
            single<ClipboardService> {
                object : ClipboardService {
                    override fun setPrimaryClip(value: String, concealed: Boolean) = Unit
                    override fun clearPrimaryClip() = Unit
                    override fun hasCopyNotification(): Boolean = false
                }
            }
            single { ClipboardEventBus() }
            single<GetScreenState> {
                object : GetScreenState {
                    override fun invoke(key: String) = flowOf(emptyMap<String, Any?>())
                }
            }
            single<PutScreenState> {
                object : PutScreenState {
                    override fun invoke(key: String, state: Map<String, Any?>): IO<Unit> = {}
                }
            }
            single<GetDebugScreenDelay> {
                object : GetDebugScreenDelay {
                    override fun invoke() = flowOf(false)
                }
            }
            single<Json> { Json }
            single<KeyboardShortcutsService> {
                object : KeyboardShortcutsService {
                    override fun handle(windowId: WindowId, keyEvent: KeyEvent): Boolean = false
                }
            }
            // The identity confirmation, with a password only.
            single<BiometricStatusUseCase> {
                object : BiometricStatusUseCase {
                    override fun invoke() = flowOf(BiometricStatus.Unavailable)
                }
            }
            single<GetBiometricRequireConfirmation> {
                object : GetBiometricRequireConfirmation {
                    override fun invoke() = flowOf(false)
                }
            }
            single<ConfirmAccessByYubiKeyUseCase> {
                object : ConfirmAccessByYubiKeyUseCase {
                    override fun invoke(): IO<ConfirmAccessByYubiKeyRequest?> = { null }
                }
            }
            single<BiometricPromptHost> { NoBiometricPromptHost }
        }
    }
}
