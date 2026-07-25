package com.artemchep.keyguard.common.service.export.impl

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.feature.home.vault.search.createSecret
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class JsonExportServiceImplTest {
    private val json = Json
    private val service = JsonExportServiceImpl(json)

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
}

private const val TARGET_REMOTE_ID = "b0eebc99-9c0b-4ef8-bb6d-6bb9bd380a12"
private const val OTHER_REMOTE_ID = "c0eebc99-9c0b-4ef8-bb6d-6bb9bd380a13"
