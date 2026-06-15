package com.artemchep.keyguard.provider.bitwarden.entity.request

import com.artemchep.keyguard.provider.bitwarden.entity.CipherRepromptTypeEntity
import com.artemchep.keyguard.provider.bitwarden.entity.CipherTypeEntity
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CipherBulkRequestSerializationTest {
    @Test
    fun `cipher archive request uses ids key`() {
        val json = encodeToJsonObject(
            CipherArchiveRequest(
                ids = listOf("cipher-1"),
            ),
        )

        assertEquals(setOf("ids"), json.keys)
        assertEquals("cipher-1", json["ids"]!!.jsonArray[0].jsonPrimitive.content)
    }

    @Test
    fun `cipher unarchive request uses ids key`() {
        val json = encodeToJsonObject(
            CipherUnarchiveRequest(
                ids = listOf("cipher-1"),
            ),
        )

        assertEquals(setOf("ids"), json.keys)
        assertEquals("cipher-1", json["ids"]!!.jsonArray[0].jsonPrimitive.content)
    }

    @Test
    fun `cipher delete request uses ids key`() {
        val json = encodeToJsonObject(
            CipherDeleteRequest(
                ids = listOf("cipher-1", "cipher-2"),
            ),
        )

        assertEquals(setOf("ids"), json.keys)
        assertEquals("cipher-1", json["ids"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("cipher-2", json["ids"]!!.jsonArray[1].jsonPrimitive.content)
    }

    @Test
    fun `cipher restore request uses ids key`() {
        val json = encodeToJsonObject(
            CipherRestoreRequest(
                ids = listOf("cipher-1"),
            ),
        )

        assertEquals(setOf("ids"), json.keys)
        assertEquals("cipher-1", json["ids"]!!.jsonArray[0].jsonPrimitive.content)
    }

    @Test
    fun `cipher move request uses ids and folder id keys`() {
        val json = encodeToJsonObject(
            CipherMoveRequest(
                ids = listOf("cipher-1"),
                folderId = "folder-1",
            ),
        )

        assertEquals(setOf("ids", "folderId"), json.keys)
        assertEquals("cipher-1", json["ids"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("folder-1", json["folderId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `cipher bulk share request uses collection ids and ciphers keys`() {
        val json = encodeToJsonObject(
            CipherBulkShareRequest(
                collectionIds = listOf("collection-1"),
                ciphers = listOf(createCipherRequest()),
            ),
        )

        assertEquals(setOf("collectionIds", "ciphers"), json.keys)
        assertEquals("collection-1", json["collectionIds"]!!.jsonArray[0].jsonPrimitive.content)
        val cipher = json["ciphers"]!!.jsonArray[0].jsonObject
        assertEquals("cipher-share", cipher["id"]!!.jsonPrimitive.content)
        assertEquals("org-1", cipher["organizationId"]!!.jsonPrimitive.content)
        assertEquals("Cipher", cipher["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `cipher bulk update collections request uses upstream keys`() {
        val json = encodeToJsonObject(
            CipherBulkUpdateCollectionsRequest(
                organizationId = "org-1",
                cipherIds = listOf("cipher-1"),
                collectionIds = listOf("collection-1", "collection-2"),
                removeCollections = true,
            ),
        )

        assertEquals(
            setOf("organizationId", "cipherIds", "collectionIds", "removeCollections"),
            json.keys,
        )
        assertEquals("org-1", json["organizationId"]!!.jsonPrimitive.content)
        assertEquals("cipher-1", json["cipherIds"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("collection-2", json["collectionIds"]!!.jsonArray[1].jsonPrimitive.content)
        assertTrue(json["removeCollections"]!!.jsonPrimitive.boolean)
    }

    private inline fun <reified T> encodeToJsonObject(value: T) = Json
        .parseToJsonElement(Json.encodeToString(value))
        .jsonObject

    private fun createCipherRequest() = CipherWithIdRequest(
        id = "cipher-share",
        key = null,
        type = CipherTypeEntity.SecureNote,
        organizationId = "org-1",
        folderId = null,
        name = "Cipher",
        notes = null,
        favorite = false,
        login = null,
        secureNote = null,
        card = null,
        identity = null,
        sshKey = null,
        fields = null,
        passwordHistory = null,
        attachments = null,
        attachments2 = null,
        lastKnownRevisionDate = null,
        archivedDate = null,
        reprompt = CipherRepromptTypeEntity.None,
    )
}
