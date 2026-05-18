package com.artemchep.keyguard.provider.bitwarden.api

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NotificationsHubMessageTest {
    @Test
    fun `parse accepts Bitwarden style message`() {
        val message = mapOf(
            "Type" to 0,
            "ContextId" to "device-1",
            "Payload" to mapOf(
                "Id" to "cipher-1",
            ),
        )

        val parsed = parseNotificationsHubMessage(message)

        assertEquals(NotificationsHubEventType.SYNC_CIPHER_UPDATE, parsed?.type)
        assertEquals(0, parsed?.rawType)
        assertEquals("device-1", parsed?.contextId)
    }

    @Test
    fun `parse accepts lower case keys and string type`() {
        val message = mapOf(
            "type" to "5",
            "contextId" to "device-2",
        )

        val parsed = parseNotificationsHubMessage(message)

        assertEquals(NotificationsHubEventType.SYNC_VAULT, parsed?.type)
        assertEquals(5, parsed?.rawType)
        assertEquals("device-2", parsed?.contextId)
    }

    @Test
    fun `parse accepts SignalRKore json message`() {
        val message = JsonObject(
            mapOf(
                "type" to JsonPrimitive("5"),
                "contextId" to JsonPrimitive("device-2"),
            ),
        )

        val parsed = parseNotificationsHubMessage(message)

        assertEquals(NotificationsHubEventType.SYNC_VAULT, parsed?.type)
        assertEquals(5, parsed?.rawType)
        assertEquals("device-2", parsed?.contextId)
    }

    @Test
    fun `parse returns null for unknown payload shape`() {
        assertNull(parseNotificationsHubMessage("not a map"))
        assertNull(parseNotificationsHubMessage(mapOf("Payload" to emptyMap<String, String>())))
    }

    @Test
    fun `unknown payload shape falls back to sync`() {
        val action = resolveNotificationsHubMessageAction(
            message = "not a map",
            currentDeviceId = "device-1",
        )

        assertEquals(NotificationsHubMessageAction.SYNC, action)
    }

    @Test
    fun `self-originated message is ignored`() {
        val action = resolveNotificationsHubMessageAction(
            message = mapOf(
                "Type" to NotificationsHubEventType.SYNC_CIPHERS.code,
                "ContextId" to "device-1",
            ),
            currentDeviceId = "DEVICE-1",
        )

        assertEquals(NotificationsHubMessageAction.IGNORE, action)
    }

    @Test
    fun `auth request and notification center messages are ignored`() {
        val ignoredTypes = listOf(
            NotificationsHubEventType.AUTH_REQUEST,
            NotificationsHubEventType.AUTH_REQUEST_RESPONSE,
            NotificationsHubEventType.NOTIFICATION,
            NotificationsHubEventType.NOTIFICATION_STATUS,
        )

        ignoredTypes.forEach { type ->
            val action = resolveNotificationsHubMessageAction(
                message = mapOf("Type" to type.code),
                currentDeviceId = "device-1",
            )

            assertEquals(NotificationsHubMessageAction.IGNORE, action)
        }
    }

    @Test
    fun `logout message has explicit action`() {
        val action = resolveNotificationsHubMessageAction(
            message = mapOf("Type" to NotificationsHubEventType.LOG_OUT.code),
            currentDeviceId = "device-1",
        )

        assertEquals(NotificationsHubMessageAction.LOG_OUT, action)
    }

    @Test
    fun `unknown future type falls back to sync`() {
        val action = resolveNotificationsHubMessageAction(
            message = mapOf("Type" to 999),
            currentDeviceId = "device-1",
        )

        assertEquals(NotificationsHubMessageAction.SYNC, action)
    }
}
