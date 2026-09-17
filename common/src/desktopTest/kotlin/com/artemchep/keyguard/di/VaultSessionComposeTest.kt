package com.artemchep.keyguard.di

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import com.artemchep.keyguard.common.model.MasterKdfVersion
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.service.vault.VaultSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.koin.compose.KoinContext
import org.koin.compose.koinInject
import org.koin.core.Koin
import org.koin.dsl.module

@OptIn(ExperimentalCoroutinesApi::class)
class VaultSessionComposeTest {
    @Test
    fun `disposing one composition leaves its shared session alive and retirement removes content`() = runTest {
        withHarness {
            val session = factory.create(masterKey)
            val trigger = mutableIntStateOf(0)
            var firstActive = false
            var secondActive = false
            var secondReads = 0
            var lastTick = -1
            var firstService: Service? = null
            var secondService: Service? = null
            val first = compose {
                VaultSessionContent(session) {
                    val service = koinInject<Service>()
                    DisposableEffect(Unit) {
                        firstActive = true
                        onDispose { firstActive = false }
                    }
                    SideEffect { firstService = service }
                }
            }
            compose {
                VaultSessionContent(session) {
                    val tick = trigger.intValue
                    val service = koinInject<Service>()
                    DisposableEffect(Unit) {
                        secondActive = true
                        onDispose { secondActive = false }
                    }
                    SideEffect {
                        secondService = service
                        secondReads++
                        lastTick = tick
                    }
                }
            }
            frame()
            assertTrue(firstActive)
            assertTrue(secondActive)
            assertNotNull(firstService)
            assertSame(firstService, secondService)

            first.dispose()
            frame()
            assertFalse(firstActive)
            assertTrue(secondActive)
            assertTrue(session.active.value)
            assertSame(secondService, session.resolve { get<Service>() })

            val readsBeforeRetirement = secondReads
            session.retire()
            trigger.intValue++
            frame()

            assertFalse(secondActive)
            assertEquals(readsBeforeRetirement, secondReads)
            assertEquals(0, lastTick)
            assertNull(session.resolve { get<Service>() })
        }
    }

    @Test
    fun `a new session generation recreates remembered content and injected services`() = runTest {
        withHarness {
            val first = factory.create(masterKey)
            val second = factory.create(masterKey)
            val current = mutableStateOf<VaultSession>(first)
            var remembered: Any? = null
            var injected: Service? = null
            compose {
                VaultSessionContent(current.value) {
                    val token = remember { Any() }
                    val service = koinInject<Service>()
                    SideEffect {
                        remembered = token
                        injected = service
                    }
                }
            }
            frame()
            val firstRemembered = assertNotNull(remembered)
            val firstService = assertNotNull(injected)

            current.value = second
            frame()

            assertNotSame(firstRemembered, remembered)
            assertNotSame(firstService, injected)
            assertSame(injected, second.resolve { get<Service>() })
            assertTrue(first.active.value)
            assertTrue(second.active.value)
        }
    }

    private suspend fun TestScope.withHarness(block: suspend Harness.() -> Unit) {
        val harness = Harness(this)
        try {
            harness.block()
        } finally {
            harness.close()
        }
    }

    private class Harness(private val testScope: TestScope) {
        private val koin = Koin().apply { loadModules(listOf(TestModule().module)) }
        val factory = KoinVaultSessionFactory(koin)
        private val clock = BroadcastFrameClock()
        private val recomposer = Recomposer(testScope.backgroundScope.coroutineContext + clock)
        private val runner = testScope.backgroundScope.launch(clock) {
            recomposer.runRecomposeAndApplyChanges()
        }
        private val compositions = mutableListOf<Composition>()
        private var frameTime = 0L

        fun compose(content: @Composable () -> Unit): Composition =
            Composition(NoOpApplier(), recomposer).also { composition ->
                compositions += composition
                composition.setContent { KoinContext(koin, content = content) }
            }

        fun frame() {
            Snapshot.sendApplyNotifications()
            testScope.runCurrent()
            // collectAsState may have written snapshot state while the coroutine queue ran.
            Snapshot.sendApplyNotifications()
            testScope.runCurrent()
            clock.sendFrame(++frameTime)
            testScope.runCurrent()
        }

        suspend fun close() {
            compositions.forEach(Composition::dispose)
            recomposer.cancel()
            runner.cancelAndJoin()
            koin.close()
        }
    }

    private class TestModule {
        val module = module { scope<VaultSessionScope> { scoped { Service() } } }
    }

    private class Service

    private class NoOpApplier : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun onClear() = Unit
    }

    private val masterKey = MasterKey(MasterKdfVersion.LATEST, byteArrayOf(1))
}
