package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.test.environmentPayload
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.io
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.FolderHierarchyMode
import com.artemchep.keyguard.common.model.FolderOwnership2
import com.artemchep.keyguard.common.model.create.CreateRequest
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.environment.EnvironmentOperationGuard
import com.artemchep.keyguard.common.service.environment.EnvironmentServiceException
import com.artemchep.keyguard.common.usecase.AddFolder
import com.artemchep.keyguard.common.usecase.AddFolderRequest
import com.artemchep.keyguard.common.usecase.ArchiveCipherById
import com.artemchep.keyguard.common.usecase.GetCanWrite
import com.artemchep.keyguard.common.usecase.GetWriteAccess
import com.artemchep.keyguard.common.usecase.QueueSyncById
import com.artemchep.keyguard.common.usecase.ResolveFolderHierarchyMode
import com.artemchep.keyguard.common.usecase.TrashCipherById
import com.artemchep.keyguard.copy.Base64ServiceJvm
import com.artemchep.keyguard.data.Database
import com.artemchep.keyguard.feature.confirmation.organization.FolderInfo
import com.artemchep.keyguard.feature.environments.EnvironmentDefinition
import com.artemchep.keyguard.feature.environments.EnvironmentPayload
import com.artemchep.keyguard.provider.bitwarden.crypto.BitwardenEnvironmentFieldCodec
import com.artemchep.keyguard.provider.bitwarden.mapper.of
import com.artemchep.keyguard.feature.environments.EnvironmentValueSource
import com.artemchep.keyguard.feature.environments.EnvironmentVariable
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.provider.bitwarden.mapper.toDomain
import com.artemchep.keyguard.provider.bitwarden.sync.v2.UploadTestCryptoGenerator
import com.artemchep.keyguard.provider.bitwarden.sync.v2.UploadTestMarkBackupAsDirty
import com.artemchep.keyguard.provider.bitwarden.sync.v2.UploadTestPasswordStrength
import com.artemchep.keyguard.provider.bitwarden.sync.v2.UploadTestPendingUploadCoordinator
import com.artemchep.keyguard.provider.bitwarden.sync.v2.UploadTestVaultDatabaseManager
import com.artemchep.keyguard.provider.bitwarden.sync.v2.createUploadTestDatabase
import com.artemchep.keyguard.provider.bitwarden.sync.v2.insertUploadTestAccount
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadCoordinator
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadFile
import com.artemchep.keyguard.provider.bitwarden.usecase.util.ModifyDatabase
import com.artemchep.keyguard.provider.bitwarden.usecase.util.ModifyCipherById
import kotlinx.collections.immutable.toPersistentList
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

class EnvironmentProviderAuthorizationTest {
    @Test
    fun `revocation while waiting on the database prevents a provider write`() = runTest {
        Fixture(Boundary.DATABASE).use { fixture ->
            var allowed = true
            val pending = async {
                runCatching {
                    withContext(EnvironmentOperationGuard(currentCoroutineContext()) {
                        if (!allowed) throw EnvironmentServiceException("integration_disabled")
                    }) { fixture.save() }
                }
            }
            select {
                fixture.entered.onAwait { }
                pending.onAwait { it.getOrThrow(); error("Provider did not reach the database boundary") }
            }
            allowed = false
            fixture.resume.complete(Unit)
            val failure = assertIs<EnvironmentServiceException>(pending.await().exceptionOrNull())
            assertEquals("integration_disabled", failure.code)
            assertNull(fixture.row())
        }
    }

    @Test
    fun `revocation during upload preparation prevents the final database transaction`() = runTest {
        Fixture(Boundary.TRANSACTION).use { fixture ->
            var allowed = true
            val pending = async {
                runCatching {
                    withContext(EnvironmentOperationGuard(currentCoroutineContext()) {
                        if (!allowed) throw EnvironmentServiceException("integration_disabled")
                    }) { fixture.save() }
                }
            }
            select {
                fixture.entered.onAwait { }
                pending.onAwait { it.getOrThrow(); error("Provider did not reach the transaction boundary") }
            }
            allowed = false
            fixture.resume.complete(Unit)
            val failure = assertIs<EnvironmentServiceException>(pending.await().exceptionOrNull())
            assertEquals("integration_disabled", failure.code)
            assertNull(fixture.row())
        }
    }

    @Test
    fun `authorized provider writes still reach the database`() = runTest {
        Fixture(Boundary.NONE).use { fixture ->
            withContext(EnvironmentOperationGuard(currentCoroutineContext()) {}) { fixture.save() }
            assertEquals("Environment", assertNotNull(fixture.row()).data_.name)
        }
    }

    @Test
    fun `secure note import restores literal unset and future environment sources in SQLite`() = runTest {
        Fixture(Boundary.NONE).use { fixture ->
            val payload = BitwardenCipher.Environment.of(environmentPayload(EnvironmentDefinition(listOf(
                EnvironmentVariable("TOKEN", EnvironmentValueSource.Literal("preserved-secret")),
                EnvironmentVariable("REQUIRED", EnvironmentValueSource.Unset),
                EnvironmentVariable("FUTURE", EnvironmentValueSource.Unsupported(
                    Json.parseToJsonElement("""{"kind":"future","reference":"retained"}"""),
                )),
            ))))
            fixture.importEnvironment(payload)
            val stored = assertNotNull(fixture.row()).data_
            assertEquals(BitwardenCipher.Type.Environment, stored.type)
            assertEquals(payload, stored.environment)
            assertTrue(stored.fields.none { BitwardenEnvironmentFieldCodec.isTransportField(it.name) })
        }
    }

    @Test
    fun `copy move trash and restore preserve an opaque environment without changing its source`() = runTest {
        Fixture(Boundary.NONE).use { fixture ->
            val payload = BitwardenCipher.Environment.of(
                EnvironmentPayload.Unsupported("""{"version":2,"future":"opaque"}"""),
            )
            fixture.save()
            fixture.replacePayload(payload)
            val source = assertNotNull(fixture.row()).data_
            fixture.copyEnvironment()
            val copied = assertNotNull(fixture.row("copied-environment")).data_
            assertEquals(payload, copied.environment)
            assertEquals("account", copied.accountId)
            assertEquals(BitwardenCipher.Type.Environment, copied.type)
            fixture.moveAndTrashCopy()
            val trashed = assertNotNull(fixture.row("copied-environment")).data_
            assertEquals("destination-folder", trashed.folderId)
            assertNotNull(trashed.deletedDate)
            assertEquals(payload, trashed.environment)
            fixture.restoreCopy()
            val restored = assertNotNull(fixture.row("copied-environment")).data_
            assertNull(restored.deletedDate)
            assertEquals(payload, restored.environment)
            assertEquals(source, assertNotNull(fixture.row()).data_)
        }
    }

    private enum class Boundary { NONE, DATABASE, TRANSACTION }

    private class Fixture(private val boundary: Boundary) : AutoCloseable {
        private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        private val database = createUploadTestDatabase(driver.also { Database.Schema.create(it) })
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()

        init { database.insertUploadTestAccount("account") }

        private suspend fun waitAt(value: Boundary) {
            if (boundary == value) {
                entered.complete(Unit)
                resume.await()
            }
        }

        private val manager = object : VaultDatabaseManager by UploadTestVaultDatabaseManager(database) {
            override fun <T> mutate(tag: String, block: suspend (Database) -> T): IO<T> = ioEffect {
                waitAt(Boundary.DATABASE)
                block(database)
            }
        }
        private val uploads = object : PendingUploadCoordinator by UploadTestPendingUploadCoordinator() {
            override suspend fun <T> persist(
                createdPendingUploads: Collection<PendingUploadFile>,
                removedPendingUploads: Collection<PendingUploadFile>,
                block: suspend () -> T,
            ): T {
                waitAt(Boundary.TRANSACTION)
                return block()
            }
        }
        private val modifyDatabase = ModifyDatabase(
            db = manager,
            getCanWrite = object : GetCanWrite { override fun invoke() = flowOf(true) },
            getWriteAccess = object : GetWriteAccess { override fun invoke() = flowOf(true) },
            queueSyncById = object : QueueSyncById { override fun invoke(accountId: AccountId) = io(Unit) },
            markBackupAsDirty = UploadTestMarkBackupAsDirty,
        )
        private val modifyCipher = ModifyCipherById(modifyDatabase)
        private val addFolder = object : AddFolder {
            override fun invoke(requests: Collection<AddFolderRequest>): IO<List<String>> = error("Unexpected folder")
        }
        private val resolveHierarchy = object : ResolveFolderHierarchyMode {
            override fun invoke(accountId: AccountId): IO<FolderHierarchyMode> = error("Unexpected folder")
        }
        private val useCase = AddCipherImpl(
            modifyDatabase = modifyDatabase,
            addFolder = addFolder,
            resolveFolderHierarchyMode = resolveHierarchy,
            archiveCipherById = object : ArchiveCipherById {
                override fun invoke(ids: Set<String>) = io(Unit)
            },
            trashCipherById = object : TrashCipherById {
                override fun invoke(ids: Set<String>) = io(Unit)
            },
            cryptoGenerator = UploadTestCryptoGenerator,
            getPasswordStrength = UploadTestPasswordStrength,
            gpgKeyMetadataResolver = null,
            base64Service = Base64ServiceJvm(),
            pendingUploadCoordinator = uploads,
        )

        suspend fun save() = useCase(mapOf(null to CreateRequest(
            ownership2 = CreateRequest.Ownership2("account", FolderInfo.None, null, emptySet()),
            title = "Environment", type = DSecret.Type.Environment, environment = EnvironmentDefinition(),
            favorite = false, reprompt = false,
            now = Clock.System.now(),
        ))).bind()

        suspend fun importEnvironment(payload: BitwardenCipher.Environment) = useCase(mapOf(null to CreateRequest(
            ownership2 = CreateRequest.Ownership2("account", FolderInfo.None, null, emptySet()),
            title = "Imported", type = DSecret.Type.SecureNote,
            favorite = false, reprompt = false,
            fields = BitwardenEnvironmentFieldCodec.encode(payload).map { it.toDomain() }.toPersistentList(),
            now = Clock.System.now(),
        ))).bind()

        fun replacePayload(payload: BitwardenCipher.Environment) {
            val row = checkNotNull(row())
            database.cipherQueries.insert(
                cipherId = row.cipherId,
                accountId = row.accountId,
                folderId = row.folderId,
                data = row.data_.copy(environment = payload),
                updatedAt = Clock.System.now(),
            )
        }

        suspend fun copyEnvironment() {
            val useCase = CopyCipherByIdImpl(
                modifyCipher, addFolder, resolveHierarchy,
                object : CryptoGenerator by UploadTestCryptoGenerator {
                    override fun uuid() = "copied-environment"
                },
            )
            useCase(mapOf("generated-uuid" to CreateRequest.Ownership2(
                "account", FolderInfo.None, null, emptySet(),
            ))).bind()
        }

        suspend fun moveAndTrashCopy() {
            MoveCipherToFolderByIdImpl(modifyCipher, addFolder, resolveHierarchy)(
                setOf("copied-environment"), FolderOwnership2("account", FolderInfo.Id("destination-folder")),
            ).bind()
            assertEquals(
                "destination-folder", checkNotNull(row("copied-environment")).data_.folderId,
            )
            TrashCipherByIdImpl(modifyCipher)(setOf("copied-environment")).bind()
        }

        suspend fun restoreCopy() = RestoreCipherByIdImpl(modifyCipher)(setOf("copied-environment")).bind()

        fun row(id: String = "generated-uuid") = database.cipherQueries.getByCipherId(id).executeAsOneOrNull()
        override fun close() { driver.close() }
    }
}
