package com.artemchep.keyguard.common.usecase.impl

import arrow.core.Either
import com.artemchep.keyguard.common.exception.MasterPasswordChangedException
import com.artemchep.keyguard.common.exception.PasswordMismatchException
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.io
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.AuthResult
import com.artemchep.keyguard.common.model.BiometricStatus
import com.artemchep.keyguard.common.model.Fingerprint
import com.artemchep.keyguard.common.model.FingerprintBiometric
import com.artemchep.keyguard.common.model.FingerprintFido2
import com.artemchep.keyguard.common.model.FingerprintPassword
import com.artemchep.keyguard.common.model.FingerprintYubiKey
import com.artemchep.keyguard.common.model.LockReason
import com.artemchep.keyguard.common.model.MasterKdf
import com.artemchep.keyguard.common.model.MasterKdfVersion
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.model.MasterPassword
import com.artemchep.keyguard.common.model.MasterPasswordHash
import com.artemchep.keyguard.common.model.MasterPasswordSalt
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.model.VaultState
import com.artemchep.keyguard.common.model.VaultState.Main.ChangePassword.Change
import com.artemchep.keyguard.common.service.biometrics.BiometricKeyRepository
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.common.service.keyvalue.KeyValueStoreUnavailableException
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
import com.artemchep.keyguard.common.usecase.GenerateMasterHashUseCase
import com.artemchep.keyguard.common.usecase.GetBiometricRemainingDuration
import com.artemchep.keyguard.common.usecase.GetBiometricRequireConfirmation
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.common.usecase.PutVaultSession
import com.artemchep.keyguard.common.usecase.RewrapMasterKeyUseCase
import com.artemchep.keyguard.common.usecase.YubiKeyUnlockAvailability
import com.artemchep.keyguard.core.session.usecase.AuthMasterKeyUseCase
import com.artemchep.keyguard.common.service.text.impl.Base64ServiceImpl
import com.artemchep.keyguard.crypto.NativeCipherEncryptor
import com.artemchep.keyguard.crypto.NativeCryptoGenerator
import com.artemchep.keyguard.data.Database
import com.artemchep.keyguard.platform.LeBiometricCipher
import com.artemchep.keyguard.provider.keyguard.KeyguardAuthorityReseal
import com.artemchep.keyguard.provider.keyguard.KeyguardAuthorityResealer
import com.artemchep.keyguard.provider.keyguard.KeyguardAuthorityReseals
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

@Suppress("TooManyFunctions")
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

    @Test
    fun `a new vault wraps a random master key and unlocks with the password`() =
        withFixture(crypto = true) {
            val main = createAndOpen("1234", CUSTOM_KDF)
            val fingerprint = assertNotNull(savedFingerprint)
            assertEquals(CUSTOM_KDF, fingerprint.master.kdf)
            assertIs<FingerprintPassword.Wrapped>(fingerprint.master)
            assertEquals(MasterKdfVersion.V2, fingerprint.version)
            val session = assertIs<MasterSession.Key>(published.await())
            assertEquals(MasterKdfVersion.V2, session.masterKey.version)
            assertEquals(32, session.masterKey.byteArray.size)
            assertEquals(deriveKey(fingerprint, "1234"), session.masterKey)
            assertEquals(CUSTOM_KDF, main.changePassword.kdf)

            sessions.value = lockedSession()
            val unlock = awaitState<VaultState.Unlock>()
            val wrong = unlock.unlockWithMasterPassword.getCreateIo("4321").attempt().bind()
            assertIs<PasswordMismatchException>(assertIs<Either.Left<Throwable>>(wrong).value)
            assertIs<Either.Right<Unit>>(unlock.unlockWithMasterPassword.getCreateIo("1234").attempt().bind())
        }

    @Test
    fun `an outdated unlock screen checks the password against the latest fingerprint`() =
        withFixture(crypto = true) {
            val main = createAndOpen("1234", CUSTOM_KDF)
            sessions.value = lockedSession()
            val unlock = awaitState<VaultState.Unlock>()
            // Another process changes the password: it re-wraps the same master key.
            val before = assertNotNull(fingerprints.value)
            fingerprints.value = before.copy(master = rewrap(main.masterKey, "5678"))

            val old = unlock.unlockWithMasterPassword.getCreateIo("1234").attempt().bind()
            assertIs<PasswordMismatchException>(assertIs<Either.Left<Throwable>>(old).value)
            assertIs<Either.Right<Unit>>(unlock.unlockWithMasterPassword.getCreateIo("5678").attempt().bind())
        }

    @Test
    fun `a key derivation change re-wraps the master key and keeps everything else`() =
        withFixture(crypto = true) {
            val main = createAndOpen("1234", CUSTOM_KDF)
            val before = addUnlockMethods()
            // Changes from the fingerprint without the other unlock methods.
            val changePassword = main.changePassword
            events.clear()

            val report = changePassword.withMasterPassword.getCreateIo("1234", Change.Kdf(OTHER_KDF)).bind()

            assertEquals(0, report.authoritiesNotResealed)
            // No re-key, no re-seal of the authority keys, no new session.
            assertEquals(listOf("put"), events.toList())
            assertTrue(rekeys.isEmpty())
            assertEquals(1, publishes.size)
            val after = assertNotNull(savedFingerprint)
            assertEquals(OTHER_KDF, after.master.kdf)
            assertIs<FingerprintPassword.Wrapped>(after.master)
            assertNotEquals(before.master.salt, after.master.salt)
            assertSame(before.biometric, after.biometric)
            assertSame(before.yubiKey, after.yubiKey)
            assertSame(before.fido2, after.fido2)
            // The new parameters open the same master key.
            assertEquals(main.masterKey, deriveKey(after, "1234"))

            val state = awaitState<VaultState.Main>()
            assertSame(vaultSession, state.session)
            assertEquals(OTHER_KDF, state.changePassword.kdf)
        }

    @Test
    fun `a password change re-wraps the master key and re-seals the authority keys`() =
        withFixture(crypto = true) {
            val main = createAndOpen("1234", CUSTOM_KDF)
            val before = addUnlockMethods()
            // Changes from the fingerprint without the other unlock methods.
            val changePassword = main.changePassword
            events.clear()

            val report = changePassword.withMasterPassword.getCreateIo("1234", Change.Password("5678")).bind()

            assertEquals(2, report.authoritiesNotResealed)
            assertEquals(listOf("prepare", "put", "commit"), events.toList())
            assertTrue(rekeys.isEmpty())
            assertEquals(1, publishes.size)
            val after = assertNotNull(savedFingerprint)
            // Keeps the custom key derivation.
            assertEquals(CUSTOM_KDF, after.master.kdf)
            assertSame(before.biometric, after.biometric)
            assertSame(before.yubiKey, after.yubiKey)
            assertSame(before.fido2, after.fido2)
            assertEquals(main.masterKey, deriveKey(after, "5678"))
            assertFailsWith<PasswordMismatchException> { deriveKey(after, "1234") }
        }

    @Test
    fun `a password change to the same password still re-seals the authority keys`() = withFixture(crypto = true) {
        val main = createAndOpen("1234", CUSTOM_KDF)
        events.clear()

        main.changePassword.withMasterPassword.getCreateIo("1234", Change.Password("1234")).bind()

        assertEquals(listOf("prepare", "put", "commit"), events.toList())
        val after = assertNotNull(savedFingerprint)
        assertEquals(CUSTOM_KDF, after.master.kdf)
        assertEquals(main.masterKey, deriveKey(after, "1234"))
    }

    @Test
    fun `a failed re-seal preparation leaves the vault as it was`() = withFixture(crypto = true) {
        val main = createAndOpen("1234", CUSTOM_KDF)
        val before = assertNotNull(savedFingerprint)
        events.clear()
        prepareFailure = IllegalStateException("Cannot read the sync groups")

        val result = main.changePassword.withMasterPassword
            .getCreateIo("1234", Change.Password("5678"))
            .attempt()
            .bind()

        val reported = assertIs<IllegalStateException>(assertIs<Either.Left<Throwable>>(result).value)
        assertEquals(prepareFailure?.message, reported.message)
        assertEquals(listOf("prepare"), events.toList())
        assertSame(before, savedFingerprint)
    }

    @Test
    fun `a wrong current password changes nothing`() = withFixture(crypto = true) {
        val main = createAndOpen("1234", CUSTOM_KDF)
        val before = assertNotNull(savedFingerprint)
        events.clear()

        val result = main.changePassword.withMasterPassword
            .getCreateIo("4321", Change.Kdf(OTHER_KDF))
            .attempt()
            .bind()

        assertIs<PasswordMismatchException>(assertIs<Either.Left<Throwable>>(result).value)
        assertTrue(events.isEmpty())
        assertSame(before, savedFingerprint)
    }

    @Test
    fun `a password change moves a legacy vault to the wrapped format with the same master key`() =
        withFixture(crypto = true) {
            val legacy = openLegacy("1234")
            val before = assertNotNull(fingerprints.value)
            val changePassword = legacy.changePassword
            assertEquals(MasterKdf.Pbkdf2Legacy, changePassword.kdf)

            changePassword.withMasterPassword.getCreateIo("1234", Change.Password("5678")).bind()

            // No re-key and no new session: the master key stays the same.
            assertEquals(listOf("prepare", "put", "commit"), events.toList())
            assertTrue(rekeys.isEmpty())
            assertTrue(publishes.isEmpty())
            val after = assertNotNull(savedFingerprint)
            assertEquals(MasterKdf.Argon2id.DEFAULT, after.master.kdf)
            assertIs<FingerprintPassword.Wrapped>(after.master)
            assertEquals(MasterKdfVersion.V2, after.version)
            assertSame(before.biometric, after.biometric)
            assertSame(before.yubiKey, after.yubiKey)
            assertSame(before.fido2, after.fido2)
            val key = deriveKey(after, "5678")
            assertTrue(legacy.masterKey.byteArray.contentEquals(key.byteArray))
            assertFailsWith<PasswordMismatchException> { deriveKey(after, "1234") }
        }

    @Test
    fun `a wrong current password does not move a legacy vault`() = withFixture(crypto = true) {
        val legacy = openLegacy("1234")
        val before = fingerprints.value

        val result = legacy.changePassword.withMasterPassword
            .getCreateIo("4321", Change.Kdf(OTHER_KDF))
            .attempt()
            .bind()

        assertIs<PasswordMismatchException>(assertIs<Either.Left<Throwable>>(result).value)
        assertTrue(events.isEmpty())
        assertNull(savedFingerprint)
        assertSame(before, fingerprints.value)
    }

    @Test
    fun `two wrapped changes at once run one after the other`() = withFixture(crypto = true) {
        val main = createAndOpen("1234", CUSTOM_KDF)
        confirmations.clear()
        putStarted = CompletableDeferred()
        putGate = CompletableDeferred()

        val first = start(main.changePassword.withMasterPassword.getCreateIo("1234", Change.Password("5678")))
        putStarted.await()
        // The first change holds the lock while it writes the fingerprint,
        // so the second one does not even verify the password yet.
        val second = start(main.changePassword.withMasterPassword.getCreateIo("1234", Change.Kdf(OTHER_KDF)))
        assertNull(withTimeoutOrNull(200.milliseconds) { second.await() })
        assertEquals(1, confirmations.size)

        putGate.complete(Unit)
        assertIs<Either.Right<VaultState.Main.ChangePassword.Report>>(first.await())
        val failure = assertIs<Either.Left<Throwable>>(second.await()).value
        assertIs<MasterPasswordChangedException>(failure)
        assertEquals(1, confirmations.size)
        val fingerprint = assertNotNull(savedFingerprint)
        assertEquals(CUSTOM_KDF, fingerprint.master.kdf)
        assertEquals(main.masterKey, deriveKey(fingerprint, "5678"))
    }

    @Test
    fun `an unavailable fingerprint store emits no state until the existing vault can be unlocked`() =
        withFixture(unavailableCollections = 2) {
            fingerprints.value = existingFingerprint()
            assertEquals(1.seconds, retryDelays.receive())
            assertTrue(observed.isEmpty())
            retryGate.send(Unit)
            assertEquals(2.seconds, retryDelays.receive())
            assertTrue(observed.isEmpty())
            retryGate.send(Unit)

            awaitState<VaultState.Unlock>()
            assertFalse(observed.any { it is VaultState.Create })
        }

    @Test
    fun `an unavailable fingerprint store emits no state until a new vault can be created`() =
        withFixture(unavailableCollections = 1) {
            assertEquals(1.seconds, retryDelays.receive())
            assertTrue(observed.isEmpty())
            retryGate.send(Unit)

            awaitState<VaultState.Create>()
            assertEquals(1, observed.size)
        }

    @Test
    fun `other fingerprint store failures are not retried`() =
        withFixture(fingerprintFailure = IllegalStateException("Cannot read the fingerprint")) {
            val failure = assertIs<IllegalStateException>(uncaught.await())
            assertEquals("Cannot read the fingerprint", failure.message)
            assertTrue(retryDelays.tryReceive().isFailure)
            assertTrue(observed.isEmpty())
        }

    private fun withFixture(
        biometric: Boolean = false,
        unavailableCollections: Int = 0,
        fingerprintFailure: Throwable? = null,
        /** Derives the keys for real, instead of returning a fixed one. */
        crypto: Boolean = false,
        block: suspend Fixture.() -> Unit,
    ): Unit = runBlocking {
        withTimeout(10.seconds) {
            val fixture = Fixture(
                biometric = biometric,
                unavailableCollections = unavailableCollections,
                fingerprintFailure = fingerprintFailure,
                crypto = if (crypto) NativeCryptoGenerator() else null,
            )
            try {
                fixture.block()
            } finally {
                fixture.close()
            }
        }
    }

    private class Fixture(
        biometric: Boolean,
        /** The number of collections of the fingerprint store that fail as unavailable. */
        private var unavailableCollections: Int,
        /** Fails every collection of the fingerprint store. */
        private val fingerprintFailure: Throwable?,
        /** Derives the keys with it, or returns a fixed key if `null`. */
        crypto: CryptoGenerator?,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        /** The failure of the shared state flow, which has no subscriber to report it to. */
        val uncaught = CompletableDeferred<Throwable>()

        // Shares the state like GlobalScope does, but reports its failure.
        private val sharingScope = CoroutineScope(
            SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> uncaught.complete(e) },
        )

        /** The delays the use case asked for before retrying to open the fingerprint store. */
        val retryDelays = Channel<Duration>(Channel.UNLIMITED)

        /** Lets one retry to open the fingerprint store proceed. */
        val retryGate = Channel<Unit>()
        val vaultSession = testVaultSession()
        private val generationStarted = CompletableDeferred<Unit>()
        val generating = CompletableDeferred<Unit>()
        val opening = CompletableDeferred<Unit>()
        val opened = CompletableDeferred<VaultSession>()
        val published = CompletableDeferred<MasterSession>()

        /** The sessions the use case published. */
        val publishes = ConcurrentLinkedQueue<MasterSession>()
        val fingerprints = MutableStateFlow<Fingerprint?>(null)
        val sessions = MutableStateFlow<MasterSession>(MasterSession.Empty())
        val states = Channel<VaultState>(Channel.UNLIMITED)
        val observed = ConcurrentLinkedQueue<VaultState>()
        var writeFailure: Throwable? = null
        var deferFingerprintDelivery = false
        var savedFingerprint: Fingerprint? = null
        val events = ConcurrentLinkedQueue<String>()
        var prepareFailure: Throwable? = null
        var putStarted = CompletableDeferred<Unit>()
        var putGate = CompletableDeferred(Unit)

        /** The keys the database was re-keyed with. */
        val rekeys = ConcurrentLinkedQueue<MasterKey>()

        /** The key derivations of the verified passwords. */
        val confirmations = ConcurrentLinkedQueue<MasterKdf>()
        private lateinit var initialCreateState: VaultState.Create

        /** A vault of the derived format, of version 1. */
        private val auth = AuthResult(
            key = MasterKey(MasterKdfVersion.V1, byteArrayOf(1)),
            token = FingerprintPassword.Derived(
                kdf = MasterKdf.Argon2id.V1,
                hash = MasterPasswordHash(MasterKdf.Argon2id.V1, byteArrayOf(2)),
                salt = MasterPasswordSalt(byteArrayOf(3)),
            ),
        )

        /** A new wrapped master key. */
        private val generated = AuthResult(
            key = MasterKey(MasterKdfVersion.V2, byteArrayOf(6)),
            token = FingerprintPassword.Wrapped(
                kdf = MasterKdf.Argon2id.V1,
                salt = MasterPasswordSalt(byteArrayOf(7)),
                wrappedKey = byteArrayOf(8),
            ),
        )
        private val log = LogRepositoryBridge(emptyList())
        private val cipherEncryptor = crypto?.let {
            NativeCipherEncryptor(
                cryptoGenerator = it,
                base64Service = Base64ServiceImpl(),
            )
        }
        private val realHash = crypto?.let {
            GenerateMasterHashUseCaseImpl(log, it)
        }
        private val realKey = crypto?.let {
            GenerateMasterKeyUseCaseImpl(log, it)
        }
        private val wrapping = crypto?.let {
            MasterKeyWrapping(
                cryptoGenerator = it,
                cipherEncryptor = requireNotNull(cipherEncryptor),
            )
        }
        private val realConfirm = crypto?.let {
            AuthConfirmMasterKeyUseCaseImpl(
                generateMasterHashUseCase = GenerateMasterHashUseCaseImpl(log, it),
                generateMasterKeyUseCase = GenerateMasterKeyUseCaseImpl(log, it),
                masterKeyWrapping = requireNotNull(wrapping),
            )
        }
        private val realRewrap = crypto?.let {
            RewrapMasterKeyUseCaseImpl(
                masterKeyWrapping = requireNotNull(wrapping),
                generateMasterSaltUseCase = GenerateMasterSaltUseCaseImpl(it),
            )
        }
        private val realGenerate = crypto?.let {
            AuthGenerateMasterKeyUseCaseImpl(
                cryptoGenerator = it,
                rewrapMasterKeyUseCase = requireNotNull(realRewrap),
            )
        }
        private val databaseManager = object : VaultDatabaseManager {
            override fun get(): IO<Database> = error("Unexpected database read")
            override fun <T> mutate(tag: String, block: suspend (Database) -> T): IO<T> =
                error("Unexpected database write")
            override fun changePassword(newMasterKey: MasterKey): IO<Unit> = ioEffect {
                events += "rekey"
                rekeys += newMasterKey
            }
        }
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
                                .also { events += "encrypt" }
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
                    publishes += session
                    published.complete(session)
                }
            },
            disableBiometric = unused(),
            logRepository = LogRepositoryBridge(emptyList()),
            keyReadWriteRepository = object : FingerprintReadWriteRepository {
                override fun update(transform: (Fingerprint?) -> Fingerprint?) = ioEffect {
                    put(transform(fingerprints.value)).bind()
                }
                // Each collection opens the store anew, like the real one.
                override fun get() = flow {
                    if (unavailableCollections > 0) {
                        unavailableCollections -= 1
                        throw KeyValueStoreUnavailableException(IOException("Keystore is unavailable"))
                    }
                    fingerprintFailure?.let { throw it }
                    emitAll(fingerprints)
                }
                override fun put(key: Fingerprint?): IO<Unit> = ioEffect {
                    writeFailure?.let { throw it }
                    putStarted.complete(Unit)
                    putGate.await()
                    events += "put"
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
                    token: FingerprintPassword,
                ): AuthMasterKeyUseCase = { password ->
                    ioEffect {
                        confirmations += token.kdf
                        realConfirm?.invoke(token)?.invoke(password)?.bind() ?: auth
                    }
                }
            },
            generateMasterHashUseCase = object : GenerateMasterHashUseCase {
                override fun invoke(
                    password: MasterPassword,
                    salt: MasterPasswordSalt,
                    kdf: MasterKdf,
                ): IO<MasterPasswordHash> = ioEffect {
                    confirmations += kdf
                    realHash?.invoke(password, salt, kdf)?.bind()
                        ?: (auth.token as FingerprintPassword.Derived).hash
                }
            },
            authGenerateMasterKeyUseCase = object : AuthGenerateMasterKeyUseCase {
                override fun invoke(kdf: MasterKdf.Argon2id): AuthMasterKeyUseCase = { password ->
                    ioEffect {
                        generationStarted.complete(Unit)
                        generating.await()
                        realGenerate?.invoke(kdf)?.invoke(password)?.bind() ?: generated
                    }
                }
            },
            rewrapMasterKeyUseCase = object : RewrapMasterKeyUseCase {
                override fun invoke(
                    masterKey: MasterKey,
                    password: MasterPassword,
                    kdf: MasterKdf.Argon2id,
                ): IO<FingerprintPassword.Wrapped> =
                    requireNotNull(realRewrap) { "Re-wrapping needs the real key derivation" }
                        .invoke(masterKey, password, kdf)
            },
            cryptoGenerator = unused(),
            cipherEncryptor = unused(),
            yubiKeyUnlockAvailability = YubiKeyUnlockAvailability { false },
            fido2UnlockAvailability = com.artemchep.keyguard.common.usecase.Fido2UnlockAvailability { false },
            fido2UnlockService = Fido2UnlockService(unused(), unused(), unused(), unused(), unused()),
            base64Service = unused(),
            authorityResealer = object : KeyguardAuthorityResealer {
                override suspend fun prepare(
                    db: VaultDatabaseManager,
                    oldPassword: String,
                    newPassword: String,
                ): KeyguardAuthorityReseals {
                    events += "prepare"
                    prepareFailure?.let { throw it }
                    return KeyguardAuthorityReseals(reseals = emptyList(), stale = listOf("stale"))
                }

                override suspend fun commit(
                    db: VaultDatabaseManager,
                    reseals: List<KeyguardAuthorityReseal>,
                    onError: suspend (Throwable) -> Unit,
                ): List<String> {
                    events += "commit"
                    return listOf("changed")
                }
            },
            delayForRetry = { delay ->
                retryDelays.send(delay)
                retryGate.receive()
            },
            scope = sharingScope,
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
            val kdf = MasterKdf.Argon2id.V1
            val action = if (biometric) {
                assertNotNull(createState.createWithMasterPasswordAndBiometric).getCreateIo("1234", kdf)
            } else {
                createState.createWithMasterPassword.getCreateIo("1234", kdf)
            }
            scope.async { action.attempt().bind() }.also {
                generationStarted.await()
            }
        }

        /** Creates the vault with the [kdf], then opens it. Needs the real key derivation. */
        suspend fun createAndOpen(password: String, kdf: MasterKdf.Argon2id): VaultState.Main {
            generating.complete(Unit)
            opened.complete(vaultSession)
            val createState = awaitState<VaultState.Create>()
            createState.createWithMasterPassword.getCreateIo(password, kdf).bind()
            sessions.value = published.await()
            return awaitState<VaultState.Main>()
        }

        /**
         * Opens a vault of the legacy key derivation, with the biometric, YubiKey
         * and FIDO2 unlock. Needs the real key derivation.
         */
        suspend fun openLegacy(password: String): VaultState.Main {
            generating.complete(Unit)
            opened.complete(vaultSession)
            val kdf = MasterKdf.Pbkdf2Legacy
            val salt = MasterPasswordSalt(ByteArray(64) { it.toByte() })
            val hash = requireNotNull(realHash)(MasterPassword.of(password), salt, kdf).bind()
            val key = requireNotNull(realKey)(MasterPassword.of(password), hash).bind()
            fingerprints.value = Fingerprint(
                master = FingerprintPassword.Derived(
                    kdf = kdf,
                    hash = hash,
                    salt = salt,
                ),
                biometric = BIOMETRIC,
                yubiKey = YUBI_KEY,
                fido2 = FIDO2,
            )
            sessions.value = MasterSession.Key(
                masterKey = key,
                session = vaultSession,
                origin = MasterSession.Key.Authenticated,
                createdAt = Instant.DISTANT_PAST,
            )
            return awaitState<VaultState.Main>()
        }

        /** Adds the other unlock methods to the fingerprint, and returns it. */
        fun addUnlockMethods(): Fingerprint {
            val fingerprint = assertNotNull(fingerprints.value).copy(
                biometric = BIOMETRIC,
                yubiKey = YUBI_KEY,
                fido2 = FIDO2,
            )
            fingerprints.value = fingerprint
            return fingerprint
        }

        /** Wraps the [masterKey] under the [password], like a password change does. */
        suspend fun rewrap(masterKey: MasterKey, password: String): FingerprintPassword.Wrapped =
            requireNotNull(realRewrap)(masterKey, MasterPassword.of(password), CUSTOM_KDF).bind()

        /** Derives the master key the [fingerprint] gives for the [password]. */
        suspend fun deriveKey(fingerprint: Fingerprint, password: String): MasterKey =
            requireNotNull(realConfirm)(fingerprint.master)(MasterPassword.of(password)).bind().key

        fun <T> start(io: IO<T>) = scope.async { io.attempt().bind() }

        suspend fun assertSetupPreserved() {
            assertNull(withTimeoutOrNull(200.milliseconds) { states.receive() })
            assertEquals(listOf(initialCreateState), observed.toList())
        }

        suspend fun awaitMain(predicate: (VaultState.Main) -> Boolean): VaultState.Main {
            while (true) {
                val state = awaitState<VaultState.Main>()
                if (predicate(state)) return state
            }
        }

        suspend inline fun <reified T : VaultState> awaitState(): T {
            while (true) {
                val state = states.receive()
                if (state is T) return state
            }
        }

        fun existingFingerprint() = Fingerprint(
            master = auth.token,
            biometric = null,
        )

        fun close() {
            scope.cancel()
            sharingScope.cancel()
            vaultSession.close()
        }
    }

    companion object {
        private val CUSTOM_KDF = MasterKdf.Argon2id(
            iterations = 2,
            memoryKib = 16 * 1024,
            parallelism = 1,
        )

        private val OTHER_KDF = MasterKdf.Argon2id(
            iterations = 3,
            memoryKib = 16 * 1024,
            parallelism = 2,
        )

        private val BIOMETRIC = FingerprintBiometric(
            iv = byteArrayOf(1),
            encryptedMasterKey = byteArrayOf(2),
        )

        private val YUBI_KEY = FingerprintYubiKey(
            slot = 2,
            challenge = byteArrayOf(1),
            hkdfSalt = byteArrayOf(2),
            encryptedMasterKey = "2.yubikey",
        )

        private val FIDO2 = FingerprintFido2(
            credentialId = "credential",
            salt = "salt",
            hkdfSalt = "hkdf-salt",
            encryptedMasterKey = "2.fido2",
        )

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
