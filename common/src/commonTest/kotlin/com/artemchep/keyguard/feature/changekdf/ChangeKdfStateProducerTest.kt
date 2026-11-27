package com.artemchep.keyguard.feature.changekdf

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.MasterKdf
import com.artemchep.keyguard.common.model.MasterKdfVersion
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.model.VaultState
import com.artemchep.keyguard.common.model.VaultState.Main.ChangePassword.Change
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.common.service.vault.VaultSession
import com.artemchep.keyguard.common.usecase.MeasureMasterKdfUseCase
import com.artemchep.keyguard.common.usecase.UnlockUseCase
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.masterkdf.MasterKdfTestScope
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration

private const val PASSWORD = "password"

private val CUSTOM = MasterKdf.Argon2id(
    iterations = 4,
    memoryKib = 131072, // 128 MiB
    parallelism = 2,
)

@OptIn(ExperimentalCoroutinesApi::class)
class ChangeKdfStateProducerTest {
    @Test
    fun `form starts from the key derivation of the vault`() =
        runTest {
            val fixture = fixture(kdf = CUSTOM)

            val state = fixture.current()
            assertEquals("4", state.kdf.iterations.text)
            assertEquals("128", state.kdf.memory.text)
            assertEquals("2", state.kdf.parallelism.text)
            assertEquals(CUSTOM, state.kdf.kdf)
        }

    @Test
    fun `form of a legacy vault starts from the default`() =
        runTest {
            val fixture = fixture(kdf = MasterKdf.Pbkdf2Legacy)

            assertEquals(MasterKdf.Argon2id.DEFAULT, fixture.current().kdf.kdf)
        }

    @Test
    fun `unchanged key derivation can not be saved`() =
        runTest {
            val fixture = fixture(kdf = CUSTOM)

            fixture.current().password.onChange!!.invoke(PASSWORD)

            val state = fixture.current()
            assertNull(state.password.error)
            assertNull(state.onConfirm)
        }

    @Test
    fun `legacy vault can be saved with the default`() =
        runTest {
            val fixture = fixture(kdf = MasterKdf.Pbkdf2Legacy)

            fixture.current().password.onChange!!.invoke(PASSWORD)

            fixture.current().onConfirm!!.invoke()
            runCurrent()
            assertEquals(
                listOf(ChangeCall(PASSWORD, Change.Kdf(MasterKdf.Argon2id.DEFAULT))),
                fixture.calls,
            )
        }

    @Test
    fun `changed key derivation is saved with the same password`() =
        runTest {
            val fixture = fixture(kdf = MasterKdf.Argon2id.DEFAULT)
            fixture.current().password.onChange!!.invoke(PASSWORD)
            fixture.current().kdf.memory.onChange!!.invoke("128")

            fixture.current().onConfirm!!.invoke()
            runCurrent()

            val expected = MasterKdf.Argon2id.DEFAULT.copy(memoryKib = 131072)
            assertEquals(listOf(ChangeCall(PASSWORD, Change.Kdf(expected))), fixture.calls)
            assertEquals(ToastMessage.Type.SUCCESS, fixture.scope.messages.single().type)
            assertIs<NavigationIntent.PopById>(fixture.scope.navigations.single())
        }

    @Test
    fun `invalid input can not be saved`() =
        runTest {
            val fixture = fixture(kdf = MasterKdf.Argon2id.DEFAULT)
            fixture.current().kdf.memory.onChange!!.invoke("128")

            // Too short.
            fixture.current().password.onChange!!.invoke("12345")
            val short = fixture.current()
            assertNotNull(short.password.error)
            assertNull(short.onConfirm)

            fixture.current().password.onChange!!.invoke(PASSWORD)
            assertNotNull(fixture.current().onConfirm)

            // Out of range.
            fixture.current().kdf.iterations.onChange!!.invoke("1")
            assertNull(fixture.current().onConfirm)
        }

    @Test
    fun `a callback of an older state saves the latest input`() =
        runTest {
            val fixture = fixture(kdf = MasterKdf.Argon2id.DEFAULT)
            fixture.current().password.onChange!!.invoke(PASSWORD)
            fixture.current().kdf.memory.onChange!!.invoke("128")
            val onConfirm = fixture.current().onConfirm!!

            // Back to the current key derivation: nothing to save.
            fixture.current().kdf.memory.onChange!!.invoke("64")
            onConfirm()
            runCurrent()
            assertTrue(fixture.calls.isEmpty())

            fixture.current().kdf.memory.onChange!!.invoke("256")
            onConfirm()
            runCurrent()
            val expected = MasterKdf.Argon2id.DEFAULT.copy(memoryKib = 262144)
            assertEquals(listOf(ChangeCall(PASSWORD, Change.Kdf(expected))), fixture.calls)
        }

    @Test
    fun `a running test blocks saving`() =
        runTest {
            val gate = CompletableDeferred<Duration>()
            val fixture = fixture(
                kdf = MasterKdf.Argon2id.DEFAULT,
                measure = { gate.await() },
            )
            fixture.current().password.onChange!!.invoke(PASSWORD)
            fixture.current().kdf.memory.onChange!!.invoke("128")
            val onConfirm = fixture.current().onConfirm!!

            fixture.current().kdf.test.onTest!!.invoke()
            runCurrent()
            assertNull(fixture.current().onConfirm)

            onConfirm()
            runCurrent()
            assertTrue(fixture.calls.isEmpty())

            gate.complete(Duration.ZERO)
            runCurrent()
            assertNotNull(fixture.current().onConfirm)
        }

    @Test
    fun `later vault states keep the edits`() =
        runTest {
            val fixture = fixture(kdf = MasterKdf.Argon2id.DEFAULT)
            fixture.current().kdf.memory.onChange!!.invoke("128")

            fixture.vaultState.value = vaultState(kdf = CUSTOM, calls = fixture.calls)

            val state = fixture.current()
            assertEquals("3", state.kdf.iterations.text)
            assertEquals("128", state.kdf.memory.text)
            assertEquals("4", state.kdf.parallelism.text)
        }

    @Test
    fun `a failed change is reported as a message and keeps the screen`() =
        runTest {
            val error = IllegalStateException("Wrong password")
            val fixture = fixture(kdf = MasterKdf.Argon2id.DEFAULT, failure = error)
            fixture.current().password.onChange!!.invoke(PASSWORD)
            fixture.current().kdf.memory.onChange!!.invoke("128")

            fixture.current().onConfirm!!.invoke()
            runCurrent()

            assertSame(error, fixture.scope.errors.single())
            assertTrue(fixture.scope.messages.isEmpty())
            assertTrue(fixture.scope.navigations.isEmpty())
            val state = fixture.current()
            assertFalse(state.isLoading)
            assertNotNull(state.onConfirm)
        }

    private data class ChangeCall(
        val currentPassword: String,
        val change: Change,
    )

    private class Fixture(
        val scope: MasterKdfTestScope,
        val stateFlow: Flow<Loadable<ChangeKdfState>>,
        val vaultState: MutableStateFlow<VaultState>,
        val calls: MutableList<ChangeCall>,
    ) {
        suspend fun current(): ChangeKdfState = stateFlow.first().getOrNull()!!
    }

    // The screen scope is the background scope of the test: run its
    // work with runCurrent(), as advanceUntilIdle() skips it.
    private suspend fun TestScope.fixture(
        kdf: MasterKdf,
        failure: Throwable? = null,
        measure: suspend () -> Duration = { Duration.ZERO },
    ): Fixture {
        val scope = MasterKdfTestScope(backgroundScope)
        val calls = mutableListOf<ChangeCall>()
        val vaultState = MutableStateFlow<VaultState>(
            vaultState(
                kdf = kdf,
                calls = calls,
                failure = failure,
            ),
        )
        val unlockUseCase = object : UnlockUseCase {
            override fun invoke(): Flow<VaultState> = vaultState
        }
        val measureMasterKdf = object : MeasureMasterKdfUseCase {
            override fun invoke(kdf: MasterKdf.Argon2id): IO<Duration> = ioEffect {
                measure()
            }
        }
        val windowCoroutineScope = object : WindowCoroutineScope,
            CoroutineScope by backgroundScope {}
        val stateFlow = scope.changeKdfStateProducer(
            unlockUseCase = unlockUseCase,
            measureMasterKdf = measureMasterKdf,
            windowCoroutineScope = windowCoroutineScope,
        )
        return Fixture(
            scope = scope,
            stateFlow = stateFlow,
            vaultState = vaultState,
            calls = calls,
        )
    }

    private fun vaultState(
        kdf: MasterKdf,
        calls: MutableList<ChangeCall>,
        failure: Throwable? = null,
    ): VaultState.Main {
        val getCreateIo = { currentPassword: String, change: Change ->
            ioEffect {
                calls += ChangeCall(currentPassword, change)
                if (failure != null) throw failure
                VaultState.Main.ChangePassword.Report()
            }
        }
        val masterKey = MasterKey(MasterKdfVersion.V1, byteArrayOf(1))
        return VaultState.Main(
            masterKey = masterKey,
            changePassword = VaultState.Main.ChangePassword(
                key = kdf,
                kdf = kdf,
                withMasterPassword = VaultState.Main.ChangePassword.WithPassword(
                    getCreateIo = getCreateIo,
                ),
            ),
            session = TestVaultSession,
        )
    }
}

private object TestVaultSession : VaultSession {
    override val id: String = "change_kdf_test"
    override val active: StateFlow<Boolean> = MutableStateFlow(true)

    override fun retire() = Unit

    override fun close() = Unit
}
