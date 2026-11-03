package com.artemchep.keyguard.integration.bitwarden

import com.artemchep.keyguard.provider.bitwarden.api.notificationsHub
import com.artemchep.keyguard.util.signalr.HubConnectionHttpException
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Keyguard's live sync notifications, through the server's SignalR hub. */
class KeyguardNotificationsE2eTest {
    @Test
    fun `cipher and folder creation reach the hub`() = runE2eTest {
        val account = accounts.register()
        val session = keyguardLogin(account)
        val messages = ConcurrentLinkedQueue<Any>()
        val connected = CompletableDeferred<Unit>()
        // The hub session waits in real time, so it runs off the test's event loop.
        val hub = CoroutineScope(Dispatchers.IO).async {
            notificationsHub(
                user = session.token,
                httpClient = httpClient,
                onConnected = { connected.complete(Unit) },
                onMessage = { message -> messages += message },
                onHeartbeat = {},
            )
        }
        try {
            withTimeout(HUB_TIMEOUT) { connected.await() }
            // Another client, so the server tags the changes with a device that is not Keyguard's.
            val otherDeviceId = UUID.randomUUID().toString()
            val accessToken = accounts.login(account, deviceIdentifier = otherDeviceId).accessToken
            val cipherId = accounts.createLoginCipher(
                accessToken = accessToken,
                account = account,
                name = "Example",
                username = "alice",
                password = "hunter2",
            )
            val folderId = accounts.createFolder(accessToken, account, name = "Work")

            val cipherCreated = awaitMessage(hub, messages, type = SYNC_CIPHER_CREATE, id = cipherId)
            val folderCreated = awaitMessage(hub, messages, type = SYNC_FOLDER_CREATE, id = folderId)
            val keyguardDeviceId = deviceId().lowercase()
            listOf(cipherCreated, folderCreated).forEach { message ->
                val contextId = assertNotNull(message.stringOrNull("ContextId"), "No ContextId in $message")
                assertEquals(otherDeviceId, contextId.lowercase())
                assertNotEquals(keyguardDeviceId, contextId.lowercase())
            }
        } finally {
            hub.cancel()
            withTimeout(STOP_TIMEOUT) { hub.join() }
        }
        val failure = runCatching { hub.await() }.exceptionOrNull()
        assertTrue(failure == null || failure is CancellationException, "The hub failed on cancel: $failure")
    }

    @Test
    fun `hub rejects an invalid token`() = runE2eTest {
        val session = keyguardLogin(accounts.register())
        val token = requireNotNull(session.token.token)
        val user = session.token.copy(token = token.copy(accessToken = "invalid"))

        val error = assertFailsWith<HubConnectionHttpException> {
            withContext(Dispatchers.IO) {
                withTimeout(HUB_TIMEOUT) {
                    notificationsHub(
                        user = user,
                        httpClient = httpClient,
                        onConnected = {},
                        onMessage = {},
                        onHeartbeat = {},
                    )
                }
            }
        }
        assertEquals(HttpStatusCode.Unauthorized, error.statusCode)
    }

    companion object {
        /** Bitwarden's `PushType`s, as Vaultwarden sends them. */
        private const val SYNC_CIPHER_CREATE = 1
        private const val SYNC_FOLDER_CREATE = 7

        private val HUB_TIMEOUT = 30.seconds
        private val STOP_TIMEOUT = 5.seconds
        private val POLL_INTERVAL = 100.milliseconds

        /** Waits for a message of [type] about the item [id]; fails early if the [hub] ends. */
        private suspend fun awaitMessage(
            hub: Deferred<Unit>,
            messages: Collection<Any>,
            type: Int,
            id: String,
        ): JsonObject = withTimeoutOrNull(HUB_TIMEOUT) {
            var message = messages.findMessage(type, id)
            while (message == null) {
                if (!hub.isActive) {
                    hub.await()
                    error("The hub disconnected before message $type for $id arrived: $messages")
                }
                delay(POLL_INTERVAL)
                message = messages.findMessage(type, id)
            }
            message
        } ?: error("No message $type for $id in $HUB_TIMEOUT, got: $messages")
    }
}

/** A `ReceiveMessage` argument: `Type`, `ContextId` and a `Payload` with the item's `Id`. */
private fun Collection<Any>.findMessage(type: Int, id: String): JsonObject? = filterIsInstance<JsonObject>()
    .firstOrNull { message ->
        val payload = message["Payload"] as? JsonObject
        message["Type"]?.jsonPrimitive?.intOrNull == type && payload?.stringOrNull("Id") == id
    }
