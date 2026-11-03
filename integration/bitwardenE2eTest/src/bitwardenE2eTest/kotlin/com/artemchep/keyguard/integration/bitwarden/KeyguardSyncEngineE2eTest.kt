package com.artemchep.keyguard.integration.bitwarden

import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

/** Keyguard's production Bitwarden sync engine against a real server. */
class KeyguardSyncEngineE2eTest {
    @Test
    fun `first sync stores decrypted ciphers and folders`() = runE2eTest {
        val account = accounts.register()
        val accessToken = accounts.login(account).accessToken
        val cipherId = accounts.createLoginCipher(
            accessToken = accessToken,
            account = account,
            name = "Example",
            username = "alice",
            password = "hunter2",
        )
        val folderId = accounts.createFolder(accessToken, account, name = "Work")
        val session = keyguardLogin(account)

        KeyguardSyncEngine.create(this, session).use { engine ->
            val requests = syncRequests(engine)
            assertContains(requests, "GET /api/accounts/revision-date")
            assertContains(requests, "GET /api/sync")

            val cipher = engine.ciphers().single()
            assertEquals(cipherId, cipher.service.remote?.id)
            assertEquals("Example", cipher.name)
            assertEquals("alice", cipher.login?.username)
            assertEquals("hunter2", cipher.login?.password)
            val folder = engine.folders().single()
            assertEquals(folderId, folder.service.remote?.id)
            assertEquals("Work", folder.name)
        }
    }

    @Test
    fun `local changes upload to the server`() = runE2eTest {
        val account = accounts.register()
        val accessToken = accounts.login(account).accessToken
        val session = keyguardLogin(account)

        KeyguardSyncEngine.create(this, session).use { engine ->
            engine.sync()
            val local = newLoginCipher(session.token.id, name = "Local", username = "bob", password = "s3cret")
            engine.insertCipher(local)
            assertContains(syncRequests(engine), "POST /api/ciphers/")
            val created = engine.ciphers().single()
            assertEquals(local.cipherId, created.cipherId)
            assertNull(created.service.error)
            val remoteId = assertNotNull(created.service.remote).id
            val serverCreated = assertNotNull(serverCipher(accessToken, remoteId))
            assertEquals("Local", decrypt(account, serverCreated.string("name")))
            assertEquals("bob", decrypt(account, serverCreated.getValue("login").jsonObject.string("username")))

            waitPastServerWrite()
            engine.updateCipher(created.copy(name = "Renamed"))
            assertContains(syncRequests(engine), "PUT /api/ciphers/$remoteId")
            assertNull(engine.ciphers().single().service.error)
            val serverRenamed = assertNotNull(serverCipher(accessToken, remoteId))
            assertEquals("Renamed", decrypt(account, serverRenamed.string("name")))

            // Like TrashCipherById: the revision date stays, as Bitwarden does not change it on trash.
            waitPastServerWrite()
            engine.insertCipher(engine.ciphers().single().copy(deletedDate = Clock.System.now()))
            assertContains(syncRequests(engine), "PUT /api/ciphers/$remoteId/delete")
            val trashed = engine.ciphers().single()
            assertNull(trashed.service.error)
            assertNotNull(trashed.service.remote?.deletedDate)
            assertNotNull(assertNotNull(serverCipher(accessToken, remoteId)).stringOrNull("deletedDate"))

            // Like RemoveCipherById.
            waitPastServerWrite()
            engine.updateCipher(trashed.copy(service = trashed.service.copy(deleted = true)))
            // The bulk delete, with the ids in the body.
            assertContains(syncRequests(engine), "DELETE /api/ciphers/")
            assertNull(serverCipher(accessToken, remoteId))
            assertEquals(emptyList(), engine.ciphers())
        }
    }

    @Test
    fun `unchanged server only asks for the revision date`() = runE2eTest {
        val account = accounts.register()
        val accessToken = accounts.login(account).accessToken
        accounts.createLoginCipher(
            accessToken = accessToken,
            account = account,
            name = "Example",
            username = "alice",
            password = "hunter2",
        )
        accounts.createFolder(accessToken, account, name = "Work")
        val session = keyguardLogin(account)

        KeyguardSyncEngine.create(this, session).use { engine ->
            engine.sync()
            assertEquals(listOf("GET /api/accounts/revision-date"), syncRequests(engine))
        }
    }

    @Test
    fun `expired access token is refreshed before syncing`() = runE2eTest {
        val account = accounts.register()
        val session = keyguardLogin(account)

        KeyguardSyncEngine.create(this, session).use { engine ->
            val token = requireNotNull(session.token.token)
            val expired = token.copy(
                accessToken = "expired",
                expirationDate = Clock.System.now() - 1.hours,
            )
            engine.replaceToken(session.token.copy(token = expired))
            val requests = syncRequests(engine)

            val refresh = requests.indexOf("POST /identity/connect/token")
            assertTrue(refresh >= 0, "No token refresh in $requests")
            assertTrue(refresh < requests.indexOf("GET /api/sync"), "The refresh must come before the sync: $requests")
            val refreshed = assertNotNull(engine.storedToken().token)
            assertNotEquals("expired", refreshed.accessToken)
            // A JWT of the same user.
            assertEquals(accounts.userId(token.accessToken), accounts.userId(refreshed.accessToken))
            assertTrue(refreshed.expirationDate > Clock.System.now())
        }
    }
}

/** Runs one sync and returns the requests it sent. */
private suspend fun VaultwardenFixture.syncRequests(engine: KeyguardSyncEngine): List<String> {
    clearRecordedRequests()
    engine.sync()
    return recordedRequests.toList()
}

/**
 * The engine compares dates in deciseconds, so it takes an edit within ~100 ms of the
 * server's last write for no change. People do not edit that fast; the test waits instead.
 */
private suspend fun waitPastServerWrite() = delay(300.milliseconds)

/** A login that Keyguard created and has not uploaded yet, as `AddCipher` stores it. */
private fun VaultwardenFixture.newLoginCipher(
    accountId: String,
    name: String,
    username: String,
    password: String,
): BitwardenCipher {
    val now = Clock.System.now()
    return BitwardenCipher(
        accountId = accountId,
        cipherId = cryptoGenerator.uuid(),
        revisionDate = now,
        createdDate = now,
        service = BitwardenService(version = BitwardenService.VERSION),
        name = name,
        notes = null,
        favorite = false,
        reprompt = BitwardenCipher.RepromptType.None,
        type = BitwardenCipher.Type.Login,
        login = BitwardenCipher.Login(
            username = username,
            password = password,
            uris = emptyList(),
        ),
    )
}

/** The server's copy of a cipher, trashed or not; `null` once it is deleted. */
private suspend fun VaultwardenFixture.serverCipher(accessToken: String, id: String): JsonObject? = accounts
    .sync(accessToken)
    .getValue("ciphers")
    .jsonArray
    .map { it.jsonObject }
    .singleOrNull { it.string("id") == id }

private fun VaultwardenFixture.decrypt(account: TestAccount, cipherText: String): String = accounts
    .decrypt(account.userKey, cipherText)
    .decodeToString()
