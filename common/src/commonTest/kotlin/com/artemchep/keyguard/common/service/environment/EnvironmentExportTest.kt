package com.artemchep.keyguard.common.service.environment

import com.artemchep.keyguard.test.environmentPayload
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.service.credentialexchange.CxfExportSkipReason
import com.artemchep.keyguard.common.service.credentialexchange.FakeSshKeyImportService
import com.artemchep.keyguard.common.service.credentialexchange.FakeSshKeyPkcs8Exporter
import com.artemchep.keyguard.common.service.credentialexchange.cxfExportSkips
import com.artemchep.keyguard.common.service.credentialexchange.cxfProfile
import com.artemchep.keyguard.common.service.credentialexchange.impl.CxfImportSecretMapper
import com.artemchep.keyguard.common.service.credentialexchange.impl.CxfSecretMapper
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfCredential
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfCredentialType
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfEditableField
import com.artemchep.keyguard.common.service.export.impl.JsonExportServiceImpl
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.feature.credentialexchange.export.toNotes
import com.artemchep.keyguard.feature.environments.EnvironmentDefinition
import com.artemchep.keyguard.feature.environments.EnvironmentFieldReference
import com.artemchep.keyguard.feature.environments.EnvironmentFormat
import com.artemchep.keyguard.feature.environments.EnvironmentPayload
import com.artemchep.keyguard.feature.environments.EnvironmentValueSource
import com.artemchep.keyguard.feature.environments.EnvironmentVariable
import com.artemchep.keyguard.provider.bitwarden.crypto.BitwardenEnvironmentFieldCodec
import com.artemchep.keyguard.provider.bitwarden.mapper.toDomain
import com.artemchep.keyguard.test.createSecret
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Instant

private const val REFERENCE_TARGET_ID = "00000000-0000-0000-0000-000000000001"

class EnvironmentExportTest {
    private val mapper = CxfSecretMapper(sshKeyPkcs8Exporter = FakeSshKeyPkcs8Exporter(null))
    private val now = Instant.parse("2024-01-30T14:09:33Z")

    @Test
    fun `JSON backup exports complete environment as compatible protected secure note fields`() {
        val payload = environmentPayload(EnvironmentDefinition(listOf(
            EnvironmentVariable("TOKEN", EnvironmentValueSource.Literal("secret\n🔑".repeat(600))),
            EnvironmentVariable("UNSET", EnvironmentValueSource.Unset),
        )))
        val cipher = createSecret("environment", type = DSecret.Type.Environment).copy(environment = payload)
        val output = JsonExportServiceImpl(Json).export(emptyList(), emptyList(), emptyList(), listOf(cipher))
        val item = Json.parseToJsonElement(output).jsonObject.getValue("items").jsonArray.single().jsonObject
        assertEquals(2, item.getValue("type").jsonPrimitive.int)
        assertNotNull(item["secureNote"])
        val fields = item.getValue("fields").jsonArray.map { raw ->
            val field = raw.jsonObject
            assertEquals(1, field.getValue("type").jsonPrimitive.int)
            BitwardenCipher.Field(
                name = field.getValue("name").jsonPrimitive.content,
                value = field.getValue("value").jsonPrimitive.content,
                type = BitwardenCipher.Field.Type.Hidden,
            )
        }
        assertEquals(payload, BitwardenEnvironmentFieldCodec.decode(fields)?.environment?.toDomain())
    }

    @Test
    fun `JSON backup exports a document read with loss as it is`() {
        val document = """{"version":1,"variables":[],"future":true}"""
        val payload = assertNotNull(EnvironmentFormat.decode(document))
        val cipher = createSecret("environment", type = DSecret.Type.Environment).copy(environment = payload)
        val output = JsonExportServiceImpl(Json).export(emptyList(), emptyList(), emptyList(), listOf(cipher))
        val item = Json.parseToJsonElement(output).jsonObject.getValue("items").jsonArray.single().jsonObject
        val fields = item.getValue("fields").jsonArray.map { raw ->
            BitwardenCipher.Field(
                name = raw.jsonObject.getValue("name").jsonPrimitive.content,
                value = raw.jsonObject.getValue("value").jsonPrimitive.content,
                type = BitwardenCipher.Field.Type.Hidden,
            )
        }
        assertEquals(document, BitwardenEnvironmentFieldCodec.decode(fields)?.environment?.document)
    }

    @Test
    fun `CXF omits corrupt atomic payloads and reports an item loss`() {
        val payload = EnvironmentPayload.Unsupported("""{"version":2,"future":"opaque"}""")
        val cipher = createSecret("environment", type = DSecret.Type.Environment, notes = "retained note")
            .copy(environment = payload)
        val mapper = CxfSecretMapper(sshKeyPkcs8Exporter = FakeSshKeyPkcs8Exporter(null))
        val result = mapper.buildItem(cipher, CxfCredentialType.ALL)
        assertNull(result.item)
        assertEquals(1, result.skips[CxfExportSkipReason.Item])
    }

    @Test
    fun `CXF retains unknown source payloads as protected transport fields`() {
        val raw = Json.parseToJsonElement("""{"kind":"future","reference":"opaque"}""")
        val payload = environmentPayload(EnvironmentDefinition(listOf(
            EnvironmentVariable("TOKEN", EnvironmentValueSource.Unsupported(raw)),
        )))
        val cipher = createSecret("environment", type = DSecret.Type.Environment).copy(environment = payload)
        val mapper = CxfSecretMapper(sshKeyPkcs8Exporter = FakeSshKeyPkcs8Exporter(null))
        val result = mapper.buildItem(cipher, CxfCredentialType.ALL)
        assertEquals(0, result.skips.totalCount)
        val fields = assertIs<CxfCredential.CustomFields>(assertNotNull(result.item).credentials.single()).fields
            .map { field ->
                assertEquals(CxfEditableField.FIELD_TYPE_CONCEALED_STRING, field.fieldType)
                BitwardenCipher.Field(field.label, field.value, type = BitwardenCipher.Field.Type.Hidden)
            }
        assertEquals(payload, BitwardenEnvironmentFieldCodec.decode(fields)?.environment?.toDomain())
    }

    @Test
    fun `CXF omits complete environments with references even when their targets are exported`() {
        val target = createSecret(
            id = "device-local-target",
            login = DSecret.Login(password = "target-password"),
            fields = listOf(DSecret.Field("TOKEN", "target-token", type = DSecret.Field.Type.Hidden)),
            service = BitwardenService(remote = BitwardenService.Remote(
                id = REFERENCE_TARGET_ID,
                revisionDate = now,
                deletedDate = null,
            )),
        )
        val password = EnvironmentValueSource.Reference(REFERENCE_TARGET_ID, EnvironmentFieldReference.Password)
        val custom = EnvironmentValueSource.Reference(REFERENCE_TARGET_ID, EnvironmentFieldReference.Custom("TOKEN"))
        val sources = listOf(
            listOf(password),
            listOf(custom),
            listOf(password, custom, EnvironmentValueSource.Literal("literal"), EnvironmentValueSource.Unset),
        )
        sources.forEach { values ->
            val environment = environment(values).copy(
                notes = "Do not export a partial environment",
                fields = listOf(DSecret.Field("extra", "value", type = DSecret.Field.Type.Text)),
                passwordHistory = listOf(DSecret.Login.PasswordHistory("old-password", now)),
            )
            listOf(emptyList(), listOf(target)).forEach { targets ->
                val result = mapper.buildAccountResult(
                    profile = cxfProfile(accountId = environment.accountId),
                    ciphers = listOf(environment) + targets,
                    allowedTypes = CxfCredentialType.ALL,
                    folders = emptyList(),
                )
                assertEquals(
                    targets.map { mapper.buildItem(it, CxfCredentialType.ALL).item },
                    result.account?.items.orEmpty(),
                )
                assertEquals(1, result.skips.totalCount)
                assertEquals(1, result.skips[CxfExportSkipReason.EnvironmentReference])
                assertEquals(
                    mapOf(environment.name to 1),
                    result.skips.titlesOf(CxfExportSkipReason.EnvironmentReference),
                )
                // An account with no transferable items must still expose its
                // warning, with the affected environment available to expand.
                val note = result.skips.toNotes().single()
                assertEquals("EnvironmentReference", note.id)
                assertEquals(listOf(environment.name), note.titles.map { it.text })
                assertNotNull(note.onToggle)
            }
        }
    }

    @Test
    fun `CXF reference skips honor deletion archive and requested credential types`() {
        val environment = environment(listOf(
            EnvironmentValueSource.Reference(REFERENCE_TARGET_ID, EnvironmentFieldReference.Password),
        ))
        data class Case(
            val secret: DSecret,
            val types: Set<CxfCredentialType>,
            val reason: CxfExportSkipReason? = null,
        )
        val cases = listOf(
            Case(environment.copy(deletedDate = now), CxfCredentialType.ALL),
            Case(environment.copy(deletedDate = now, archivedDate = now), CxfCredentialType.ALL),
            Case(environment.copy(archivedDate = now), CxfCredentialType.ALL, CxfExportSkipReason.Archived),
            Case(environment.copy(archivedDate = now), setOf(CxfCredentialType.BasicAuth)),
            Case(environment, emptySet()),
            Case(environment, setOf(CxfCredentialType.BasicAuth)),
            Case(environment, setOf(CxfCredentialType.Note)),
            Case(environment, setOf(CxfCredentialType.CustomFields), CxfExportSkipReason.EnvironmentReference),
            Case(
                environment.copy(notes = "note"),
                setOf(CxfCredentialType.Note),
                CxfExportSkipReason.EnvironmentReference,
            ),
            Case(
                environment.copy(notes = "note", archivedDate = now),
                setOf(CxfCredentialType.Note),
                CxfExportSkipReason.Archived,
            ),
        )
        cases.forEachIndexed { index, case ->
            val result = mapper.buildItem(case.secret, case.types)
            assertNull(result.item, "case $index")
            val expected = case.reason?.let { cxfExportSkips(it to 1) } ?: cxfExportSkips()
            assertEquals(expected, result.skips, "case $index")
        }
    }

    @Test
    fun `CXF round trips literal and unset environments as protected transport fields`() {
        val environment = environment(listOf(
            EnvironmentValueSource.Literal("secret\n🔑".repeat(600)),
            EnvironmentValueSource.Unset,
        ))
        val exported = mapper.buildItem(environment, CxfCredentialType.ALL)
        assertEquals(0, exported.skips.totalCount)
        val imported = CxfImportSecretMapper(FakeSshKeyImportService())
            .mapItem(assertNotNull(exported.item), now)
        assertEquals(0, imported.skips.totalCount)
        val request = imported.requests.single()
        assertEquals(DSecret.Type.SecureNote, request.type)
        val fields = request.fields.map { field ->
            assertEquals(DSecret.Field.Type.Hidden, field.type)
            BitwardenCipher.Field(field.name, field.value, type = BitwardenCipher.Field.Type.Hidden)
        }
        assertEquals(environment.environment, BitwardenEnvironmentFieldCodec.decode(fields)?.environment?.toDomain())
    }

    private fun environment(sources: List<EnvironmentValueSource>): DSecret =
        createSecret("environment", type = DSecret.Type.Environment).copy(
            environment = environmentPayload(EnvironmentDefinition(
                variables = sources.mapIndexed { index, source -> EnvironmentVariable("VAR_$index", source) },
            )),
        )
}
