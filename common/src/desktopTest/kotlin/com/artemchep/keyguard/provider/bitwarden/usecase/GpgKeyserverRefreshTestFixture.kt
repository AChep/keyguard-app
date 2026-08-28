package com.artemchep.keyguard.provider.bitwarden.usecase

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.io
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.model.DGpgKeyserverResult
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.GpgKeyserverConfig
import com.artemchep.keyguard.common.model.RefreshGpgPublicKeysRequest
import com.artemchep.keyguard.common.model.SearchGpgPublicKeyRequest
import com.artemchep.keyguard.common.service.backup.BackupStatus
import com.artemchep.keyguard.common.service.crypto.GpgCertificateMaterialReconcileResult
import com.artemchep.keyguard.common.service.crypto.GpgCertificateMaterialReconciler
import com.artemchep.keyguard.common.service.crypto.GpgKeyMetadataResolver
import com.artemchep.keyguard.common.service.gpgkeyserver.GpgKeyserverClient
import com.artemchep.keyguard.common.service.gpgkeyserver.impl.GpgKeyserverStateRepositoryImpl
import com.artemchep.keyguard.common.usecase.GetCanWrite
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetGpgKeyserverConfig
import com.artemchep.keyguard.common.usecase.GetWriteAccess
import com.artemchep.keyguard.common.usecase.MarkBackupAsDirty
import com.artemchep.keyguard.common.usecase.PutGpgKeyserverLastRefresh
import com.artemchep.keyguard.common.usecase.QueueSyncById
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.crypto.GPG_TEST_CV25519_PRIMARY_FINGERPRINT
import com.artemchep.keyguard.crypto.GPG_TEST_CV25519_PUBLIC_KEY
import com.artemchep.keyguard.crypto.NativeGpgCertificateMaterialReconciler
import com.artemchep.keyguard.crypto.NativeGpgKeyMetadataResolver
import com.artemchep.keyguard.data.Database
import com.artemchep.keyguard.provider.bitwarden.mapper.toDomain
import com.artemchep.keyguard.provider.bitwarden.sync.v2.UploadTestPasswordStrength
import com.artemchep.keyguard.provider.bitwarden.sync.v2.UploadTestVaultDatabaseManager
import com.artemchep.keyguard.provider.bitwarden.sync.v2.createUploadTestDatabase
import com.artemchep.keyguard.provider.bitwarden.usecase.util.ModifyCipherById
import com.artemchep.keyguard.provider.bitwarden.usecase.util.ModifyDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlin.test.assertIs
import kotlin.time.Instant

internal const val REFRESH_CIPHER_ID = "refresh-cipher"
internal const val REFRESH_ACCOUNT_ID = "refresh-account"
internal const val REFRESH_FINGERPRINT = GPG_TEST_CV25519_PRIMARY_FINGERPRINT
internal val REFRESH_CREATED_AT = Instant.parse("2024-01-01T00:00:00Z")
internal val REFRESH_PUBLIC_KEY: String by lazy {
    assertIs<GpgCertificateMaterialReconcileResult.Success>(
        NativeGpgCertificateMaterialReconciler.reconcile(
            expectedPrimaryFingerprint = REFRESH_FINGERPRINT,
            existingPublicCertificate = null,
            existingSecretCertificate = null,
            incomingPublicCertificate = GPG_TEST_CV25519_PUBLIC_KEY,
            incomingSecretCertificate = null,
        ),
    ).localPublicMaterial
}

internal fun refreshTestCipher(
    id: String = REFRESH_CIPHER_ID,
    publicKey: String = REFRESH_PUBLIC_KEY,
): BitwardenCipher = BitwardenCipher(
    accountId = REFRESH_ACCOUNT_ID,
    cipherId = id,
    revisionDate = REFRESH_CREATED_AT,
    service = BitwardenService(version = BitwardenService.VERSION),
    name = "Public key",
    notes = "Keep my notes",
    favorite = false,
    reprompt = BitwardenCipher.RepromptType.None,
    type = BitwardenCipher.Type.GpgKey,
    gpgKey = BitwardenCipher.GpgKey(
        publicKeyArmored = publicKey,
        fingerprint = REFRESH_FINGERPRINT,
        metadata = NativeGpgKeyMetadataResolver.resolve(null, publicKey, REFRESH_FINGERPRINT)?.metadata,
    ),
)

internal class GpgKeyserverRefreshTestFixture(
    initial: List<BitwardenCipher> = listOf(refreshTestCipher()),
    reconciler: GpgCertificateMaterialReconciler = NativeGpgCertificateMaterialReconciler,
    resolver: GpgKeyMetadataResolver = NativeGpgKeyMetadataResolver,
) : AutoCloseable {
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    val database = createUploadTestDatabase(
        driver = driver.also { Database.Schema.create(it) },
    )
    private val manager = UploadTestVaultDatabaseManager(database)
    val stateRepository = GpgKeyserverStateRepositoryImpl(manager, Dispatchers.Unconfined)
    val lastRefreshes = mutableListOf<Instant?>()
    val lookups = mutableListOf<String>()
    var beforeLookup: () -> Unit = {}
    var lookup: (String) -> DGpgKeyserverResult? = { fingerprint ->
        DGpgKeyserverResult(fingerprint = fingerprint, publicKeyArmored = REFRESH_PUBLIC_KEY)
    }
    var canWrite = true
    var backupDirtyCount = 0

    init {
        initial.forEach(::insert)
    }

    private val modifyDatabase = ModifyDatabase(
        db = manager,
        getCanWrite = object : GetCanWrite {
            override fun invoke() = flowOf(canWrite)
        },
        getWriteAccess = object : GetWriteAccess {
            override fun invoke() = flowOf(true)
        },
        queueSyncById = object : QueueSyncById {
            override fun invoke(accountId: AccountId) = io(Unit)
        },
        markBackupAsDirty = object : MarkBackupAsDirty {
            override fun invoke(): IO<BackupStatus> = ioEffect {
                backupDirtyCount += 1
                BackupStatus()
            }
        },
    )

    val useCase = RefreshGpgPublicKeysImpl(
        getCiphers = object : GetCiphers {
            override fun invoke(): Flow<List<DSecret>> = flow {
                emit(initial.map { it.toDomain(UploadTestPasswordStrength) })
            }
        },
        getGpgKeyserverConfig = object : GetGpgKeyserverConfig {
            override fun invoke() = flowOf(GpgKeyserverConfig())
        },
        putGpgKeyserverLastRefresh = object : PutGpgKeyserverLastRefresh {
            override fun invoke(value: Instant?): IO<Unit> = ioEffect { lastRefreshes += value }
        },
        keyserverClient = object : GpgKeyserverClient {
            override fun getByFingerprint(
                fingerprint: String,
                config: GpgKeyserverConfig,
            ): IO<DGpgKeyserverResult?> = ioEffect {
                lookups += fingerprint
                beforeLookup()
                lookup(fingerprint)
            }

            override fun search(
                request: SearchGpgPublicKeyRequest,
                config: GpgKeyserverConfig,
            ): IO<List<DGpgKeyserverResult>> = error("Unexpected search")

            override fun canServeSearch(
                request: SearchGpgPublicKeyRequest,
                config: GpgKeyserverConfig,
            ) = false

            override fun getByEmail(
                email: String,
                config: GpgKeyserverConfig,
            ): IO<List<DGpgKeyserverResult>> = error("Unexpected email lookup")

            override fun upload(
                publicKeyArmored: String,
                config: GpgKeyserverConfig,
            ): IO<Unit> = error("Unexpected upload")
        },
        keyserverStateRepository = stateRepository,
        modifyCipherById = ModifyCipherById(modifyDatabase),
        gpgKeyMetadataResolver = resolver,
        certificateMaterialReconciler = reconciler,
    )

    val request = RefreshGpgPublicKeysRequest(initial.map { it.cipherId }.toSet())

    fun row(id: String = REFRESH_CIPHER_ID) =
        database.cipherQueries.getByCipherId(id).executeAsOne()

    fun update(transform: (BitwardenCipher) -> BitwardenCipher) {
        val current = row().data_
        val updated = transform(current)
        database.transaction {
            // The normal upsert intentionally cannot move a cipher between accounts.
            if (updated.accountId != current.accountId) {
                database.cipherQueries.deleteByCipherId(current.cipherId)
            }
            insert(updated)
        }
    }

    fun insert(cipher: BitwardenCipher) {
        database.cipherQueries.insert(
            cipherId = cipher.cipherId,
            accountId = cipher.accountId,
            folderId = cipher.folderId,
            data = cipher,
            updatedAt = REFRESH_CREATED_AT,
        )
    }

    override fun close() = driver.close()
}
