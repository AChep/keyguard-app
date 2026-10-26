package com.artemchep.keyguard.common.usecase.impl

import arrow.core.Either
import com.artemchep.keyguard.common.exception.GlobalShortcutException
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.GlobalShortcutAction
import com.artemchep.keyguard.common.model.GlobalShortcutConfig
import com.artemchep.keyguard.common.service.shortcut.GlobalShortcutController
import com.artemchep.keyguard.common.service.shortcut.GlobalShortcutError
import com.artemchep.keyguard.common.service.shortcut.GlobalShortcutRecording
import com.artemchep.keyguard.common.service.shortcut.GlobalShortcutRuntimeState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

class PutGlobalShortcutImplTest {
    @Test
    fun `update is deferred and succeeds with Unit`() =
        runTest {
            var calls = 0
            val config = GlobalShortcutConfig(enabled = false)
            val put =
                PutGlobalShortcutImpl(
                    TestController { action, value ->
                        assertEquals(GlobalShortcutAction.LockVault, action)
                        assertEquals(config, value)
                        calls++
                        null
                    },
                )
            val io = put(GlobalShortcutAction.LockVault) { config }
            assertEquals(0, calls)
            assertEquals(Unit, io.bind())
            assertEquals(1, calls)
        }

    @Test
    fun `domain failures use the IO exception channel`() =
        runTest {
            val errors =
                listOf(
                    GlobalShortcutError.Duplicate(GlobalShortcutAction.LockVault),
                    GlobalShortcutError.Invalid,
                    GlobalShortcutError.UnsupportedPlatform,
                    GlobalShortcutError.UnsupportedSession,
                    GlobalShortcutError.Unavailable,
                    GlobalShortcutError.Registration,
                    GlobalShortcutError.Persistence,
                    GlobalShortcutError.PersistenceRestoration,
                )
            for (error in errors) {
                val put = PutGlobalShortcutImpl(TestController { _, _ -> error })
                val result = put(GlobalShortcutAction.QuickSearch) { it }.attempt().bind()
                val exception = assertIs<GlobalShortcutException>(assertIs<Either.Left<Throwable>>(result).value)
                assertEquals(error, exception.error)
            }
        }

    @Test
    fun `unexpected exceptions propagate unchanged`() =
        runTest {
            val failure = IllegalStateException("Unexpected")
            val put = PutGlobalShortcutImpl(TestController { _, _ -> throw failure })
            val result = put(GlobalShortcutAction.QuickSearch) { it }.attempt().bind()
            assertSame(failure, assertIs<Either.Left<Throwable>>(result).value)
        }

    @Test
    fun `cancellation propagates unchanged through attempt`() =
        runTest {
            val cancellation = CancellationException("Cancelled")
            val put = PutGlobalShortcutImpl(TestController { _, _ -> throw cancellation })
            val actual =
                assertFailsWith<CancellationException> {
                    put(GlobalShortcutAction.QuickSearch) { it }.attempt().bind()
                }
            assertSame(cancellation, actual)
        }

    private class TestController(
        val update: suspend (GlobalShortcutAction, GlobalShortcutConfig) -> GlobalShortcutError?,
    ) : GlobalShortcutController {
        override val state = MutableStateFlow(GlobalShortcutRuntimeState())

        override suspend fun update(
            action: GlobalShortcutAction,
            transform: (GlobalShortcutConfig) -> GlobalShortcutConfig,
        ) = update.invoke(action, transform(GlobalShortcutConfig()))

        override suspend fun suspendRecording(): GlobalShortcutRecording = error("Unused")
    }
}
