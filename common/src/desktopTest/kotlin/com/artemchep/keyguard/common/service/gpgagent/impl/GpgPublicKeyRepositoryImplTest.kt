package com.artemchep.keyguard.common.service.gpgagent.impl

import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.service.database.InstantToLongAdapter
import com.artemchep.keyguard.common.service.database.exposed.ExposedDatabaseManager
import com.artemchep.keyguard.common.service.gpgagent.GpgCertificationAuthorityEntry
import com.artemchep.keyguard.common.service.gpgagent.GpgPublicKeyEntry
import com.artemchep.keyguard.dataexposed.DatabaseExposed
import com.artemchep.keyguard.dataexposed.UrlBlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GpgPublicKeyRepositoryImplTest {
    @Test
    fun `replaceSnapshot restores public-only key rows from exposed database`() = runTest {
        val repository = createRepository()
        val publicOnly = createEntry(
            cipherId = "public-only",
            name = "Public only",
            fingerprint = "ABCDEF0123456789ABCDEF0123456789ABCDEF01",
            keygrip = "0123456789abcdef0123456789abcdef01234567",
        )

        repository.replaceSnapshot(
            publicKeys = listOf(publicOnly),
            certificationAuthorities = emptyList(),
        ).invoke()

        val row = repository.getSnapshot().invoke().publicKeys.single()
        assertEquals(publicOnly.accountId, row.accountId)
        assertEquals(publicOnly.cipherId, row.cipherId)
        assertEquals(publicOnly.publicKeyArmored, row.publicKeyArmored)
        assertEquals(publicOnly.primaryFingerprint, row.primaryFingerprint)
        assertEquals(publicOnly.name, row.name)

        // The keygrip is normalized on write and on lookup.
        val keyInfo = repository.getKeyInfoByKeygrip(publicOnly.keyInfo.single().keygrip).invoke()
        assertEquals(1, keyInfo.size)
        assertEquals(publicOnly.keyInfo.single().keygrip.uppercase(), keyInfo.single().keygrip)
        assertEquals("Public only", keyInfo.single().name)

        repository.clearNames().invoke()

        assertNull(repository.getSnapshot().invoke().publicKeys.single().name)
        assertNull(repository.getKeyInfo().invoke().single().name)
    }

    @Test
    fun `snapshot stores and clears public keys and authorities together`() = runTest {
        val repository = createRepository()
        val publicKey = createEntry(
            cipherId = "public",
            name = "Public",
            fingerprint = "ABCDEF0123456789ABCDEF0123456789ABCDEF01",
            keygrip = "0123456789ABCDEF0123456789ABCDEF01234567",
        )
        val authority = createAuthority("authority")

        repository.replaceSnapshot(
            publicKeys = listOf(publicKey),
            certificationAuthorities = listOf(authority),
        ).invoke()

        val snapshot = repository.getSnapshot().invoke()
        assertEquals(publicKey.cipherId, snapshot.publicKeys.single().cipherId)
        val row = snapshot.certificationAuthorities.single()
        assertEquals(authority.accountId, row.accountId)
        assertEquals(authority.cipherId, row.cipherId)
        assertEquals(authority.publicKeyArmored, row.publicKeyArmored)
        assertEquals(authority.primaryFingerprint, row.primaryFingerprint)

        repository.clear().invoke()
        val cleared = repository.getSnapshot().invoke()
        assertTrue(cleared.publicKeys.isEmpty())
        assertTrue(cleared.certificationAuthorities.isEmpty())
    }

    @Test
    fun `incomplete catalog rows are served to the agent but not as public keys`() = runTest {
        val repository = createRepository()
        val incomplete = createEntry(
            cipherId = "no-armored-key",
            name = "No armored key",
            fingerprint = "ABCDEF0123456789ABCDEF0123456789ABCDEF02",
            keygrip = "1123456789ABCDEF0123456789ABCDEF01234567",
        ).copy(publicKeyArmored = null)

        repository.replaceSnapshot(
            publicKeys = listOf(incomplete),
            certificationAuthorities = emptyList(),
        ).invoke()

        assertTrue(repository.getSnapshot().invoke().publicKeys.isEmpty())
        assertEquals(1, repository.getKeyInfo().invoke().size)
    }

    @Test
    fun `key info rows cannot exist without their parent row`() = runTest {
        val database = createExposedTestDatabase()
        val repository = GpgPublicKeyRepositoryImpl(
            exposedDatabaseManager = TestExposedDatabaseManager(database),
            dispatcher = Dispatchers.Unconfined,
        )

        val orphanInsert = runCatching {
            database.gpgAgentKeyInfoQueries.insert(
                accountId = "account",
                cipherId = "orphan",
                keygrip = "0123456789ABCDEF0123456789ABCDEF01234567",
                fingerprint = "ABCDEF0123456789ABCDEF0123456789ABCDEF01",
                algorithm = "ED25519",
                canSign = false,
                canDecrypt = false,
            )
        }
        assertTrue(
            orphanInsert.exceptionOrNull()?.message.orEmpty().contains("FOREIGN KEY"),
            "Expected the orphan key info insert to violate the foreign key",
        )

        // The repository write path stays valid under enforcement: a
        // rebuild over existing data must remove the children before
        // their parents.
        val first = createEntry(
            cipherId = "first",
            name = "First",
            fingerprint = "ABCDEF0123456789ABCDEF0123456789ABCDEF02",
            keygrip = "1123456789ABCDEF0123456789ABCDEF01234567",
        )
        val second = createEntry(
            cipherId = "second",
            name = "Second",
            fingerprint = "ABCDEF0123456789ABCDEF0123456789ABCDEF03",
            keygrip = "2123456789ABCDEF0123456789ABCDEF01234567",
        )
        repository.replaceSnapshot(listOf(first), emptyList()).invoke()
        repository.replaceSnapshot(listOf(second), emptyList()).invoke()
        assertEquals(
            listOf("second"),
            repository.getKeyInfo().invoke().map { it.cipherId },
        )
        repository.clear().invoke()
        assertTrue(repository.getKeyInfo().invoke().isEmpty())
    }

    @Test
    fun `the same keygrip is kept once per cipher`() = runTest {
        val repository = createRepository()
        val keygrip = "2123456789ABCDEF0123456789ABCDEF01234567"
        val first = createEntry(
            cipherId = "first",
            name = "First",
            fingerprint = "ABCDEF0123456789ABCDEF0123456789ABCDEF03",
            keygrip = keygrip,
        )
        val second = createEntry(
            cipherId = "second",
            name = "Second",
            fingerprint = "ABCDEF0123456789ABCDEF0123456789ABCDEF03",
            keygrip = keygrip,
        )

        repository.replaceSnapshot(listOf(first, second), emptyList()).invoke()

        val rows = repository.getKeyInfoByKeygrip(keygrip).invoke()
        assertEquals(listOf("first", "second"), rows.map { it.cipherId })
    }

    @Test
    fun `snapshot read and replacement each use one database transaction`() = runTest {
        val delegate = createSqlDriver()
        DatabaseExposed.Schema.create(delegate)
        val recordingDriver = RecordingSqlDriver(delegate)
        val database = createExposedDatabase(recordingDriver)
        val repository = createRepository(database)

        repository.replaceSnapshot(
            publicKeys = listOf(
                createEntry(
                    cipherId = "public",
                    name = "Public",
                    fingerprint = "ABCDEF0123456789ABCDEF0123456789ABCDEF01",
                    keygrip = "0123456789ABCDEF0123456789ABCDEF01234567",
                ),
            ),
            certificationAuthorities = listOf(createAuthority("authority")),
        ).invoke()
        assertEquals(1, recordingDriver.transactionCount)

        repository.getSnapshot().invoke()
        assertEquals(2, recordingDriver.transactionCount)
    }

    @Test
    fun `failed authority replacement rolls back the complete snapshot`() = runTest {
        val driver = createSqlDriver()
        DatabaseExposed.Schema.create(driver)
        val database = createExposedDatabase(driver)
        val repository = createRepository(database)
        val oldPublicKey = createEntry(
            cipherId = "old-public",
            name = "Old public",
            fingerprint = "ABCDEF0123456789ABCDEF0123456789ABCDEF01",
            keygrip = "0123456789ABCDEF0123456789ABCDEF01234567",
        )
        val oldAuthority = createAuthority("old-authority")
        repository.replaceSnapshot(listOf(oldPublicKey), listOf(oldAuthority)).invoke()
        driver.execute(
            identifier = null,
            sql = """
                CREATE TRIGGER reject_authority
                BEFORE INSERT ON gpgCertificationAuthority
                WHEN NEW.cipherId = 'reject'
                BEGIN
                    SELECT RAISE(ABORT, 'rejected authority');
                END
            """.trimIndent(),
            parameters = 0,
        )

        val failure = runCatching {
            repository.replaceSnapshot(
                publicKeys = listOf(
                    createEntry(
                        cipherId = "new-public",
                        name = "New public",
                        fingerprint = "ABCDEF0123456789ABCDEF0123456789ABCDEF02",
                        keygrip = "1123456789ABCDEF0123456789ABCDEF01234567",
                    ),
                ),
                certificationAuthorities = listOf(createAuthority("reject")),
            ).invoke()
        }
        assertTrue(failure.isFailure)

        val snapshot = repository.getSnapshot().invoke()
        assertEquals(listOf("old-public"), snapshot.publicKeys.map { it.cipherId })
        assertEquals(
            listOf("old-authority"),
            snapshot.certificationAuthorities.map { it.cipherId },
        )
        assertEquals(listOf("old-public"), repository.getKeyInfo().invoke().map { it.cipherId })
    }

    private fun createEntry(
        cipherId: String,
        name: String,
        fingerprint: String,
        keygrip: String,
    ) = GpgPublicKeyEntry(
        accountId = "account",
        cipherId = cipherId,
        publicKeyArmored = "-----BEGIN PGP PUBLIC KEY BLOCK-----",
        primaryFingerprint = fingerprint,
        canSign = false,
        canDecrypt = false,
        name = name,
        keyInfo = listOf(
            GpgPublicKeyEntry.KeyInfo(
                keygrip = keygrip,
                fingerprint = fingerprint,
                algorithm = "ED25519",
                canSign = false,
                canDecrypt = false,
            ),
        ),
    )

    private fun createAuthority(
        cipherId: String,
    ) = GpgCertificationAuthorityEntry(
        accountId = "account",
        cipherId = cipherId,
        publicKeyArmored = "-----BEGIN PGP PUBLIC KEY BLOCK-----",
        primaryFingerprint = "ABCDEF0123456789ABCDEF0123456789ABCDEF99",
    )

    private fun createRepository(
        database: DatabaseExposed = createExposedTestDatabase(),
    ) = GpgPublicKeyRepositoryImpl(
        exposedDatabaseManager = TestExposedDatabaseManager(database),
        dispatcher = Dispatchers.Unconfined,
    )

    private class RecordingSqlDriver(
        private val delegate: SqlDriver,
    ) : SqlDriver by delegate {
        var transactionCount: Int = 0
            private set

        override fun newTransaction(): QueryResult<Transacter.Transaction> {
            transactionCount += 1
            return delegate.newTransaction()
        }
    }

    private class TestExposedDatabaseManager(
        private val database: DatabaseExposed = createExposedTestDatabase(),
    ) : ExposedDatabaseManager {

        override fun get(): IO<DatabaseExposed> = {
            database
        }

        override fun <T> mutate(
            tag: String,
            block: suspend (DatabaseExposed) -> T,
        ): IO<T> = {
            block(database)
        }

        override fun changePassword(
            newMasterKey: MasterKey,
        ): IO<Unit> = {}
    }

    private companion object {
        fun createSqlDriver(): JdbcSqliteDriver {
            // Both production drivers enforce foreign keys — the JVM one
            // via this same property, the Android one via
            // setForeignKeyConstraintsEnabled — so the tests must run
            // under enforcement too.
            val properties = Properties().apply {
                put("foreign_keys", "true")
            }
            return JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, properties)
        }

        fun createExposedTestDatabase(): DatabaseExposed {
            val driver = createSqlDriver()
            DatabaseExposed.Schema.create(driver)
            return createExposedDatabase(driver)
        }

        fun createExposedDatabase(
            driver: SqlDriver,
        ) = DatabaseExposed(
            driver = driver,
            urlBlockAdapter = UrlBlock.Adapter(InstantToLongAdapter),
        )
    }
}
