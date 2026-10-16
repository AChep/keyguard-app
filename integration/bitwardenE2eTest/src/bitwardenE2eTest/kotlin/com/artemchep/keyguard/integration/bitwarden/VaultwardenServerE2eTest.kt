package com.artemchep.keyguard.integration.bitwarden

import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails

/** Proves the harness: the server, its settings, the account seeding and its cryptography. */
class VaultwardenServerE2eTest {
    @Test
    fun `alive answers and web vault is disabled`() = runE2eTest {
        assertEquals(HttpStatusCode.OK, httpClient.get("${server.baseUrl}/alive").status)
        assertEquals(HttpStatusCode.NotFound, httpClient.get("${server.baseUrl}/").status)
    }

    @Test
    fun `register login and sync round trip`() = runE2eTest {
        val account = accounts.register()
        val login = accounts.login(account)

        val profile = accounts.sync(login.accessToken).getValue("profile").jsonObject
        assertEquals(account.email, profile.string("email"))
        val userKey = accounts.decrypt(accounts.stretch(account.masterKey), profile.string("key"))
        assertContentEquals(account.userKey, userKey)
        val privateKey = accounts.decrypt(account.userKey, profile.string("privateKey"))
        assertContentEquals(account.privateKey, privateKey)
        assertEquals(profile.string("key"), login.key)
    }

    @Test
    fun `seeded login cipher appears in sync`() = runE2eTest {
        val account = accounts.register()
        val accessToken = accounts.login(account).accessToken

        val id = accounts.createLoginCipher(
            accessToken = accessToken,
            account = account,
            name = "Example",
            username = "alice",
            password = "hunter2",
        )

        val cipher = accounts.sync(accessToken)
            .getValue("ciphers")
            .jsonArray
            .map { it.jsonObject }
            .single { it.string("id") == id }
        val name = accounts.decrypt(account.userKey, cipher.string("name"))
        assertEquals("Example", name.decodeToString())
    }

    @Test
    fun `deleted account can not log in`() = runE2eTest {
        val account = accounts.register()
        accounts.login(account)

        accounts.deleteUser(account.email)

        assertFails {
            accounts.login(account)
        }
    }

    @Test
    fun `fixture client records its requests`() = runE2eTest {
        clearRecordedRequests()
        httpClient.get("${server.baseUrl}/alive?probe=1")
        assertEquals(listOf("GET /alive"), recordedRequests.toList())
    }

    @Test
    fun `argon2id account logs in with keyguard`() = runE2eTest {
        val account = accounts.register(
            kdf = TestKdf.Argon2id(iterations = 2, memoryMib = 16, parallelism = 1),
        )

        val session = keyguardLogin(account)
        assertContentEquals(account.masterKey, session.secrets.masterKey)
        val sync = keyguardSync(session)
        val userKey = accounts.decrypt(session.secrets.encryptionKey + session.secrets.macKey, sync.profile.key)
        assertContentEquals(account.userKey, userKey)
    }

    @Test
    fun `overrides replace the env file settings`() = VaultwardenFixture
        .start(overrides = mapOf("SIGNUPS_ALLOWED" to "false"))
        .use { closedServer ->
            closedServer.runE2eTest {
                val failure = assertFails { accounts.register() }
                assertContains(failure.message.orEmpty(), "Registration not allowed")
            }
        }
}
