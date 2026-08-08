package com.artemchep.keyguard.common.service.pendinghistory

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.service.database.InstantToLongAdapter
import com.artemchep.keyguard.common.service.database.exposed.ExposedDatabaseManager
import com.artemchep.keyguard.common.service.keyvalue.impl.JsonKeyValueStore
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.settings.impl.SettingsRepositoryImpl
import com.artemchep.keyguard.common.service.text.impl.Base64ServiceImpl
import com.artemchep.keyguard.dataexposed.DatabaseExposed
import com.artemchep.keyguard.dataexposed.UrlBlock
import com.artemchep.keyguard.nativecrypto.NativeCrypto
import com.artemchep.keyguard.nativecrypto.NativeCryptoPrimitives
import com.artemchep.keyguard.nativecrypto.NativeSshKeyType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PendingUsageHistoryQueueImplTest {
    companion object {
        private const val RSA_KEY_BITS = 2048

        private val keyPair by lazy {
            val material = NativeCrypto.ssh.generate(
                type = NativeSshKeyType.RSA,
                rsaBits = RSA_KEY_BITS,
            )
            val description = NativeCrypto.ssh.describe(
                type = NativeSshKeyType.RSA,
                privateKey = material.privateKey,
                publicKey = material.publicKey,
            )
            val export = NativeCrypto.ssh.exportCxf(
                privateKeyPem = description.privateKeyPem,
                publicKeyOpenSsh = description.publicKeyOpenSsh,
            )
            val privateKeyPkcs8 = export.privateKeyPkcs8
            val publicKeySpki = NativeCryptoPrimitives
                .rsaPublicKeySpkiFromPkcs8(privateKeyPkcs8)
            privateKeyPkcs8 to publicKeySpki
        }
    }

    private val json = Json
    private val settingsRepository = SettingsRepositoryImpl(
        store = JsonKeyValueStore(),
        json = json,
        base64Service = Base64ServiceImpl(),
    )

    private val queue = PendingUsageHistoryQueueImpl(
        databaseManager = TestExposedDatabaseManager(),
        settingsRepository = settingsRepository,
        json = json,
        dispatcher = Dispatchers.Unconfined,
        logRepository = NoopLogRepository,
    )

    private suspend fun provisionPublicKey() {
        val (_, publicKeySpki) = keyPair
        settingsRepository.setExposedContentPublicKey(publicKeySpki)
            .bind()
    }

    @Test
    fun `events are dropped until a public key is provisioned`() = runTest {
        queue.enqueue(event(id = "a")).bind()

        assertTrue(queue.get().bind().isEmpty())
    }

    @Test
    fun `events without a coalescence key accumulate`() = runTest {
        provisionPublicKey()

        queue.enqueue(event(id = "a")).bind()
        queue.enqueue(event(id = "b")).bind()

        assertEquals(listOf("a", "b"), queue.get().bind().map { it.id })
    }

    @Test
    fun `coalesced events overwrite a single row keeping the latest payload`() = runTest {
        provisionPublicKey()

        queue.enqueue(
            event(id = "a", responseType = "FIRST", coalescenceKey = "k", timestamp = 1L),
        ).bind()
        queue.enqueue(
            event(id = "b", responseType = "SECOND", coalescenceKey = "k", timestamp = 2L),
        ).bind()

        val row = queue.get().bind().single()
        assertEquals("a", row.id)
        assertEquals(2L, row.timestampEpochMilliseconds)
        val (privateKeyPkcs8, _) = keyPair
        val plaintext = PendingUsageHistoryEnvelope.open(privateKeyPkcs8, row.payload)
        val payload = json.decodeFromString<PendingUsageHistoryPayload>(plaintext.decodeToString())
        assertEquals("SECOND", payload.responseType)
    }

    @Test
    fun `different coalescence keys keep separate rows`() = runTest {
        provisionPublicKey()

        queue.enqueue(event(id = "a", coalescenceKey = "k1")).bind()
        queue.enqueue(event(id = "b", coalescenceKey = "k2")).bind()

        assertEquals(2, queue.get().bind().size)
    }

    @Test
    fun `a flushed row id is never reused for a later coalesced event`() = runTest {
        provisionPublicKey()

        queue.enqueue(event(id = "a", coalescenceKey = "k")).bind()
        // Simulate the flusher draining the row; its id is now recorded
        // as an eventId in the vault tables and must not be re-issued.
        queue.remove("a").bind()
        queue.enqueue(event(id = "b", coalescenceKey = "k")).bind()

        val row = queue.get().bind().single()
        assertNotEquals("a", row.id)
        assertEquals("b", row.id)
    }

    private fun event(
        id: String,
        responseType: String = "SUCCESS",
        coalescenceKey: String? = null,
        timestamp: Long = 1L,
    ) = PendingUsageHistory(
        id = id,
        protocol = PendingUsageHistory.Protocol.SSH,
        sessionId = "test-session",
        caller = "caller",
        requestType = "AGENT_LIST_KEYS",
        responseType = responseType,
        cipherId = null,
        fingerprint = null,
        keygrip = null,
        timestampEpochMilliseconds = timestamp,
        coalescenceKey = coalescenceKey,
    )

    private class TestExposedDatabaseManager : ExposedDatabaseManager {
        private val database = kotlin.run {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            DatabaseExposed.Schema.create(driver)
            DatabaseExposed(
                driver = driver,
                urlBlockAdapter = UrlBlock.Adapter(InstantToLongAdapter),
            )
        }

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

    private object NoopLogRepository : LogRepository {
        override fun post(
            tag: String,
            message: String,
            level: LogLevel,
        ) {
            // Intentionally empty.
        }

        override suspend fun add(
            tag: String,
            message: String,
            level: LogLevel,
        ) {
            // Intentionally empty.
        }
    }
}
