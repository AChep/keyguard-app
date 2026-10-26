package com.artemchep.keyguard.feature.home.settings.shortcuts

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.input.key.KeyEvent
import com.artemchep.keyguard.common.exception.GlobalShortcutException
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.GlobalShortcutAction
import com.artemchep.keyguard.common.model.GlobalShortcutBinding
import com.artemchep.keyguard.common.model.GlobalShortcutConfig
import com.artemchep.keyguard.common.model.GlobalShortcutKey
import com.artemchep.keyguard.common.model.GlobalShortcutsConfig
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.service.shortcut.GlobalShortcutController
import com.artemchep.keyguard.common.service.shortcut.GlobalShortcutError
import com.artemchep.keyguard.common.service.shortcut.GlobalShortcutRecording
import com.artemchep.keyguard.common.service.shortcut.GlobalShortcutRuntimeState
import com.artemchep.keyguard.common.service.shortcut.GlobalShortcutStatus
import com.artemchep.keyguard.common.usecase.CopyText
import com.artemchep.keyguard.common.usecase.PutGlobalShortcut
import com.artemchep.keyguard.feature.loading.LoadingTask
import com.artemchep.keyguard.feature.loading.ReadableExceptionMessage
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.state.DiskHandle
import com.artemchep.keyguard.feature.navigation.state.PersistedStorage
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.platform.LeBundle
import com.artemchep.keyguard.platform.LeContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringResource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class KeyboardShortcutsSettingsStateProducerTest {
    @Test
    fun `cancel pops the recorder without saving`() =
        runTest {
            var saves = 0
            val fixture = fixture { _, _ -> saves++ }

            fixture.edit().onDismiss()
            advanceUntilIdle()

            assertEquals(NavigationIntent.Pop, fixture.scope.navigation.last())
            assertNull(fixture.current().recorder)
            assertEquals(0, saves)
        }

    @Test
    fun `busy save rejects duplicate submissions and dismissal`() =
        runTest {
            val saved = CompletableDeferred<Unit>()
            var saves = 0
            val fixture =
                fixture { _, _ ->
                    saves += 1
                    saved.await()
                }
            val recorder = fixture.edit()

            recorder.onSave(binding)
            recorder.onSave(binding)
            recorder.onDismiss()
            runCurrent()

            assertEquals(1, saves)
            assertTrue(fixture.current().busy)
            assertNotNull(fixture.current().recorder)
            saved.complete(Unit)
            advanceUntilIdle()
            assertEquals(1, saves)
            assertFalse(fixture.current().busy)
            assertNull(fixture.current().recorder)
        }

    @Test
    fun `typed save failure keeps the recorder open and can be cleared`() =
        runTest {
            val failure = GlobalShortcutError.Duplicate(GlobalShortcutAction.LockVault)
            val fixture = fixture { _, _ -> throw GlobalShortcutException(failure) }

            fixture.edit().onSave(binding)
            advanceUntilIdle()

            val state = fixture.current()
            assertFalse(state.busy)
            val recorder = assertNotNull(state.recorder)
            assertEquals(GlobalShortcutAction.QuickSearch, recorder.action)
            assertEquals(failure, recorder.error)
            assertTrue(fixture.scope.failures.isEmpty())
            recorder.onClearError()
            assertNull(assertNotNull(fixture.current().recorder).error)
        }

    @Test
    fun `successful save closes the recorder and preserves enabled configuration`() =
        runTest {
            val updates = mutableListOf<Pair<GlobalShortcutAction, GlobalShortcutConfig>>()
            val fixture =
                fixture { action, config ->
                    updates += action to config
                }
            fixture.controller.state.value =
                runtimeState(
                    GlobalShortcutsConfig(
                        quickSearch = GlobalShortcutConfig(enabled = false),
                    ),
                )

            fixture.edit().onSave(binding)
            advanceUntilIdle()

            assertEquals(
                listOf(GlobalShortcutAction.QuickSearch to GlobalShortcutConfig(enabled = false, binding = binding)),
                updates,
            )
            assertFalse(fixture.current().busy)
            assertNull(fixture.current().recorder)
        }

    @Test
    fun `unexpected save failure releases busy state and allows retry`() =
        runTest {
            var saves = 0
            val fixture =
                fixture { _, _ ->
                    saves += 1
                    if (saves == 1) error("Failed unexpectedly")
                }

            fixture.edit().onSave(binding)
            advanceUntilIdle()

            assertFalse(fixture.current().busy)
            assertEquals(listOf("Failed unexpectedly"), fixture.scope.failures)
            assertNotNull(fixture.current().recorder).onSave(binding)
            advanceUntilIdle()
            assertEquals(2, saves)
            assertFalse(fixture.current().busy)
            assertNull(fixture.current().recorder)
        }

    @Test
    fun `enable and reset failures are inline and successful updates clear them`() =
        runTest {
            val action = GlobalShortcutAction.QuickSearch
            val failure = GlobalShortcutError.Persistence
            var fail = true
            val updates = mutableListOf<GlobalShortcutConfig>()
            val fixture =
                fixture { _, config ->
                    updates += config
                    if (fail) throw GlobalShortcutException(failure)
                }
            fixture.controller.state.value =
                runtimeState(GlobalShortcutsConfig(quickSearch = GlobalShortcutConfig(binding = binding)))

            fixture
                .current()
                .items
                .first { it.action == action }
                .onEnabledChange(false)
            advanceUntilIdle()
            assertEquals(
                failure,
                fixture
                    .current()
                    .items
                    .first { it.action == action }
                    .error,
            )
            assertEquals(GlobalShortcutConfig(enabled = false, binding = binding), updates.last())

            fixture
                .current()
                .items
                .first { it.action == action }
                .onReset()
            advanceUntilIdle()
            assertEquals(
                failure,
                fixture
                    .current()
                    .items
                    .first { it.action == action }
                    .error,
            )
            assertNull(updates.last().binding)

            fail = false
            fixture
                .current()
                .items
                .first { it.action == action }
                .onReset()
            advanceUntilIdle()
            assertNull(
                fixture
                    .current()
                    .items
                    .first { it.action == action }
                    .error,
            )
            assertTrue(fixture.scope.failures.isEmpty())
        }

    @Test
    fun `cancelled save keeps recorder open without presenting an error`() =
        runTest {
            val fixture = fixture { _, _ -> throw CancellationException("Cancelled") }
            fixture.edit().onSave(binding)
            advanceUntilIdle()
            val state = fixture.current()
            assertFalse(state.busy)
            assertNull(assertNotNull(state.recorder).error)
            assertTrue(fixture.scope.failures.isEmpty())
        }

    private suspend fun TestScope.fixture(
        put: suspend (GlobalShortcutAction, GlobalShortcutConfig) -> Unit,
    ): Fixture {
        val scope = ShortcutTestScope(this)
        val controller = ShortcutTestController()
        val putShortcut =
            object : PutGlobalShortcut {
                override fun invoke(
                    action: GlobalShortcutAction,
                    transform: (GlobalShortcutConfig) -> GlobalShortcutConfig,
                ): IO<Unit> = { put(action, transform(checkNotNull(controller.state.value.config)[action])) }
            }
        return Fixture(
            scope = scope,
            controller = controller,
            state = scope.keyboardShortcutsSettingsStateProducer(controller, putShortcut),
            recorderState =
                scope.shortcutRecorderStateProducer(
                    controller,
                    putShortcut,
                    GlobalShortcutAction.QuickSearch,
                ),
        )
    }

    private class Fixture(
        val scope: ShortcutTestScope,
        val controller: ShortcutTestController,
        val state: Flow<Loadable<KeyboardShortcutsSettingsState>>,
        val recorderState: Flow<ShortcutRecorderState>,
    ) {
        suspend fun current(): Snapshot {
            val settings = assertIs<Loadable.Ok<KeyboardShortcutsSettingsState>>(state.first()).value
            val recorder =
                recorderState.first().takeIf {
                    scope.navigation.lastOrNull() is NavigationIntent.NavigateToRoute
                }
            return Snapshot(settings.items, recorder?.busy ?: false, recorder)
        }

        suspend fun edit(): ShortcutRecorderState {
            current().items.first { it.action == GlobalShortcutAction.QuickSearch }.onEdit()
            assertEquals(
                ShortcutRecorderRoute(GlobalShortcutAction.QuickSearch),
                assertIs<NavigationIntent.NavigateToRoute>(scope.navigation.last()).route,
            )
            return assertNotNull(current().recorder)
        }
    }

    private data class Snapshot(
        val items: List<KeyboardShortcutsSettingsState.Item>,
        val busy: Boolean,
        val recorder: ShortcutRecorderState?,
    )

    private companion object {
        val binding = GlobalShortcutBinding(key = GlobalShortcutKey.K, ctrl = true, shift = true)
    }
}

private fun runtimeState(config: GlobalShortcutsConfig) =
    GlobalShortcutRuntimeState(
        config = config,
        statuses =
            GlobalShortcutAction.entries.associateWith {
                if (config[it].enabled) GlobalShortcutStatus.Active else GlobalShortcutStatus.Disabled
            },
    )

private class ShortcutTestController : GlobalShortcutController {
    override val state = MutableStateFlow(runtimeState(GlobalShortcutsConfig()))

    override suspend fun update(
        action: GlobalShortcutAction,
        transform: (GlobalShortcutConfig) -> GlobalShortcutConfig,
    ): GlobalShortcutError? = error("Updates must use PutGlobalShortcut")

    override suspend fun suspendRecording(): GlobalShortcutRecording = error("Recording is supplied by the test")
}

private class ShortcutTestScope(
    override val appScope: CoroutineScope,
) : RememberStateFlowScope,
    CoroutineScope by appScope {
    val failures = mutableListOf<String>()
    override val screenScope: CoroutineScope = appScope
    override val screenId: String = "shortcuts_test"
    override val screenName: String = "shortcuts_test"
    override val context: LeContext get() = error("Unused")
    override val colorScheme: ColorScheme get() = error("Unused")
    override val isStartedFlow: Flow<Boolean> = MutableStateFlow(true)

    override fun screenExecutor() =
        LoadingTask(
            translator = this,
            scope = appScope,
            exceptionHandler = { exception ->
                val title = exception.message.orEmpty()
                failures += title
                ReadableExceptionMessage(title = title)
            },
        )

    override suspend fun translate(res: StringResource): String = error("Unused")

    override suspend fun translate(
        res: StringResource,
        vararg args: Any,
    ): String = error("Unused")

    override suspend fun translate(
        res: PluralStringResource,
        quantity: Int,
        vararg args: Any,
    ): String = error("Unused")

    val navigation = mutableListOf<NavigationIntent>()

    override fun navigate(intent: NavigationIntent) {
        navigation += intent
    }

    override fun copier(): CopyText = error("Unused")

    override fun message(message: ToastMessage): Unit = error("Unused")

    override fun message(exception: Throwable): Unit = error("Unused")

    override fun interceptExit(
        isEnabledFlow: Flow<Boolean>,
        callback: ((Boolean) -> Unit) -> Unit,
    ): Unit = error("Unused")

    override fun interceptBackPress(interceptorFlow: Flow<(() -> Unit)?>): () -> Unit = error("Unused")

    override fun interceptKeyEvent(interceptorFlow: Flow<((KeyEvent) -> Boolean)?>): () -> Unit = error("Unused")

    override fun launchUi(block: suspend CoroutineScope.() -> Unit): () -> Unit = error("Unused")

    override fun action(block: suspend () -> Unit): Unit = error("Unused")

    override suspend fun loadDiskHandle(
        key: String,
        global: Boolean,
    ): DiskHandle = error("Unused")

    override fun <T> mutablePersistedFlow(
        key: String,
        storage: PersistedStorage,
        initialValue: () -> T,
    ): MutableStateFlow<T> = error("Unused")

    override fun <T, S> mutablePersistedFlow(
        key: String,
        storage: PersistedStorage,
        serialize: (Json, T) -> S,
        deserialize: (Json, S) -> T,
        initialValue: () -> T,
    ): MutableStateFlow<T> = error("Unused")

    override fun clearPersistedFlow(key: String): Unit = error("Unused")

    override fun persistedState(): LeBundle = error("Unused")
}
