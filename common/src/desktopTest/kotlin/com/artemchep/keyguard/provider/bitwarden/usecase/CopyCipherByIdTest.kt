package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.test.environmentPayload
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.artemchep.keyguard.common.exception.UnsupportedEnvironmentCopyException
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.io
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.model.FolderHierarchyMode
import com.artemchep.keyguard.common.model.create.CreateRequest
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.usecase.AddFolder
import com.artemchep.keyguard.common.usecase.AddFolderRequest
import com.artemchep.keyguard.common.usecase.GetCanWrite
import com.artemchep.keyguard.common.usecase.GetWriteAccess
import com.artemchep.keyguard.common.usecase.QueueSyncById
import com.artemchep.keyguard.common.usecase.ResolveFolderHierarchyMode
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.data.Database
import com.artemchep.keyguard.feature.confirmation.organization.FolderInfo
import com.artemchep.keyguard.feature.environments.EnvironmentDefinition
import com.artemchep.keyguard.feature.environments.EnvironmentFieldReference
import com.artemchep.keyguard.feature.environments.EnvironmentFormat
import com.artemchep.keyguard.feature.environments.EnvironmentIssueType
import com.artemchep.keyguard.feature.environments.EnvironmentPayload
import com.artemchep.keyguard.feature.environments.EnvironmentResolutionResult
import com.artemchep.keyguard.feature.environments.EnvironmentResolver
import com.artemchep.keyguard.provider.bitwarden.mapper.of
import com.artemchep.keyguard.provider.bitwarden.mapper.toDomain
import com.artemchep.keyguard.feature.environments.EnvironmentValueSource
import com.artemchep.keyguard.feature.environments.EnvironmentVariable
import com.artemchep.keyguard.feature.environments.editableDefinition
import com.artemchep.keyguard.provider.bitwarden.sync.v2.UploadTestCryptoGenerator
import com.artemchep.keyguard.provider.bitwarden.sync.v2.UploadTestMarkBackupAsDirty
import com.artemchep.keyguard.provider.bitwarden.sync.v2.UploadTestVaultDatabaseManager
import com.artemchep.keyguard.provider.bitwarden.sync.v2.createUploadTestDatabase
import com.artemchep.keyguard.provider.bitwarden.sync.v2.insertUploadTestAccount
import com.artemchep.keyguard.provider.bitwarden.usecase.util.ModifyCipherById
import com.artemchep.keyguard.provider.bitwarden.usecase.util.ModifyDatabase
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

class CopyCipherByIdTest {
    @Test
    fun `cross-account batch drops links and unsets references even when the target is also copied`() = runTest {
        Fixture().use { fixture ->
            val environment = cipher("environment").copy(
                type = BitwardenCipher.Type.Environment,
                environment = supportedPayload,
            )
            val target = cipher(targetId)
            fixture.insert(environment)
            fixture.insert(target)

            fixture.copy(setOf(environment.cipherId, target.cipherId), "destination")

            val copies = fixture.copies()
            assertEquals(2, copies.size)
            assertTrue(copies.all { it.links.isEmpty() && it.accountId == "destination" })
            val definition = assertIs<EnvironmentPayload.Decoded>(
                copies.single { it.type == BitwardenCipher.Type.Environment }.environment?.toDomain(),
            ).definition
            assertEquals(listOf(
                EnvironmentVariable("PASSWORD", EnvironmentValueSource.Unset),
                EnvironmentVariable("CUSTOM", EnvironmentValueSource.Unset),
                EnvironmentVariable("LITERAL", EnvironmentValueSource.Literal("kept")),
                EnvironmentVariable("EMPTY", EnvironmentValueSource.Literal("")),
                EnvironmentVariable("UNSET", EnvironmentValueSource.Unset),
            ), definition.variables)
            val resolution = assertIs<EnvironmentResolutionResult.Unresolved>(EnvironmentResolver.resolve(definition))
            assertEquals(setOf("PASSWORD", "CUSTOM", "UNSET"), resolution.issues.map { it.name }.toSet())
            assertTrue(resolution.issues.all { it.type == EnvironmentIssueType.Unset })
            assertEquals(environment, fixture.get(environment.cipherId))
            assertEquals(target, fixture.get(target.cipherId))
        }
    }

    @Test
    fun `same-account copies preserve links and references across folders and organizations`() = runTest {
        Fixture().use { fixture ->
            val source = cipher("environment").copy(
                type = BitwardenCipher.Type.Environment,
                environment = supportedPayload,
            )
            fixture.insert(source)
            fixture.copy(setOf(source.cipherId), "source", organizationId = "organization")
            val copy = fixture.copies().single()
            assertEquals("organization", copy.organizationId)
            assertEquals("folder", copy.folderId)
            assertEquals(source.links, copy.links)
            assertEquals(source.environment, copy.environment)
            assertEquals(source, fixture.get(source.cipherId))
        }
    }

    @Test
    fun `literal-only environments can be copied to another account`() = runTest {
        Fixture().use { fixture ->
            val payload = environment(EnvironmentDefinition(listOf(
                EnvironmentVariable("LITERAL", EnvironmentValueSource.Literal("kept")),
            )))
            fixture.insert(cipher("environment").copy(type = BitwardenCipher.Type.Environment, environment = payload))
            fixture.copy(setOf("environment"), "destination")
            assertEquals(payload, fixture.copies().single().environment)
        }
    }

    @Test
    fun `empty environments can be copied to another account`() = runTest {
        Fixture().use { fixture ->
            fixture.insert(cipher("environment").copy(
                type = BitwardenCipher.Type.Environment,
                environment = BitwardenCipher.Environment(),
            ))
            fixture.copy(setOf("environment"), "destination")
            val environment = fixture.copies().single().environment
            assertEquals(EnvironmentDefinition(), environment?.toDomain().editableDefinition())
        }
    }

    @Test
    fun `environments without references are copied to another account as they are`() = runTest {
        Fixture().use { fixture ->
            val document = """{"version":1,"variables":[],"future":true}"""
            fixture.insert(cipher("environment").copy(
                type = BitwardenCipher.Type.Environment,
                environment = BitwardenCipher.Environment(document = document),
            ))
            fixture.copy(setOf("environment"), "destination")
            assertEquals(document, fixture.copies().single().environment?.document)
        }
    }

    @Test
    fun `unsupported environments block the entire cross-account batch but permit same-account copies`() = runTest {
        val payloads = listOf(
            null,
            EnvironmentPayload.Unsupported("""{"version":2,"future":"opaque"}"""),
            environmentPayload(EnvironmentDefinition(listOf(
                EnvironmentVariable("FUTURE", EnvironmentValueSource.Unsupported(JsonPrimitive("future"))),
            ))),
            // Unsetting the reference would rewrite the document and lose the unknown key.
            EnvironmentFormat.decode(
                """{"version":1,"variables":[{"name":"PASSWORD","source":""" +
                    """{"kind":"cipherField","cipherId":"$targetId","field":"password"}}],"future":true}""",
            ),
        ).map { it?.let(BitwardenCipher.Environment::of) }
        for (payload in payloads) {
            Fixture().use { fixture ->
                val ordinary = cipher("ordinary")
                val environment = cipher("environment").copy(
                    type = BitwardenCipher.Type.Environment, environment = payload,
                )
                fixture.insert(ordinary)
                fixture.insert(environment)
                assertFailsWith<UnsupportedEnvironmentCopyException> {
                    fixture.copy(setOf(ordinary.cipherId, environment.cipherId), "destination")
                }
                assertTrue(fixture.copies().isEmpty())
                assertEquals(ordinary, fixture.get(ordinary.cipherId))
                assertEquals(environment, fixture.get(environment.cipherId))

                fixture.copy(setOf(environment.cipherId), "source")
                val copy = fixture.copies().single()
                assertEquals(payload, copy.environment)
                assertEquals(environment.links, copy.links)
            }
        }
    }

    private class Fixture : AutoCloseable {
        private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        private val database = createUploadTestDatabase(driver.also { Database.Schema.create(it) })
        private var nextId = 0
        private val originals = mutableSetOf<String>()

        init {
            database.insertUploadTestAccount("source")
            database.insertUploadTestAccount("destination")
        }

        private val useCase = CopyCipherByIdImpl(
            modifyCipherById = ModifyCipherById(ModifyDatabase(
                db = UploadTestVaultDatabaseManager(database),
                getCanWrite = object : GetCanWrite { override fun invoke() = flowOf(true) },
                getWriteAccess = object : GetWriteAccess { override fun invoke() = flowOf(true) },
                queueSyncById = object : QueueSyncById { override fun invoke(accountId: AccountId) = io(Unit) },
                markBackupAsDirty = UploadTestMarkBackupAsDirty,
            )),
            addFolder = object : AddFolder {
                override fun invoke(requests: Collection<AddFolderRequest>): IO<List<String>> =
                    error("Unexpected folder creation")
            },
            resolveFolderHierarchyMode = object : ResolveFolderHierarchyMode {
                override fun invoke(accountId: AccountId): IO<FolderHierarchyMode> = error("Unexpected folder creation")
            },
            cryptoGenerator = object : CryptoGenerator by UploadTestCryptoGenerator {
                override fun uuid() = "copy-${nextId++}"
            },
        )

        fun insert(cipher: BitwardenCipher) {
            originals += cipher.cipherId
            database.cipherQueries.insert(
                cipherId = cipher.cipherId, accountId = cipher.accountId, folderId = cipher.folderId,
                data = cipher, updatedAt = cipher.revisionDate,
            )
        }

        suspend fun copy(ids: Set<String>, accountId: String, organizationId: String? = null) {
            val ownership = CreateRequest.Ownership2(
                accountId = accountId,
                folder = FolderInfo.Id("folder"),
                organizationId = organizationId,
                collectionIds = if (organizationId != null) setOf("collection") else emptySet(),
            )
            useCase(ids.associateWith { ownership }).bind()
        }

        fun get(id: String) = database.cipherQueries.getByCipherId(id).executeAsOne().data_
        fun copies() = database.cipherQueries.get().executeAsList()
            .filter { it.cipherId !in originals }
            .map { it.data_ }
        override fun close() = driver.close()
    }

    companion object {
        private const val targetId = "01234567-89ab-cdef-0123-456789abcdef"
        private val supportedPayload = environment(EnvironmentDefinition(listOf(
            EnvironmentVariable(
                "PASSWORD", EnvironmentValueSource.Reference(targetId, EnvironmentFieldReference.Password),
            ),
            EnvironmentVariable(
                "CUSTOM", EnvironmentValueSource.Reference(targetId, EnvironmentFieldReference.Custom("field")),
            ),
            EnvironmentVariable("LITERAL", EnvironmentValueSource.Literal("kept")),
            EnvironmentVariable("EMPTY", EnvironmentValueSource.Literal("")),
            EnvironmentVariable("UNSET", EnvironmentValueSource.Unset),
        )))

        private fun environment(definition: EnvironmentDefinition) =
            BitwardenCipher.Environment.of(environmentPayload(definition))

        private fun cipher(id: String) = BitwardenCipher(
            accountId = "source", cipherId = id, revisionDate = Instant.parse("2026-01-01T00:00:00Z"),
            service = BitwardenService(), name = id, notes = null, favorite = false,
            reprompt = BitwardenCipher.RepromptType.None, type = BitwardenCipher.Type.Login,
            links = listOf(BitwardenCipher.Link(targetId)),
        )
    }
}
