package com.artemchep.keyguard.common.usecase.impl

import arrow.core.Either
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.io
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.AuthResult
import com.artemchep.keyguard.common.model.BiometricStatus
import com.artemchep.keyguard.common.model.Fingerprint
import com.artemchep.keyguard.common.model.FingerprintPassword
import com.artemchep.keyguard.common.model.LockReason
import com.artemchep.keyguard.common.model.MasterKdfVersion
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.model.MasterPasswordHash
import com.artemchep.keyguard.common.model.MasterPasswordSalt
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.model.VaultState
import com.artemchep.keyguard.common.service.biometrics.BiometricKeyRepository
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.common.service.logging.LogRepositoryBridge
import com.artemchep.keyguard.common.service.vault.FingerprintReadWriteRepository
import com.artemchep.keyguard.common.service.vault.SessionMetadataReadWriteRepository
import com.artemchep.keyguard.common.service.vault.VaultDatabaseSessionAccess
import com.artemchep.keyguard.common.service.vault.VaultSession
import com.artemchep.keyguard.common.service.vault.VaultSessionFactory
import com.artemchep.keyguard.common.service.vault.testVaultSession
import com.artemchep.keyguard.common.usecase.AuthConfirmMasterKeyUseCase
import com.artemchep.keyguard.common.usecase.AuthGenerateMasterKeyUseCase
import com.artemchep.keyguard.common.usecase.BiometricStatusUseCase
import com.artemchep.keyguard.common.usecase.GetBiometricRemainingDuration
import com.artemchep.keyguard.common.usecase.GetBiometricRequireConfirmation
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.common.usecase.PutVaultSession
import com.artemchep.keyguard.common.usecase.YubiKeyUnlockAvailability
import com.artemchep.keyguard.core.session.usecase.AuthMasterKeyUseCase
import com.artemchep.keyguard.platform.LeBiometricCipher
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

class UnlockUseCaseImplTest {
    @Test
    fun `password creation preserves setup until the unlocked session is delivered`() =
        checkSlowCreation(biometric = false)

    @Test
    fun `biometric creation preserves setup until the unlocked session is delivered`() =
        checkSlowCreation(biometric = true)

    private fun checkSlowCreation(biometric: Boolean) = withFixture(biometric) {
        val creation = startCreation(biometric)
        generating.complete(Unit)
        opening.await()
        assertSetupPreserved()
        assertNotNull(fingerprints.value)
        assertEquals(biometric, fingerprints.value?.biometric != null)

        opened.complete(vaultSession)
        assertIs<Either.Right<Unit>>(creation.await())
        // The write has finished, but GetVaultSession has not delivered it yet.
        // Clearing a Boolean in finally would expose Unlock during this interval.
        assertSetupPreserved()

        sessions.value = published.await()
        assertSame(vaultSession, awaitState<VaultState.Main>().session)
        assertEquals(2, observed.size)

        sessions.value = lockedSession()
        awaitState<VaultState.Unlock>()
    }

    @Test
    fun `creation preserves setup when the session arrives before the fingerprint`() = withFixture {
        deferFingerprintDelivery = true
        val creation = startCreation()
        generating.complete(Unit)
        opening.await()
        opened.complete(vaultSession)
        assertIs<Either.Right<Unit>>(creation.await())

        sessions.value = published.await()
        assertSetupPreserved()

        fingerprints.value = assertNotNull(savedFingerprint)
        assertSame(vaultSession, awaitState<VaultState.Main>().session)
        assertEquals(2, observed.size)
    }

    @Test
    fun `fingerprint write failure returns to setup and permits retry`() = withFixture {
        writeFailure = IllegalStateException("Cannot persist fingerprint")
        val creation = startCreation()
        assertSetupPreserved()
        generating.complete(Unit)

        val reported = assertIs<IllegalStateException>(assertIs<Either.Left<Throwable>>(creation.await()).value)
        assertEquals(writeFailure?.message, reported.message)
        assertNull(fingerprints.value)
        assertFalse(opening.isCompleted)

        writeFailure = null
        val retry = startCreation()
        opening.await()
        opened.complete(vaultSession)
        assertIs<Either.Right<Unit>>(retry.await())
        sessions.value = published.await()
        awaitState<VaultState.Main>()
        assertFalse(observed.any { it is VaultState.Unlock })
    }

    @Test
    fun `database opening failure returns to unlock and preserves the fingerprint`() = withFixture {
        val creation = startCreation()
        assertSetupPreserved()
        generating.complete(Unit)
        opening.await()
        val failure = IllegalStateException("Cannot open database")
        opened.completeExceptionally(failure)

        val reported = assertIs<IllegalStateException>(assertIs<Either.Left<Throwable>>(creation.await()).value)
        assertEquals(failure.message, reported.message)
        awaitState<VaultState.Unlock>()
        assertNotNull(fingerprints.value)
        assertFalse(published.isCompleted)
    }

    @Test
    fun `cancellation before saving returns to setup`() = withFixture {
        val creation = startCreation()
        assertSetupPreserved()
        creation.cancelAndJoin()

        awaitState<VaultState.Create>()
        assertNull(fingerprints.value)
        assertFalse(opening.isCompleted)
    }

    @Test
    fun `cancellation after saving returns to unlock`() = withFixture {
        val creation = startCreation()
        assertSetupPreserved()
        generating.complete(Unit)
        opening.await()
        creation.cancelAndJoin()

        awaitState<VaultState.Unlock>()
        assertNotNull(fingerprints.value)
        assertFalse(published.isCompleted)
    }

    @Test
    fun `a real lock releases the guard even if the unlocked session was never delivered`() = withFixture {
        val creation = startCreation()
        assertSetupPreserved()
        generating.complete(Unit)
        opening.await()
        opened.complete(vaultSession)
        assertIs<Either.Right<Unit>>(creation.await())

        // A stopped or slow collector can miss the intermediate unlocked session.
        sessions.value = lockedSession()
        val state = awaitState<VaultState.Unlock>()
        assertEquals(LockReason.TIMEOUT, state.lockInfo?.type)
    }

    private fun withFixture(
        biometric: Boolean = false,
        block: suspend Fixture.() -> Unit,
    ): Unit = runBlocking {
        withTimeout(10.seconds) {
            val fixture = Fixture(biometric)
            try {
                fixture.block()
            } finally {
                fixture.close()
            }
        }
    }

    private class Fixture(biometric: Boolean) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val vaultSession = testVaultSession()
        private val generationStarted = CompletableDeferred<Unit>()
        val generating = CompletableDeferred<Unit>()
        val opening = CompletableDeferred<Unit>()
        val opened = CompletableDeferred<VaultSession>()
        val published = CompletableDeferred<MasterSession>()
        val fingerprints = MutableStateFlow<Fingerprint?>(null)
        val sessions = MutableStateFlow<MasterSession>(MasterSession.Empty())
        val states = Channel<VaultState>(Channel.UNLIMITED)
        val observed = ConcurrentLinkedQueue<VaultState>()
        var writeFailure: Throwable? = null
        var deferFingerprintDelivery = false
        var savedFingerprint: Fingerprint? = null
        private lateinit var initialCreateState: VaultState.Create
        private val auth = AuthResult(
            version = MasterKdfVersion.LATEST,
            key = MasterKey(MasterKdfVersion.LATEST, byteArrayOf(1)),
            token = FingerprintPassword(
                hash = MasterPasswordHash(MasterKdfVersion.LATEST, byteArrayOf(2)),
                salt = MasterPasswordSalt(byteArrayOf(3)),
            ),
        )
        private val databaseManager = unused<VaultDatabaseManager>()
        private val useCase = UnlockUseCaseImpl(
            sessionFactory = object : VaultSessionFactory {
                override fun create(masterKey: MasterKey): VaultSession = error("Unexpected restoration")
                override suspend fun createAuthenticated(masterKey: MasterKey): VaultSession {
                    assertNotNull(savedFingerprint)
                    opening.complete(Unit)
                    return opened.await()
                }
            },
            vaultDatabaseSessionAccess = VaultDatabaseSessionAccess { databaseManager },
            biometricStatusUseCase = object : BiometricStatusUseCase {
                override fun invoke() = flowOf(
                    if (biometric) BiometricStatus.Available {
                        object : LeBiometricCipher {
                            override val iv = byteArrayOf(4)
                            override fun encode(data: ByteArray) = byteArrayOf(5)
                        }
                    } else BiometricStatus.Unavailable,
                )
            },
            biometricKeyRepository = object : BiometricKeyRepository {
                override fun exists() = io(true)
                override fun delete(): IO<Unit> = error("Unexpected biometric key deletion")
            },
            getVaultSession = object : GetVaultSession {
                override val valueOrNull get() = sessions.value
                override fun invoke() = sessions
            },
            putVaultSession = object : PutVaultSession {
                override fun invoke(session: MasterSession): IO<Unit> = ioEffect {
                    published.complete(session)
                }
            },
            disableBiometric = unused(),
            logRepository = LogRepositoryBridge(emptyList()),
            keyReadWriteRepository = object : FingerprintReadWriteRepository {
                override fun get() = fingerprints
                override fun put(key: Fingerprint?): IO<Unit> = ioEffect {
                    writeFailure?.let { throw it }
                    savedFingerprint = key
                    if (!deferFingerprintDelivery) fingerprints.value = key
                }
            },
            sessionMetadataReadWriteRepository = object : SessionMetadataReadWriteRepository {
                override fun getLastPasswordUseTimestamp() = flowOf<Instant?>(null)
                override fun setLastPasswordUseTimestamp(instant: Instant?) = io(Unit)
            },
            getBiometricRequireConfirmation = object : GetBiometricRequireConfirmation {
                override fun invoke() = flowOf(false)
            },
            getBiometricRemainingDuration = object : GetBiometricRemainingDuration {
                override fun invoke() = flowOf(Duration.INFINITE)
            },
            biometricKeyEncryptUseCase = BiometricKeyEncryptUseCaseImpl(),
            decryptBiometricKeyUseCase = unused(),
            authConfirmMasterKeyUseCase = object : AuthConfirmMasterKeyUseCase {
                override fun invoke(
                    salt: MasterPasswordSalt,
                    hash: MasterPasswordHash,
                    version: MasterKdfVersion,
                ): AuthMasterKeyUseCase = { io(auth) }
            },
            authGenerateMasterKeyUseCase = object : AuthGenerateMasterKeyUseCase {
                override fun invoke(version: MasterKdfVersion): AuthMasterKeyUseCase = {
                    ioEffect {
                        generationStarted.complete(Unit)
                        generating.await()
                        auth
                    }
                }
            },
            cryptoGenerator = unused(),
            cipherEncryptor = unused(),
            yubiKeyUnlockAvailability = YubiKeyUnlockAvailability { false },
        )

        init {
            useCase().onEach {
                observed.add(it)
                states.send(it)
            }.launchIn(scope)
        }

        suspend fun startCreation(biometric: Boolean = false) = run {
            val createState = awaitState<VaultState.Create>()
            initialCreateState = createState
            val action = if (biometric) {
                assertNotNull(createState.createWithMasterPasswordAndBiometric).getCreateIo("1234")
            } else {
                createState.createWithMasterPassword.getCreateIo("1234")
            }
            scope.async { action.attempt().bind() }.also {
                generationStarted.await()
            }
        }

        suspend fun assertSetupPreserved() {
            assertNull(withTimeoutOrNull(200.milliseconds) { states.receive() })
            assertEquals(listOf(initialCreateState), observed.toList())
        }

        suspend inline fun <reified T : VaultState> awaitState(): T {
            while (true) {
                val state = states.receive()
                if (state is T) return state
            }
        }

        fun close() {
            scope.cancel()
            vaultSession.close()
        }
    }

    companion object {
        private fun lockedSession() = MasterSession.Empty(
            lockInfo = MasterSession.Empty.LockInfo(
                type = LockReason.TIMEOUT,
                timestamp = Instant.DISTANT_PAST,
                reason = "Timeout",
            ),
        )

        private inline fun <reified T> unused(): T = Proxy.newProxyInstance(
            T::class.java.classLoader,
            arrayOf(T::class.java),
        ) { _, method, _ -> error("Unexpected ${T::class.simpleName}.${method.name}") } as T
    }
}
