package com.artemchep.keyguard.common.service.sshagent.impl

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.service.database.InstantToLongAdapter
import com.artemchep.keyguard.common.service.database.exposed.ExposedDatabaseManager
import com.artemchep.keyguard.common.service.sshagent.SshAgentPublicKeyRow
import com.artemchep.keyguard.common.service.sshagent.createSshAgentPublicKeyRow
import com.artemchep.keyguard.copy.Base64ServiceJvm
import com.artemchep.keyguard.crypto.CryptoGeneratorJvm
import com.artemchep.keyguard.dataexposed.DatabaseExposed
import com.artemchep.keyguard.dataexposed.UrlBlock
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SshAgentPublicKeyRepositoryImplTest {
    private val cryptoGenerator = CryptoGeneratorJvm()
    private val base64Service = Base64ServiceJvm()

    @Test
    fun `replaceAll reconciles rows and get returns deterministic ordering`() = runTest {
        val repository = createRepository()
        val ed25519B = createRow(
            keyType = "ssh-ed25519",
            seed = 2,
            fingerprint = "SHA256:b",
            name = "B",
        )
        val rsa = createRow(
            keyType = "ssh-rsa",
            seed = 3,
            fingerprint = "SHA256:rsa",
            name = "RSA",
        )
        val ed25519A = createRow(
            keyType = "ssh-ed25519",
            seed = 1,
            fingerprint = "SHA256:a",
            name = "A",
        )

        repository.replaceAll(listOf(rsa, ed25519B, ed25519A)).invoke()

        assertEquals(
            listOf("A", "B", "RSA"),
            repository.get().invoke().map { it.name },
        )

        repository.replaceAll(listOf(ed25519B)).invoke()

        assertEquals(
            listOf(ed25519B.publicKeyBlobSha256),
            repository.get().invoke().map { it.publicKeyBlobSha256 },
        )
    }

    @Test
    fun `lookup by hash and public key returns cached row`() = runTest {
        val repository = createRepository()
        val row = createRow(
            keyType = "ssh-ed25519",
            seed = 1,
            fingerprint = "SHA256:lookup",
            name = "Lookup key",
        )
        repository.replaceAll(listOf(row)).invoke()

        val byHash = repository
            .getByPublicKeyBlobSha256(row.publicKeyBlobSha256)
            .invoke()
        val byPublicKey = repository
            .getByPublicKey("${row.publicKey} original-comment")
            .invoke()

        assertEquals(row, byHash)
        assertEquals(row, byPublicKey)
    }

    @Test
    fun `clear removes all cached rows`() = runTest {
        val repository = createRepository()
        repository.replaceAll(
            listOf(
                createRow("ssh-ed25519", seed = 1, fingerprint = "SHA256:a", name = "A"),
                createRow("ssh-rsa", seed = 2, fingerprint = "SHA256:b", name = "B"),
            ),
        ).invoke()

        repository.clear().invoke()

        assertEquals(emptyList(), repository.get().invoke())
    }

    @Test
    fun `clearNames preserves public key rows and removes names`() = runTest {
        val repository = createRepository()
        val row = createRow(
            keyType = "ssh-ed25519",
            seed = 1,
            fingerprint = "SHA256:names",
            name = "Named key",
        )
        repository.replaceAll(listOf(row)).invoke()

        repository.clearNames().invoke()

        val updated = repository.get().invoke().single()
        assertEquals(row.publicKeyBlobSha256, updated.publicKeyBlobSha256)
        assertEquals(row.publicKey, updated.publicKey)
        assertNull(updated.name)
    }

    @Test
    fun `public key material is normalized and comments are stripped`() {
        val publicKey = buildOpenSshPublicKey(
            keyType = "ssh-ed25519",
            seed = 1,
        )

        val row = createSshAgentPublicKeyRow(
            publicKey = "$publicKey user@example.com raw comment",
            fingerprint = "SHA256:normalized",
            name = "Normalized key",
            cryptoGenerator = cryptoGenerator,
            base64Service = base64Service,
        )

        assertEquals(
            SshAgentPublicKeyRow(
                publicKeyBlobSha256 = requireNotNull(row).publicKeyBlobSha256,
                publicKey = publicKey,
                keyType = "ssh-ed25519",
                fingerprint = "SHA256:normalized",
                name = "Normalized key",
            ),
            row,
        )
    }

    @Test
    fun `malformed public key blob is rejected`() {
        val row = createSshAgentPublicKeyRow(
            publicKey = "ssh-ed25519 AAAA... raw-comment",
            fingerprint = "SHA256:invalid",
            name = "Invalid key",
            cryptoGenerator = cryptoGenerator,
            base64Service = base64Service,
        )

        assertNull(row)
    }

    private fun createRepository() = SshAgentPublicKeyRepositoryImpl(
        exposedDatabaseManager = TestExposedDatabaseManager(),
        cryptoGenerator = cryptoGenerator,
        base64Service = base64Service,
        dispatcher = Dispatchers.Unconfined,
    )

    private fun createRow(
        keyType: String,
        seed: Int,
        fingerprint: String,
        name: String?,
    ): SshAgentPublicKeyRow = requireNotNull(
        createSshAgentPublicKeyRow(
            publicKey = buildOpenSshPublicKey(
                keyType = keyType,
                seed = seed,
            ),
            fingerprint = fingerprint,
            name = name,
            cryptoGenerator = cryptoGenerator,
            base64Service = base64Service,
        ),
    )

    private class TestExposedDatabaseManager : ExposedDatabaseManager {
        private val database = createExposedTestDatabase()

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
        ): IO<Unit> = {
            Unit
        }
    }

    private companion object {
        fun createExposedTestDatabase(): DatabaseExposed {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            DatabaseExposed.Schema.create(driver)
            return DatabaseExposed(
                driver = driver,
                urlBlockAdapter = UrlBlock.Adapter(InstantToLongAdapter),
            )
        }

        fun buildOpenSshPublicKey(
            keyType: String,
            seed: Int,
        ): String {
            val blob = buildOpenSshPublicKeyBlob(
                keyType = keyType,
                seed = seed,
            )
            return "$keyType ${Base64.getEncoder().encodeToString(blob)}"
        }

        fun buildOpenSshPublicKeyBlob(
            keyType: String,
            seed: Int,
        ): ByteArray = ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { dataOutput ->
                val keyTypeBytes = keyType.toByteArray(Charsets.US_ASCII)
                dataOutput.writeInt(keyTypeBytes.size)
                dataOutput.write(keyTypeBytes)

                val keyBytes = ByteArray(32) { (seed + it).toByte() }
                dataOutput.writeInt(keyBytes.size)
                dataOutput.write(keyBytes)
                dataOutput.flush()
            }
            output.toByteArray()
        }
    }
}
