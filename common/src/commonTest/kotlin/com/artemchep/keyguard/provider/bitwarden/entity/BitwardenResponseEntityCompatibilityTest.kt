package com.artemchep.keyguard.provider.bitwarden.entity

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class BitwardenResponseEntityCompatibilityTest {
    @Test
    fun `cipher list decodes camel and pascal data keys`() {
        val camel = json.decodeFromString<CipherListEntity>(
            """
            {
              "data": [
                {
                  "id": "cipher-1",
                  "type": 2,
                  "revisionDate": "2024-01-01T00:00:00Z"
                }
              ]
            }
            """.trimIndent(),
        )
        val pascal = json.decodeFromString<CipherListEntity>(
            """
            {
              "Data": [
                {
                  "Id": "cipher-2",
                  "Type": 2,
                  "RevisionDate": "2024-01-01T00:00:00Z"
                }
              ]
            }
            """.trimIndent(),
        )

        assertEquals("cipher-1", camel.data.single().id)
        assertEquals("cipher-2", pascal.data.single().id)
    }

    @Test
    fun `cipher data serializes as json string`() {
        val entity = CipherEntity(
            id = "cipher-1",
            revisionDate = Instant.parse("2024-01-01T00:00:00Z"),
            type = CipherTypeEntity.Login,
            data = CipherDataEntity(
                name = "Data Cipher",
                username = "data-user",
            ),
        )

        val encoded = json.encodeToString(entity)
        val encodedJson = json.parseToJsonElement(encoded).jsonObject
        val dataJson = encodedJson.getValue("Data").jsonPrimitive
        val data = json.decodeFromString<CipherDataEntity>(dataJson.content)

        assertTrue(dataJson.isString)
        assertEquals("Data Cipher", data.name)
        assertEquals("data-user", data.username)
    }

    @Test
    fun `collection decodes camel and pascal external id keys`() {
        val camel = json.decodeFromString<CollectionEntity>(
            """
            {
              "id": "collection-1",
              "organizationId": "org-1",
              "name": "Collection",
              "externalId": "external-1",
              "readOnly": false,
              "hidePasswords": false
            }
            """.trimIndent(),
        )
        val pascal = json.decodeFromString<CollectionEntity>(
            """
            {
              "Id": "collection-2",
              "OrganizationId": "org-1",
              "Name": "Collection",
              "ExternalId": "external-2",
              "ReadOnly": false,
              "HidePasswords": false
            }
            """.trimIndent(),
        )

        assertEquals("external-1", camel.externalId)
        assertEquals("external-2", pascal.externalId)
    }

    @Test
    fun `policy decodes camel and pascal keys`() {
        val camel = json.decodeFromString<PolicyEntity>(
            """
            {
              "id": "policy-1",
              "organizationId": "org-1",
              "type": 0,
              "data": {},
              "enabled": true,
              "revisionDate": "2024-01-01T00:00:00Z"
            }
            """.trimIndent(),
        )
        val pascal = json.decodeFromString<PolicyEntity>(
            """
            {
              "Id": "policy-2",
              "OrganizationId": "org-1",
              "Type": 0,
              "Data": {},
              "Enabled": true,
              "RevisionDate": "2024-01-01T00:00:00Z"
            }
            """.trimIndent(),
        )

        assertEquals("policy-1", camel.id)
        assertEquals("policy-2", pascal.id)
    }

    private companion object {
        val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            isLenient = true
        }
    }
}
