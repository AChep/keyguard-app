package com.artemchep.keyguard.common.service.shortcut

import com.artemchep.autotype.GlobalHotKeyKey
import com.artemchep.autotype.GlobalHotKeyRegistration
import com.artemchep.autotype.GlobalHotKeyRegistrationFailureReason
import com.artemchep.autotype.GlobalHotKeyRegistrationResult
import com.artemchep.autotype.GlobalHotKeySpec
import com.artemchep.keyguard.common.model.GlobalShortcutAction
import com.artemchep.keyguard.common.model.GlobalShortcutBinding
import com.artemchep.keyguard.common.model.GlobalShortcutConfig
import com.artemchep.keyguard.common.model.GlobalShortcutKey
import com.artemchep.keyguard.common.model.GlobalShortcutsConfig
import com.artemchep.keyguard.common.model.defaultGlobalShortcut
import com.artemchep.keyguard.platform.Platform
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
// Keep the controller lifecycle scenarios together with their shared fixture.
@Suppress("TooManyFunctions")
class DesktopGlobalShortcutControllerTest {
    private val custom = GlobalShortcutBinding(GlobalShortcutKey.K, ctrl = true, alt = true)

    private class Fixture(
        initial: GlobalShortcutsConfig,
        dispatcher: CoroutineDispatcher,
        beforeNativeCall: () -> Unit,
    ) {
        data class Handle(
            val spec: GlobalHotKeySpec,
            val callback: () -> Unit,
            val beforeNativeCall: () -> Unit,
        ) : GlobalHotKeyRegistration {
            var closed = false

            override fun unregister(): Boolean {
                beforeNativeCall()
                closed = true
                return true
            }
        }

        val configs = MutableStateFlow(initial)
        val handles = mutableListOf<Handle>()
        val presses = mutableListOf<GlobalShortcutAction>()
        val rejected = mutableSetOf<GlobalHotKeySpec>()
        var beforePersist: suspend () -> Unit = {}
        var failWrites = false
        var writeFailure: Throwable = IllegalStateException("disk full")
        var attempts = 0
        var failureReason = GlobalHotKeyRegistrationFailureReason.Unavailable
        val controller =
            DesktopGlobalShortcutController(
                configurations = configs,
                persist = { action, config ->
                    beforePersist()
                    if (failWrites) throw writeFailure else configs.value = configs.value.with(action, config)
                },
                registrar =
                    GlobalHotKeyRegistrar { spec, callback ->
                        beforeNativeCall()
                        attempts++
                        if (handles.any { !it.closed && it.spec == spec }) {
                            GlobalHotKeyRegistrationResult.Failure(GlobalHotKeyRegistrationFailureReason.Unavailable)
                        } else if (spec in rejected) {
                            GlobalHotKeyRegistrationResult.Failure(failureReason)
                        } else {
                            val handle = Handle(spec, callback, beforeNativeCall)
                            handles.add(handle)
                            GlobalHotKeyRegistrationResult.Success(handle)
                        }
                    },
                platform = Platform.Desktop.Windows,
                dispatcher = dispatcher,
            )

        fun start(scope: TestScope): Job {
            scope.backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                controller.presses.collect { presses.add(it) }
            }
            return scope.backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { controller.run() }
        }

        fun active() = handles.filterNot { it.closed }
    }

    private fun TestScope.fixture(
        initial: GlobalShortcutsConfig = GlobalShortcutsConfig(),
        dispatcher: CoroutineDispatcher = coroutineContext[ContinuationInterceptor] as CoroutineDispatcher,
        beforeNativeCall: () -> Unit = {},
    ) = Fixture(
        initial = initial,
        dispatcher = dispatcher,
        beforeNativeCall = beforeNativeCall,
    )

    @Test
    fun `all native operations run on the controller dispatcher`() =
        runTest {
            val callerThread = Thread.currentThread()
            val nativeThreads = ConcurrentLinkedQueue<Thread>()
            Executors
                .newSingleThreadExecutor { runnable ->
                    Thread(runnable, "global-shortcut-test")
                }.asCoroutineDispatcher()
                .use { dispatcher ->
                    val f =
                        fixture(
                            initial = GlobalShortcutsConfig(quickSearch = GlobalShortcutConfig(binding = custom)),
                            dispatcher = dispatcher,
                            beforeNativeCall = { nativeThreads.add(Thread.currentThread()) },
                        )
                    f.rejected.add(custom.toNative())
                    val job = f.start(this)
                    try {
                        f.controller.state.first { it.statuses.size == GlobalShortcutAction.entries.size }
                        assertEquals(
                            GlobalShortcutStatus.Failed(GlobalShortcutError.Unavailable),
                            f.controller.state.value.statuses[GlobalShortcutAction.QuickSearch],
                        )
                        f.rejected.clear()
                        assertNull(f.controller.update(GlobalShortcutAction.QuickSearch) { GlobalShortcutConfig() })
                        val recording = f.controller.suspendRecording()
                        assertTrue(f.active().isEmpty())
                        recording.release()
                        assertEquals(2, f.active().size)
                    } finally {
                        job.cancelAndJoin()
                    }
                    assertTrue(f.active().isEmpty())
                    assertTrue(nativeThreads.isNotEmpty())
                    assertTrue(nativeThreads.all { it !== callerThread && it.name == "global-shortcut-test" })
                }
        }

    @Test fun `loads disabled settings before registering and cleans up on shutdown`() =
        runTest {
            val f = fixture(GlobalShortcutsConfig(quickSearch = GlobalShortcutConfig(enabled = false)))
            val job = f.start(this)
            assertEquals(1, f.attempts)
            assertEquals(
                GlobalShortcutStatus.Disabled,
                f.controller.state.value.statuses[GlobalShortcutAction.QuickSearch],
            )
            job.cancelAndJoin()
            assertTrue(f.active().isEmpty())
            f.handles.single().callback()
            runCurrent()
            assertTrue(f.presses.isEmpty())
        }

    @Test fun `replacement disables old callback and preserves the other action`() =
        runTest {
            val f = fixture()
            f.start(this)
            val old = f.handles.first()
            assertNull(f.controller.update(GlobalShortcutAction.QuickSearch) { GlobalShortcutConfig(binding = custom) })
            assertTrue(old.closed)
            assertFalse(f.handles[1].closed)
            old.callback()
            f.handles.last().callback()
            runCurrent()
            assertEquals(listOf(GlobalShortcutAction.QuickSearch), f.presses)
            assertEquals(custom, f.configs.value.quickSearch.binding)
        }

    @Test fun `disable keeps custom binding and enabling registers it`() =
        runTest {
            val f = fixture()
            f.start(this)
            val config = GlobalShortcutConfig(enabled = false, binding = custom)
            assertNull(f.controller.update(GlobalShortcutAction.QuickSearch) { config })
            assertEquals(1, f.active().size)
            assertEquals(config, f.configs.value.quickSearch)
            assertNull(f.controller.update(GlobalShortcutAction.QuickSearch) { config.copy(enabled = true) })
            assertEquals(custom.toNative(), f.active().last().spec)
        }

    @Test fun `failed candidate preserves saved and active binding`() =
        runTest {
            val f = fixture()
            f.start(this)
            f.rejected.add(custom.toNative())
            assertEquals(
                GlobalShortcutError.Unavailable,
                f.controller.update(GlobalShortcutAction.QuickSearch) { GlobalShortcutConfig(binding = custom) },
            )
            assertEquals(GlobalShortcutsConfig(), f.configs.value)
            assertEquals(2, f.active().size)
            assertFalse(f.handles.first().closed)
        }

    @Test fun `persistence failure releases candidate and keeps previous binding`() =
        runTest {
            val f = fixture()
            f.start(this)
            f.failWrites = true
            assertEquals(
                GlobalShortcutError.Persistence,
                f.controller.update(GlobalShortcutAction.QuickSearch) { GlobalShortcutConfig(binding = custom) },
            )
            assertEquals(GlobalShortcutsConfig(), f.configs.value)
            assertEquals(2, f.active().size)
            assertTrue(f.active().none { it.spec == custom.toNative() })
            assertEquals(
                GlobalShortcutStatus.Active,
                f.controller.state.value.statuses[GlobalShortcutAction.QuickSearch],
            )
        }

    @Test fun `duplicate names other action but is allowed while disabled`() =
        runTest {
            val f = fixture()
            f.start(this)
            val duplicate =
                GlobalShortcutConfig(
                    binding = defaultGlobalShortcut(GlobalShortcutAction.LockVault, Platform.Desktop.Windows),
                )
            assertEquals(
                GlobalShortcutError.Duplicate(GlobalShortcutAction.LockVault),
                f.controller.update(GlobalShortcutAction.QuickSearch) { duplicate },
            )
            assertNull(f.controller.update(GlobalShortcutAction.QuickSearch) { duplicate.copy(enabled = false) })
            assertEquals(
                GlobalShortcutError.Duplicate(GlobalShortcutAction.LockVault),
                f.controller.update(GlobalShortcutAction.QuickSearch) { duplicate },
            )
        }

    @Test fun `recording suspends both actions rejects queued callbacks and restores once`() =
        runTest {
            val f = fixture()
            f.start(this)
            val recording = f.controller.suspendRecording()
            assertTrue(f.active().isEmpty())
            f.handles.forEach { it.callback() }
            runCurrent()
            assertTrue(f.presses.isEmpty())
            recording.release()
            recording.release()
            assertEquals(2, f.active().size)
            assertEquals(4, f.attempts)
            f.active().forEach { it.callback() }
            runCurrent()
            assertEquals(GlobalShortcutAction.entries.toList(), f.presses)
        }

    @Test fun `overlapping recorder disposal only resumes after last lease`() =
        runTest {
            val f = fixture()
            f.start(this)
            val a = f.controller.suspendRecording()
            val b = f.controller.suspendRecording()
            a.release()
            assertTrue(f.active().isEmpty())
            b.release()
            assertEquals(2, f.active().size)
        }

    @Test fun `recording current shortcut does not conflict or replace it twice`() =
        runTest {
            val f = fixture()
            f.start(this)
            val binding = defaultGlobalShortcut(GlobalShortcutAction.QuickSearch, Platform.Desktop.Windows)
            f.controller.suspendRecording().release()
            val attempts = f.attempts
            assertNull(f.controller.update(GlobalShortcutAction.QuickSearch) { it.copy(binding = binding) })
            assertEquals(attempts, f.attempts)
        }

    @Test fun `startup failure does not overwrite preferences and saving again retries`() =
        runTest {
            val initial = GlobalShortcutsConfig(quickSearch = GlobalShortcutConfig(binding = custom))
            val f = fixture(initial)
            f.rejected.add(custom.toNative())
            f.failureReason = GlobalHotKeyRegistrationFailureReason.UnsupportedSession
            f.start(this)
            assertEquals(
                GlobalShortcutStatus.Failed(GlobalShortcutError.UnsupportedSession),
                f.controller.state.value.statuses[GlobalShortcutAction.QuickSearch],
            )
            assertEquals(initial, f.configs.value)
            f.rejected.clear()
            assertNull(f.controller.update(GlobalShortcutAction.QuickSearch) { initial.quickSearch })
            assertEquals(2, f.active().size)
            assertEquals(initial, f.configs.value)
        }

    @Test fun `external settings restore reconciles registrations`() =
        runTest {
            val f = fixture()
            f.start(this)
            f.configs.value =
                GlobalShortcutsConfig(
                    quickSearch = GlobalShortcutConfig(enabled = false),
                    lockVault = GlobalShortcutConfig(binding = custom),
                )
            runCurrent()
            assertEquals(custom.toNative(), f.active().single().spec)
        }

    @Test fun `external settings restore can swap bindings or transfer one from a disabled action`() =
        runTest {
            val quickSearchBinding = defaultGlobalShortcut(GlobalShortcutAction.QuickSearch, Platform.Desktop.Windows)
            val lockVaultBinding = defaultGlobalShortcut(GlobalShortcutAction.LockVault, Platform.Desktop.Windows)
            for (lockEnabled in listOf(true, false)) {
                val f = fixture()
                f.start(this)
                val oldHandles = f.active().toList()
                f.configs.value =
                    GlobalShortcutsConfig(
                        quickSearch = GlobalShortcutConfig(binding = lockVaultBinding),
                        lockVault = GlobalShortcutConfig(enabled = lockEnabled, binding = quickSearchBinding),
                    )
                runCurrent()

                assertTrue(oldHandles.all { it.closed })
                assertEquals(if (lockEnabled) 2 else 1, f.active().size)
                assertEquals(
                    GlobalShortcutStatus.Active,
                    f.controller.state.value.statuses[GlobalShortcutAction.QuickSearch],
                )
                assertEquals(
                    if (lockEnabled) GlobalShortcutStatus.Active else GlobalShortcutStatus.Disabled,
                    f.controller.state.value.statuses[GlobalShortcutAction.LockVault],
                )
                oldHandles.forEach { it.callback() }
                f.active().single { it.spec == lockVaultBinding.toNative() }.callback()
                runCurrent()
                assertEquals(listOf(GlobalShortcutAction.QuickSearch), f.presses)
            }
        }

    @Test fun `invalid restored binding can still be disabled`() =
        runTest {
            val invalid = GlobalShortcutConfig(binding = GlobalShortcutBinding(GlobalShortcutKey.A))
            val f = fixture(GlobalShortcutsConfig(quickSearch = invalid))
            f.start(this)
            assertEquals(
                GlobalShortcutStatus.Failed(GlobalShortcutError.Invalid),
                f.controller.state.value.statuses[GlobalShortcutAction.QuickSearch],
            )
            assertNull(f.controller.update(GlobalShortcutAction.QuickSearch) { invalid.copy(enabled = false) })
            assertFalse(f.configs.value.quickSearch.enabled)
            assertEquals(invalid.binding, f.configs.value.quickSearch.binding)
            assertEquals(1, f.active().size)
        }

    @Test fun `every shortcut key maps to a native hot key by name`() {
        assertEquals(GlobalHotKeyKey.entries.map { it.name }, GlobalShortcutKey.entries.map { it.name })
    }

    @Test fun `reports a failed disk rollback explicitly`() =
        runTest {
            val f = fixture()
            f.start(this)
            f.failWrites = true
            f.writeFailure =
                GlobalShortcutPersistenceRollbackException(
                    IllegalStateException("write failed"),
                    IllegalStateException("rollback failed"),
                )
            assertEquals(
                GlobalShortcutError.PersistenceRestoration,
                f.controller.update(GlobalShortcutAction.QuickSearch) { GlobalShortcutConfig(binding = custom) },
            )
            assertEquals(2, f.active().size)
            assertEquals(GlobalShortcutsConfig(), f.controller.state.value.config)
        }

    @Test
    fun `resolving restored duplicates reconciles both actions`() =
        runTest {
            val changes =
                listOf(
                    GlobalShortcutConfig(enabled = false, binding = custom),
                    GlobalShortcutConfig(binding = custom.copy(key = GlobalShortcutKey.J)),
                    GlobalShortcutConfig(),
                )
            for (change in changes) {
                val duplicate = GlobalShortcutConfig(binding = custom)
                val f = fixture(GlobalShortcutsConfig(quickSearch = duplicate, lockVault = duplicate))
                val job = f.start(this)
                assertTrue(f.active().isEmpty())

                assertNull(f.controller.update(GlobalShortcutAction.QuickSearch) { change })
                runCurrent()

                assertEquals(
                    GlobalShortcutStatus.Active,
                    f.controller.state.value.statuses[GlobalShortcutAction.LockVault],
                )
                assertEquals(if (change.enabled) 2 else 1, f.active().size)
                f.active().single { it.spec == custom.toNative() }.callback()
                runCurrent()
                assertEquals(listOf(GlobalShortcutAction.LockVault), f.presses)
                job.cancelAndJoin()
            }
        }

    @Test
    fun `publishes configuration and statuses together and ignores provisional callbacks`() =
        runTest {
            val f = fixture()
            f.start(this)
            val observed = mutableListOf<GlobalShortcutRuntimeState>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.controller.state.collect { observed.add(it) }
            }
            val persistStarted = CompletableDeferred<Unit>()
            val finishPersist = CompletableDeferred<Unit>()
            f.beforePersist = {
                persistStarted.complete(Unit)
                finishPersist.await()
            }
            val update =
                launch {
                    assertNull(
                        f.controller.update(GlobalShortcutAction.QuickSearch) { it.copy(binding = custom) },
                    )
                }
            persistStarted.await()
            assertEquals(1, observed.size)
            f.handles.last().callback()
            finishPersist.complete(Unit)
            update.join()
            runCurrent()
            assertTrue(f.presses.isEmpty())
            assertEquals(2, observed.size)
            assertEquals(
                custom,
                observed
                    .last()
                    .config
                    ?.quickSearch
                    ?.binding,
            )
            assertEquals(GlobalShortcutStatus.Active, observed.last().statuses[GlobalShortcutAction.QuickSearch])
            assertEquals(GlobalShortcutStatus.Active, observed.last().statuses[GlobalShortcutAction.LockVault])
        }

    @Test
    fun `cancelled acquisition returns its lease for cleanup across dispatchers`() =
        runTest {
            var acquisition: Job? = null
            var cancelOnNativeCall = false
            val f =
                fixture(
                    dispatcher = StandardTestDispatcher(testScheduler, "native"),
                    beforeNativeCall = { if (cancelOnNativeCall) acquisition?.cancel() },
                )
            f.start(this)
            runCurrent()
            var received = false
            acquisition =
                launch(StandardTestDispatcher(testScheduler, "caller")) {
                    var lease: GlobalShortcutRecording? = null
                    try {
                        lease = f.controller.suspendRecording()
                        received = true
                    } finally {
                        cancelOnNativeCall = false
                        withContext(NonCancellable) { lease?.release() }
                    }
                }
            cancelOnNativeCall = true
            acquisition.join()
            assertTrue(received)
            assertEquals(
                GlobalShortcutStatus.Active,
                f.controller.state.value.statuses[GlobalShortcutAction.QuickSearch],
            )
            assertEquals(2, f.active().size)
        }

    @Test
    fun `cancelled release still restores shortcuts across dispatchers`() =
        runTest {
            var releaseJob: Job? = null
            var cancelOnNativeCall = false
            val f =
                fixture(
                    dispatcher = StandardTestDispatcher(testScheduler, "native"),
                    beforeNativeCall = { if (cancelOnNativeCall) releaseJob?.cancel() },
                )
            f.start(this)
            runCurrent()
            val lease = f.controller.suspendRecording()
            var returned = false
            releaseJob =
                launch(StandardTestDispatcher(testScheduler, "caller")) {
                    lease.release()
                    returned = true
                }
            cancelOnNativeCall = true
            releaseJob.join()
            assertTrue(returned)
            assertEquals(
                GlobalShortcutStatus.Active,
                f.controller.state.value.statuses[GlobalShortcutAction.QuickSearch],
            )
            assertEquals(2, f.active().size)
        }

    @Test
    fun `saving while recording probes the binding and registers it after release`() =
        runTest {
            val f = fixture()
            f.start(this)
            val lease = f.controller.suspendRecording()
            assertNull(f.controller.update(GlobalShortcutAction.QuickSearch) { GlobalShortcutConfig(binding = custom) })
            assertEquals(custom.toNative(), f.handles.last().spec)
            assertTrue(f.active().isEmpty())
            assertEquals(custom, f.configs.value.quickSearch.binding)
            assertEquals(
                GlobalShortcutStatus.Recording,
                f.controller.state.value.statuses[GlobalShortcutAction.QuickSearch],
            )
            lease.release()
            val lockVault = defaultGlobalShortcut(GlobalShortcutAction.LockVault, Platform.Desktop.Windows)
            assertEquals(setOf(custom.toNative(), lockVault.toNative()), f.active().map { it.spec }.toSet())
        }

    @Test
    fun `saving an unavailable binding while recording keeps the preference`() =
        runTest {
            val f = fixture()
            f.start(this)
            f.rejected.add(custom.toNative())
            val lease = f.controller.suspendRecording()
            assertEquals(
                GlobalShortcutError.Unavailable,
                f.controller.update(GlobalShortcutAction.QuickSearch) { GlobalShortcutConfig(binding = custom) },
            )
            assertEquals(GlobalShortcutsConfig(), f.configs.value)
            lease.release()
            assertEquals(2, f.active().size)
        }

    @Test
    fun `overlapping runs share registrations until the last one stops`() =
        runTest {
            val f = fixture()
            val first = f.start(this)
            val second = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { f.controller.run() }
            assertEquals(2, f.attempts)
            first.cancelAndJoin()
            assertEquals(2, f.active().size)
            second.cancelAndJoin()
            assertTrue(f.active().isEmpty())
        }

    @Test
    fun `a press handler can update shortcuts`() =
        runTest {
            val f = fixture()
            f.start(this)
            val result = CompletableDeferred<GlobalShortcutError?>()
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                f.controller.presses.first()
                result.complete(
                    f.controller.update(GlobalShortcutAction.QuickSearch) { GlobalShortcutConfig(binding = custom) },
                )
            }
            f.handles.first().callback()
            runCurrent()
            assertNull(result.await())
            assertEquals(custom, f.configs.value.quickSearch.binding)
        }

    @Test
    fun `a value collected before an update cannot undo it`() =
        runTest {
            val f = fixture()
            f.start(this)
            assertNull(f.controller.update(GlobalShortcutAction.QuickSearch) { it.copy(enabled = false) })
            // The collector takes the first saved value, then waits for the second update.
            f.beforePersist = { yield() }
            assertNull(f.controller.update(GlobalShortcutAction.QuickSearch) { GlobalShortcutConfig(binding = custom) })
            runCurrent()

            val registration = f.handles.single { it.spec == custom.toNative() }
            assertFalse(registration.closed)
            assertEquals(custom, f.controller.state.value.config?.quickSearch?.binding)
        }

    @Test
    fun `update edits the saved value before it is collected`() =
        runTest {
            val f = fixture()
            f.start(this)
            f.configs.value = GlobalShortcutsConfig(quickSearch = GlobalShortcutConfig(binding = custom))
            assertNull(f.controller.update(GlobalShortcutAction.QuickSearch) { it.copy(enabled = false) })
            assertEquals(GlobalShortcutConfig(enabled = false, binding = custom), f.configs.value.quickSearch)
        }
}
