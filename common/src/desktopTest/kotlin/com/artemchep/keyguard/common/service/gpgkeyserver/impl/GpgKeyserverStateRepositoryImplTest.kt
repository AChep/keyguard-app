package com.artemchep.keyguard.common.service.gpgkeyserver.impl

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.DGpgKeyserverState
import com.artemchep.keyguard.common.model.GpgKeyserverVerificationStatus
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.common.service.crypto.GpgKeyMetadataResolver
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpPublicKey
import com.artemchep.keyguard.common.service.gpgagent.GpgRevocationStatus
import com.artemchep.keyguard.common.service.gpgkeyserver.GpgKeyserverStateRecorder
import com.artemchep.keyguard.common.service.gpgkeyserver.GpgKeyserverStateEvaluator
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.impl.WatchtowerGpgKeyPublishing
import com.artemchep.keyguard.crypto.NativeGpgCertificateMaterialReconciler
import com.artemchep.keyguard.crypto.NativeGpgKeyMetadataResolver
import com.artemchep.keyguard.crypto.toDomain
import com.artemchep.keyguard.nativecrypto.NativeCrypto
import com.artemchep.keyguard.data.Database
import com.artemchep.keyguard.provider.bitwarden.usecase.REFRESH_FINGERPRINT
import com.artemchep.keyguard.provider.bitwarden.usecase.REFRESH_PUBLIC_KEY
import com.artemchep.keyguard.provider.bitwarden.usecase.refreshRevocationCertificates
import com.artemchep.keyguard.provider.bitwarden.usecase.refreshTestCipher
import com.artemchep.keyguard.provider.bitwarden.usecase.GpgKeyserverRefreshTestFixture
import com.artemchep.keyguard.provider.bitwarden.mapper.toDomain
import com.artemchep.keyguard.provider.bitwarden.sync.v2.UploadTestPasswordStrength
import com.artemchep.keyguard.provider.bitwarden.sync.v2.UploadTestVaultDatabaseManager
import com.artemchep.keyguard.provider.bitwarden.sync.v2.createUploadTestDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalCoroutinesApi::class)
class GpgKeyserverStateRepositoryImplTest {
    @Test
    fun `retained signed restoration activates and expires on daily assessments without rewriting state`() = runTest {
        val certificates = refreshRevocationCertificates(restorationExpirationSeconds = (1.days + 2.minutes).inWholeSeconds)
        for (recordedAtOffset in listOf(15L, 25L)) {
            val startedAt = testScheduler.currentTime
            val clock = object : Clock {
                override fun now() = Instant.fromEpochSeconds(1_700_000_000 + recordedAtOffset) +
                    (testScheduler.currentTime - startedAt).milliseconds
            }
            val resolver = object : GpgKeyMetadataResolver {
                override fun resolve(
                    privateKeyArmored: String?,
                    publicKeyArmored: String?,
                    fingerprint: String?,
                    candidateRevocationKeys: List<GpgOpenPgpPublicKey>,
                ) = NativeCrypto.openPgp.resolveMetadata(
                    privateKeyData = null,
                    publicKeyData = publicKeyArmored?.encodeToByteArray(),
                    normalizedFingerprint = fingerprint.orEmpty(),
                    candidateRevocationKeys = candidateRevocationKeys.map { it.armored.encodeToByteArray() },
                    referenceTimeEpochSeconds = clock.now().epochSeconds,
                )?.toDomain()
            }
            val initial = refreshTestCipher(publicKey = certificates.original, fingerprint = certificates.fingerprint)
            GpgKeyserverRefreshTestFixture(initial = listOf(initial)).use { fixture ->
                val repository = fixture.stateRepository
                val recorded = GpgKeyserverStateRecorder(repository, NativeGpgCertificateMaterialReconciler, resolver).record(
                    fingerprint = certificates.fingerprint,
                    cipherIds = setOf(initial.cipherId),
                    publicCertificates = listOf(certificates.retired, certificates.restored),
                    publicationStatus = GpgKeyserverVerificationStatus.VERIFIED,
                    sourceKeyserver = "https://keys.example.test",
                    checkedAt = clock.now(),
                    refreshed = false,
                ).bind()
                assertEquals(GpgKeyserverVerificationStatus.VERIFIED, recorded.publicationStatus)
                assertEquals(
                    if (recordedAtOffset < 20) GpgKeyserverVerificationStatus.REVOKED else GpgKeyserverVerificationStatus.VERIFIED,
                    recorded.verificationStatus,
                )
                val cipher = initial.toDomain(UploadTestPasswordStrength)
                val processor = WatchtowerGpgKeyPublishing(
                    keyserverStateRepository = repository,
                    getCiphers = object : GetCiphers {
                        override fun invoke() = flowOf(listOf(cipher))
                    },
                    evaluator = GpgKeyserverStateEvaluator(NativeGpgCertificateMaterialReconciler, resolver),
                    scope = backgroundScope,
                    clock = clock,
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                )
                val versions = mutableListOf<String>()
                val subscription = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    processor.version().collect(versions::add)
                }
                runCurrent()
                assertEquals(if (recordedAtOffset < 20) "revoked" else null, processor.process(listOf(cipher)).single().value)

                advanceTimeBy(1.minutes)
                runCurrent()
                assertEquals(if (recordedAtOffset < 20) "revoked" else null, processor.process(listOf(cipher)).single().value)
                assertEquals(1, versions.size)

                advanceTimeBy(1.days - 1.minutes)
                runCurrent()
                assertNull(processor.process(listOf(cipher)).single().value)

                advanceTimeBy(1.days)
                runCurrent()
                assertEquals("revoked", processor.process(listOf(cipher)).single().value)
                assertEquals(if (recordedAtOffset < 20) 3 else 2, versions.size)
                assertEquals(recorded, repository.getByFingerprint(certificates.fingerprint).first())
                assertEquals(initial, fixture.row().data_)
                assertTrue(fixture.lookups.isEmpty())
                subscription.cancel()
                runCurrent()
            }
        }
    }

    @Test
    fun `malformed and oversized evidence cannot replace the retained anchor`() = runTest {
        val repository = createRepository()
        val initial = model(REFRESH_FINGERPRINT).copy(revocationEvidenceArmored = REFRESH_PUBLIC_KEY)
        repository.put(initial).bind()
        val recorder = GpgKeyserverStateRecorder(repository, NativeGpgCertificateMaterialReconciler, NativeGpgKeyMetadataResolver)

        for (incoming in listOf("malformed", "A".repeat(4 * 1024 * 1024 + 1))) {
            assertFailsWith<IllegalStateException> {
                recorder.record(
                    fingerprint = REFRESH_FINGERPRINT,
                    cipherIds = emptySet(),
                    publicCertificates = listOf(incoming),
                    publicationStatus = GpgKeyserverVerificationStatus.VERIFIED,
                    sourceKeyserver = "https://keys.example.test",
                    checkedAt = Instant.parse("2024-03-01T00:00:00Z"),
                    refreshed = false,
                ).bind()
            }
            assertEquals(initial, repository.getByFingerprint(REFRESH_FINGERPRINT).first())
        }
    }

    @Test
    fun `missing or unsupported revocation verdict retains evidence without clearing a warning`() = runTest {
        val certificates = refreshRevocationCertificates()
        for (status in listOf(GpgKeyserverVerificationStatus.REVOKED, GpgKeyserverVerificationStatus.FOUND_UNVERIFIED)) {
            for (policyRevision in listOf(1, 2)) {
                val repository = createRepository()
                val initial = model(certificates.fingerprint, status = status).copy(
                    revocationEvidenceArmored = certificates.retired,
                )
                repository.put(initial).bind()
                val resolver = object : GpgKeyMetadataResolver {
                    override fun resolve(
                        privateKeyArmored: String?,
                        publicKeyArmored: String?,
                        fingerprint: String?,
                        candidateRevocationKeys: List<GpgOpenPgpPublicKey>,
                    ) = NativeGpgKeyMetadataResolver.resolve(null, publicKeyArmored, fingerprint)?.let { result ->
                        result.copy(
                            authorization = result.authorization.copy(
                                policyRevision = policyRevision,
                                revocations = if (policyRevision == 1) {
                                    mapOf(certificates.fingerprint to GpgRevocationStatus.NOT_REVOKED)
                                } else emptyMap(),
                            ),
                        )
                    }
                }
                val recorder = GpgKeyserverStateRecorder(repository, NativeGpgCertificateMaterialReconciler, resolver)

                val recorded = recorder.record(
                    fingerprint = certificates.fingerprint,
                    cipherIds = emptySet(),
                    publicCertificates = listOf(certificates.restored),
                    publicationStatus = GpgKeyserverVerificationStatus.VERIFIED,
                    sourceKeyserver = "https://keys.example.test",
                    checkedAt = Instant.parse("2024-03-01T00:00:00Z"),
                    refreshed = false,
                ).bind()

                assertEquals(
                    if (status == GpgKeyserverVerificationStatus.REVOKED) status else GpgKeyserverVerificationStatus.UNKNOWN,
                    recorded.verificationStatus,
                )
                // The accepted restoration remains available for a later supported evaluation.
                val accepted = NativeGpgKeyMetadataResolver.resolve(null, recorded.revocationEvidenceArmored, certificates.fingerprint)
                assertEquals(GpgRevocationStatus.NOT_REVOKED, accepted?.authorization?.revocations?.get(certificates.fingerprint))
            }
        }
    }

    @Test
    fun `public evidence and opaque legacy warnings survive a database round trip`() = runTest {
        val repository = createRepository()
        val state = model("ABCDEF01", status = GpgKeyserverVerificationStatus.REVOKED).copy(
            publicationStatus = GpgKeyserverVerificationStatus.VERIFIED,
            revocationEvidenceArmored = "retained public packets",
            hasUnbackedRevocation = true,
        )

        repository.put(state).bind()

        assertEquals(state, repository.getByFingerprint(state.fingerprint).first())
    }

    @Test
    fun `migration backfills publication status and preserves legacy revocation warnings`() = runTest {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            driver.execute(
                null,
                """
                CREATE TABLE gpgKeyserverState (
                    fingerprint TEXT NOT NULL PRIMARY KEY,
                    cipherId TEXT,
                    verificationStatus INTEGER NOT NULL,
                    lastCheckedAt INTEGER,
                    lastRefreshedAt INTEGER,
                    sourceKeyserver TEXT
                )
                """.trimIndent(),
                0,
            )
            GpgKeyserverVerificationStatus.entries.forEach { status ->
                driver.execute(
                    null,
                    "INSERT INTO gpgKeyserverState VALUES ('${status.code}', NULL, ${status.code}, 100, 90, 'server')",
                    0,
                )
            }

            Database.Schema.migrate(driver, 26, 27)
            val states = createRepository(createUploadTestDatabase(driver)).getAll().first()
            GpgKeyserverVerificationStatus.entries.forEach { status ->
                val state = states.single { it.fingerprint == status.code.toString() }
                val revoked = status == GpgKeyserverVerificationStatus.REVOKED
                assertEquals(status, state.verificationStatus)
                assertEquals(
                    if (revoked) GpgKeyserverVerificationStatus.UNKNOWN else status,
                    state.publicationStatus,
                )
                assertEquals(revoked, state.hasUnbackedRevocation)
                assertNull(state.revocationEvidenceArmored)
                assertEquals(Instant.fromEpochMilliseconds(100), state.lastCheckedAt)
                assertEquals(Instant.fromEpochMilliseconds(90), state.lastRefreshedAt)
                assertEquals("server", state.sourceKeyserver)
            }
        }
    }

    @Test
    fun `concurrent updates merge from the latest saved evidence`() = runTest {
        val database = createUploadTestDatabase()
        val delegate = UploadTestVaultDatabaseManager(database)
        val mutex = Mutex()
        val manager = object : VaultDatabaseManager by delegate {
            override fun <T> mutate(tag: String, block: suspend (Database) -> T) = ioEffect {
                mutex.withLock {
                    yield()
                    block(database)
                }
            }
        }
        val repository = GpgKeyserverStateRepositoryImpl(manager, Dispatchers.Unconfined)

        coroutineScope {
            repeat(12) { index ->
                launch(Dispatchers.Default) {
                    repository.update("ABCDEF01") { current, _ ->
                        (current ?: model("ABCDEF01")).copy(
                            revocationEvidenceArmored = current?.revocationEvidenceArmored.orEmpty() + "$index,",
                        )
                    }.bind()
                }
            }
        }

        val evidence = assertNotNull(repository.getByFingerprint("ABCDEF01").first()?.revocationEvidenceArmored)
        assertEquals((0 until 12).toSet(), evidence.split(',').filter(String::isNotEmpty).map(String::toInt).toSet())
    }

    @Test
    fun `a failed evidence evaluation leaves the saved state unchanged`() = runTest {
        val repository = createRepository()
        val initial = model("ABCDEF01", status = GpgKeyserverVerificationStatus.REVOKED).copy(
            revocationEvidenceArmored = "retained signed evidence",
        )
        repository.put(initial).bind()

        assertFailsWith<IllegalStateException> {
            repository.update(initial.fingerprint) { _, _ -> error("Cannot evaluate incoming evidence") }.bind()
        }

        assertEquals(initial, repository.getByFingerprint(initial.fingerprint).first())
    }

    @Test
    fun `put normalizes fingerprint and looks up state`() = runTest {
        val repository = createRepository()

        repository.put(
            model(
                fingerprint = "ab cd:ef 01",
                cipherId = "cipher-a",
                status = GpgKeyserverVerificationStatus.VERIFIED,
            ),
        )()

        val byFingerprint = repository.getByFingerprint("AB:CD EF01").first()
        val byCipher = repository.getByCipherId("cipher-a").first()

        assertEquals("ABCDEF01", byFingerprint?.fingerprint)
        assertEquals(GpgKeyserverVerificationStatus.VERIFIED, byFingerprint?.verificationStatus)
        assertEquals(listOf("ABCDEF01"), byCipher.map { it.fingerprint })
    }

    @Test
    fun `put replaces existing state by normalized fingerprint`() = runTest {
        val repository = createRepository()
        repository.put(
            model(
                fingerprint = "ab cd ef 01",
                status = GpgKeyserverVerificationStatus.NOT_FOUND,
                sourceKeyserver = "https://old.example",
            ),
        )()

        repository.put(
            model(
                fingerprint = "ABCDEF01",
                status = GpgKeyserverVerificationStatus.FOUND_UNVERIFIED,
                sourceKeyserver = "https://new.example",
            ),
        )()

        val state = repository.getAll().first().single()

        assertEquals("ABCDEF01", state.fingerprint)
        assertEquals(GpgKeyserverVerificationStatus.FOUND_UNVERIFIED, state.verificationStatus)
        assertEquals("https://new.example", state.sourceKeyserver)
    }

    @Test
    fun `remove by fingerprint and remove all clear state`() = runTest {
        val repository = createRepository()
        repository.put(model(fingerprint = "ab cd ef 01"))()
        repository.put(model(fingerprint = "12 34", cipherId = "cipher-b"))()

        repository.removeByFingerprint("AB:CD EF01")()

        assertNull(repository.getByFingerprint("abcdef01").first())
        assertEquals(listOf("1234"), repository.getAll().first().map { it.fingerprint })

        repository.removeAll()()

        assertEquals(emptyList(), repository.getAll().first())
    }

    private fun createRepository(database: Database = createUploadTestDatabase()): GpgKeyserverStateRepositoryImpl {
        return GpgKeyserverStateRepositoryImpl(
            databaseManager = UploadTestVaultDatabaseManager(database),
            dispatcher = UnconfinedTestDispatcher(),
        )
    }

    private fun model(
        fingerprint: String,
        cipherId: String? = null,
        status: GpgKeyserverVerificationStatus = GpgKeyserverVerificationStatus.UNKNOWN,
        checkedAt: Instant = Instant.parse("2024-01-01T00:00:00Z"),
        refreshedAt: Instant? = null,
        sourceKeyserver: String? = "https://keys.openpgp.org",
    ) = DGpgKeyserverState(
        fingerprint = fingerprint,
        cipherId = cipherId,
        verificationStatus = status,
        publicationStatus = status.takeUnless { it == GpgKeyserverVerificationStatus.REVOKED }
            ?: GpgKeyserverVerificationStatus.UNKNOWN,
        lastCheckedAt = checkedAt,
        lastRefreshedAt = refreshedAt,
        sourceKeyserver = sourceKeyserver,
    )
}
