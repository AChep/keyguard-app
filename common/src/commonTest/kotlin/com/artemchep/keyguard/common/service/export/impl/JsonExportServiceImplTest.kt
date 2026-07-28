package com.artemchep.keyguard.common.service.export.impl

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentFields
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadata
import com.artemchep.keyguard.feature.home.vault.search.createSecret
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class JsonExportServiceImplTest {
    private val json = Json
    private val service = JsonExportServiceImpl(json)

    @Test
    fun `exports gpg key fields before user and link fields`() {
        val cipher = createSecret(
            id = "cipher",
            type = DSecret.Type.SecureNote,
            fields = listOf(
                DSecret.Field(
                    name = GpgAgentFields.PRIVATE_KEY_ARMORED,
                    value = "stale private key",
                    type = DSecret.Field.Type.Text,
                ),
                DSecret.Field(
                    name = "Note",
                    value = "hello",
                    type = DSecret.Field.Type.Text,
                ),
            ),
        ).copy(
            gpgKey = DSecret.GpgKey(
                privateKeyArmored = PRIVATE_KEY_ARMORED,
                publicKeyArmored = PUBLIC_KEY_ARMORED,
                fingerprint = FINGERPRINT,
                metadata = GpgAgentKeyMetadata(),
            ),
            links = listOf(
                DSecret.Link(TARGET_REMOTE_ID),
            ),
        )

        val fields = exportedFields(cipher)
            .jsonArray
            .map { it.jsonObject.toExportedField() }

        assertEquals(
            listOf(
                ExportedField(
                    type = 1,
                    name = GpgAgentFields.PRIVATE_KEY_ARMORED,
                    value = PRIVATE_KEY_ARMORED,
                ),
                ExportedField(
                    type = 0,
                    name = GpgAgentFields.PUBLIC_KEY_ARMORED,
                    value = PUBLIC_KEY_ARMORED,
                ),
                ExportedField(
                    type = 0,
                    name = GpgAgentFields.FINGERPRINT,
                    value = FINGERPRINT,
                ),
                ExportedField(
                    type = 0,
                    name = "Note",
                    value = "hello",
                ),
                ExportedField(
                    type = 0,
                    name = "keyguard.link.1",
                    value = "keyguard://cipher/$TARGET_REMOTE_ID",
                ),
            ),
            fields,
        )
    }

    @Test
    fun `omits blank gpg key fields and metadata`() {
        val cipher = createSecret(
            id = "cipher",
            type = DSecret.Type.GpgKey,
        ).copy(
            gpgKey = DSecret.GpgKey(
                privateKeyArmored = " ",
                publicKeyArmored = PUBLIC_KEY_ARMORED,
                fingerprint = null,
                metadata = GpgAgentKeyMetadata(),
            ),
        )

        assertEquals(
            listOf(
                ExportedField(
                    type = 0,
                    name = GpgAgentFields.PUBLIC_KEY_ARMORED,
                    value = PUBLIC_KEY_ARMORED,
                ),
            ),
            exportedFields(cipher)
                .jsonArray
                .map { it.jsonObject.toExportedField() },
        )
    }

    @Test
    fun `preserves reserved user fields when typed gpg key has no exportable values`() {
        val reservedField = DSecret.Field(
            name = GpgAgentFields.PRIVATE_KEY_ARMORED,
            value = "user value",
            type = DSecret.Field.Type.Text,
        )
        val cipher = createSecret(
            id = "cipher",
            type = DSecret.Type.GpgKey,
            fields = listOf(reservedField),
        ).copy(
            gpgKey = DSecret.GpgKey(
                privateKeyArmored = " ",
                metadata = GpgAgentKeyMetadata(),
            ),
        )

        assertEquals(
            listOf(
                ExportedField(
                    type = 0,
                    name = GpgAgentFields.PRIVATE_KEY_ARMORED,
                    value = "user value",
                ),
            ),
            exportedFields(cipher)
                .jsonArray
                .map { it.jsonObject.toExportedField() },
        )
    }

    @Test
    fun `appends canonical link fields after user fields`() {
        val cipher = createSecret(
            id = "cipher",
            fields = listOf(
                DSecret.Field(
                    name = "Note",
                    value = "hello",
                    type = DSecret.Field.Type.Text,
                ),
            ),
        ).copy(
            links = listOf(
                DSecret.Link(TARGET_REMOTE_ID.uppercase()),
                DSecret.Link(TARGET_REMOTE_ID),
                DSecret.Link(OTHER_REMOTE_ID),
            ),
        )

        val fields = exportedFields(cipher).jsonArray

        assertEquals(
            listOf(
                "Note" to "hello",
                "keyguard.link.1" to "keyguard://cipher/$TARGET_REMOTE_ID",
                "keyguard.link.2" to "keyguard://cipher/$OTHER_REMOTE_ID",
            ),
            fields.map { field ->
                val obj = field.jsonObject
                obj.getValue("name").jsonPrimitive.content to
                        obj.getValue("value").jsonPrimitive.content
            },
        )
    }

    @Test
    fun `exports null fields when there are no user fields or links`() {
        assertEquals(
            JsonNull,
            exportedFields(createSecret(id = "cipher")),
        )
    }

    private fun exportedFields(cipher: DSecret) = json
        .parseToJsonElement(
            service.export(
                organizations = emptyList(),
                collections = emptyList(),
                folders = emptyList(),
                ciphers = listOf(cipher),
            ),
        )
        .jsonObject
        .getValue("items")
        .jsonArray
        .single()
        .jsonObject
        .getValue("fields")

    private fun JsonObject.toExportedField() = ExportedField(
        type = getValue("type").jsonPrimitive.content.toInt(),
        name = getValue("name").jsonPrimitive.content,
        value = getValue("value").jsonPrimitive.content,
    )
}

private data class ExportedField(
    val type: Int,
    val name: String,
    val value: String,
)

private const val TARGET_REMOTE_ID = "b0eebc99-9c0b-4ef8-bb6d-6bb9bd380a12"
private const val OTHER_REMOTE_ID = "c0eebc99-9c0b-4ef8-bb6d-6bb9bd380a13"
private const val PRIVATE_KEY_ARMORED = "-----BEGIN PGP PRIVATE KEY BLOCK-----"
private const val PUBLIC_KEY_ARMORED = "-----BEGIN PGP PUBLIC KEY BLOCK-----"
private const val FINGERPRINT = "0123456789ABCDEF"
