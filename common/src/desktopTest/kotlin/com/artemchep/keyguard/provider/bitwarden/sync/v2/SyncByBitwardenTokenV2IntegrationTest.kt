package com.artemchep.keyguard.provider.bitwarden.sync.v2

import com.artemchep.keyguard.common.service.crypto.CipherEncryptor
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.copy.Base64ServiceJvm
import com.artemchep.keyguard.core.store.DatabaseSyncer
import com.artemchep.keyguard.core.store.bitwarden.BitwardenMeta
import com.artemchep.keyguard.core.store.bitwarden.BitwardenToken
import com.artemchep.keyguard.crypto.CipherEncryptorImpl
import com.artemchep.keyguard.crypto.CryptoGeneratorJvm
import com.artemchep.keyguard.data.Database
import com.artemchep.keyguard.provider.bitwarden.crypto.BitwardenCrImpl
import com.artemchep.keyguard.provider.bitwarden.crypto.BitwardenCrKey
import com.artemchep.keyguard.provider.bitwarden.crypto.appendOrganizationToken2
import com.artemchep.keyguard.provider.bitwarden.crypto.appendProfileToken
import com.artemchep.keyguard.provider.bitwarden.crypto.appendUserToken
import com.artemchep.keyguard.provider.bitwarden.entity.CollectionEntity
import com.artemchep.keyguard.provider.bitwarden.entity.FolderEntity
import com.artemchep.keyguard.provider.bitwarden.entity.OrganizationEntity
import com.artemchep.keyguard.provider.bitwarden.entity.OrganizationUserStatusTypeEntity
import com.artemchep.keyguard.provider.bitwarden.entity.OrganizationUserTypeEntity
import com.artemchep.keyguard.provider.bitwarden.entity.ProfileEntity
import io.ktor.http.HttpMethod
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

class SyncByBitwardenTokenV2IntegrationTest {
    @Test
    fun `full sync orchestrates folders ciphers collections organizations and sends in one run`() = runTest {
        val server = UploadTestServer()
        server.revisionDate = "rev-all-entities"
        val fixture = createFullSyncFixture(server)
        val organizationKey = ByteArray(64) { index -> (index + 31).toByte() }
        val organization =
            fixture.organizationEntity(
                id = "organization-remote-1",
                keyData = organizationKey,
                name = "Engineering",
            )
        fixture.crypto.appendOrganizationToken2(
            id = organization.id,
            keyData = organizationKey,
        )
        server.profile = fixture.profile.copy(organizations = listOf(organization))
        server.seedFolder(
            FolderEntity(
                id = "folder-remote-1",
                name = fixture.encryptUserString("Remote folder"),
                revisionDate = T1,
            ),
        )
        server.seedCollection(
            CollectionEntity(
                id = "collection-remote-1",
                organizationId = organization.id,
                name = fixture.encryptOrganizationString(
                    organizationId = organization.id,
                    value = "Engineering vault",
                ),
                externalId = null,
                readOnly = true,
                hidePasswords = false,
            ),
        )

        fixture.sync.invoke(fixture.user).invoke()

        val logMessages = fixture.logRepository.messages()
        assertTrue(
            listOf(
                "Syncing folder entities.",
                "Syncing cipher entities.",
                "Syncing collection entities.",
                "Syncing organization entities.",
                "Syncing send entities.",
            ).all { it in logMessages },
        )
        assertTrue(logMessages.any { it.contains("sync_response_received account_id=$ACCOUNT_ID") })
        assertTrue(logMessages.any { it.contains("entity_plan_built entity=folders") })
        assertTrue(logMessages.any { it.contains("entity_plan_built entity=ciphers") })
        assertEquals(
            listOf(HttpMethod.Get to "/api/accounts/revision-date", HttpMethod.Get to "/api/sync"),
            server.requests.map { it.method to it.path },
        )
        assertEquals(
            listOf("Remote folder"),
            fixture.database.folderQueries
                .getByAccountId(ACCOUNT_ID)
                .executeAsList()
                .map { it.data_.name },
        )
        assertEquals(
            listOf("Engineering vault"),
            fixture.database.collectionQueries
                .getByAccountId(ACCOUNT_ID)
                .executeAsList()
                .map { it.data_.name },
        )
        val savedOrganization =
            fixture.database.organizationQueries
                .getByAccountId(ACCOUNT_ID)
                .executeAsList()
                .single()
                .data_
        assertEquals("Engineering", savedOrganization.name)
        assertEquals(fixture.base64Service.encodeToString(organizationKey), savedOrganization.keyBase64)
        val meta =
            fixture.database.metaQueries
                .getByAccountId(ACCOUNT_ID)
                .executeAsOne()
                .data_
        assertIs<BitwardenMeta.LastSyncResult.Success>(meta.lastSyncResult)
        assertEquals("rev-all-entities", meta.lastServerRevisionDate)
    }

    @Test
    fun `expired access token is refreshed before sync requests`() = runTest {
        val server = UploadTestServer()
        server.refreshedAccessToken = "access-token-refreshed-before-sync"
        val fixture =
            createFullSyncFixture(server) { user ->
                user.copy(
                    token =
                        requireNotNull(user.token).copy(
                            accessToken = "access-token-expired",
                            refreshToken = "refresh-token-expired",
                            expirationDate = Instant.parse("2000-01-01T00:00:00Z"),
                        ),
                )
            }
        server.profile = fixture.profile

        fixture.sync.invoke(fixture.user).invoke()

        assertEquals(
            listOf(
                HttpMethod.Post to "/identity/connect/token",
                HttpMethod.Get to "/api/accounts/revision-date",
                HttpMethod.Get to "/api/sync",
            ),
            server.requests.map { it.method to it.path },
        )
        assertEquals(
            listOf(
                "Bearer ${server.refreshedAccessToken}",
                "Bearer ${server.refreshedAccessToken}",
            ),
            server.requests
                .filter { it.path != "/identity/connect/token" }
                .map { it.authorization },
        )
        assertEquals(server.refreshedAccessToken, fixture.savedUser().token?.accessToken)
    }

    @Test
    fun `unauthorized response during sync refreshes token and retries full sync`() = runTest {
        val server = UploadTestServer()
        server.refreshedAccessToken = "access-token-refreshed-after-401"
        server.syncUnauthorizedTokens += "access-token-stale"
        val fixture =
            createFullSyncFixture(server) { user ->
                user.copy(
                    token =
                        requireNotNull(user.token).copy(
                            accessToken = "access-token-stale",
                            refreshToken = "refresh-token-stale",
                        ),
                )
            }
        server.profile = fixture.profile

        fixture.sync.invoke(fixture.user).invoke()

        assertEquals(
            listOf(
                HttpMethod.Get to "/api/accounts/revision-date",
                HttpMethod.Get to "/api/sync",
                HttpMethod.Post to "/identity/connect/token",
                HttpMethod.Get to "/api/accounts/revision-date",
                HttpMethod.Get to "/api/sync",
            ),
            server.requests.map { it.method to it.path },
        )
        assertEquals(
            listOf(
                "Bearer access-token-stale",
                "Bearer ${server.refreshedAccessToken}",
            ),
            server.requests
                .filter { it.path == "/api/sync" }
                .map { it.authorization },
        )
        assertEquals(server.refreshedAccessToken, fixture.savedUser().token?.accessToken)
    }
}

private data class FullSyncFixture(
    val sync: SyncByBitwardenTokenV2Impl,
    val database: Database,
    val user: BitwardenToken,
    val profile: ProfileEntity,
    val crypto: BitwardenCrImpl,
    val base64Service: Base64ServiceJvm,
    val logRepository: CapturingLogRepository,
) {
    fun encryptUserString(value: String): String =
        crypto.encoder(BitwardenCrKey.UserToken).invoke(
            CipherEncryptor.Type.AesCbc256_HmacSha256_B64,
            value.encodeToByteArray(),
        )

    fun encryptOrganizationString(
        organizationId: String,
        value: String,
    ): String =
        crypto.encoder(BitwardenCrKey.OrganizationToken(organizationId)).invoke(
            CipherEncryptor.Type.AesCbc256_HmacSha256_B64,
            value.encodeToByteArray(),
        )

    fun organizationEntity(
        id: String,
        keyData: ByteArray,
        name: String,
    ): OrganizationEntity =
        OrganizationEntity(
            id = id,
            key =
                crypto.encoder(BitwardenCrKey.UserToken).invoke(
                    CipherEncryptor.Type.AesCbc256_HmacSha256_B64,
                    keyData,
                ),
            privateKey = null,
            name = name,
            avatarColor = null,
            status = OrganizationUserStatusTypeEntity.Confirmed,
            type = OrganizationUserTypeEntity.User,
            enabled = true,
        )

    fun savedUser(): BitwardenToken =
        database.accountQueries
            .getByAccountId(ACCOUNT_ID)
            .executeAsOne()
            .data_ as BitwardenToken
}

private fun createFullSyncFixture(
    server: UploadTestServer,
    transformUser: (BitwardenToken) -> BitwardenToken = { it },
): FullSyncFixture {
    val database = createUploadTestDatabase()
    val cryptoGenerator = CryptoGeneratorJvm()
    val base64Service = Base64ServiceJvm()
    val cipherEncryptor = CipherEncryptorImpl(
        cryptoGenerator = cryptoGenerator,
        base64Service = base64Service,
    )
    val (createdUser, profile) = createUploadTestUserAndProfile(
        server = server,
        cipherEncryptor = cipherEncryptor,
        base64Service = base64Service,
    )
    val user = transformUser(createdUser)
    database.accountQueries.insert(
        accountId = user.id,
        data = user,
    )
    val logRepository = CapturingLogRepository()
    val sync = SyncByBitwardenTokenV2Impl(
        logRepository = logRepository,
        cipherEncryptor = cipherEncryptor,
        cryptoGenerator = cryptoGenerator,
        base64Service = base64Service,
        getPasswordStrength = UploadTestPasswordStrength,
        json = UploadTestServer.json,
        httpClient = server.client,
        db = UploadTestVaultDatabaseManager(database),
        dbSyncer = DatabaseSyncer(cryptoGenerator),
        pendingUploadCoordinator = UploadTestPendingUploadCoordinator(),
        watchdog = UploadTestWatchdog,
    )
    val crypto =
        BitwardenCrImpl(
            cipherEncryptor = cipherEncryptor,
            cryptoGenerator = cryptoGenerator,
            base64Service = base64Service,
        ).apply {
            appendUserToken(
                encKey = base64Service.decode(user.key.encryptionKeyBase64),
                macKey = base64Service.decode(user.key.macKeyBase64),
            )
            appendProfileToken(
                keyCipherText = profile.key,
                privateKeyCipherText = profile.privateKey,
            )
        }
    return FullSyncFixture(
        sync = sync,
        database = database,
        user = user,
        profile = profile,
        crypto = crypto,
        base64Service = base64Service,
        logRepository = logRepository,
    )
}

private class CapturingLogRepository : LogRepository {
    private val lock = Any()

    private val entries = mutableListOf<LogEntry>()

    fun messages(): List<String> = synchronized(lock) {
        entries.map { it.message }
    }

    override fun post(
        tag: String,
        message: String,
        level: LogLevel,
    ) {
        record(
            tag = tag,
            message = message,
            level = level,
        )
    }

    override suspend fun add(
        tag: String,
        message: String,
        level: LogLevel,
    ) {
        record(
            tag = tag,
            message = message,
            level = level,
        )
    }

    private fun record(
        tag: String,
        message: String,
        level: LogLevel,
    ) {
        synchronized(lock) {
            entries += LogEntry(
                tag = tag,
                message = message,
                level = level,
            )
        }
    }
}

private data class LogEntry(
    val tag: String,
    val message: String,
    val level: LogLevel,
)
