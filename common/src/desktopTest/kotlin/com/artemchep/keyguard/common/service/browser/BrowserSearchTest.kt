package com.artemchep.keyguard.common.service.browser

import com.artemchep.keyguard.common.model.AddUriCipherRequest
import com.artemchep.keyguard.common.model.CliAgentUsageHistoryRequestType
import com.artemchep.keyguard.common.model.DGlobalUrlBlock
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.MatchDetection
import com.artemchep.keyguard.common.model.UrlBlockScope
import com.artemchep.keyguard.test.createProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** The `search` request, and the release of the items it finds, README section 5. */
@Suppress("TooManyFunctions")
class BrowserSearchTest {
    @Test
    fun `search needs a paired session and an unlocked vault`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        fixture.login("a", name = "GitHub")
        val unpaired = fixture.connect()
        unpaired.hello()
        assertEquals("unpaired", unpaired.call(search(2, "git")).string("error"))

        val client = paired(fixture)
        assertEquals(listOf("GitHub"), client.call(search(10, "git")).names())
        fixture.lock()
        // The `vault` event comes first.
        client.send(search(11, "git"))
        assertEquals("vault_locked", client.receiveResponse(11).string("error"))
    }

    @Test
    fun `search takes a query of 2 to 100 code points, once trimmed`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        val client = paired(fixture)
        // One code point, two chars.
        val key = "🔑"

        var id = 10
        for (query in listOf("ab", "  ab  ", key.repeat(2), "a".repeat(100), key.repeat(100))) {
            assertEquals(true, client.call(search(id++, query)).bool("ok"), query)
        }
        for (query in listOf("", "a", "  a  ", key, "a".repeat(101), key.repeat(101))) {
            assertEquals("invalid_request", client.call(search(id++, query)).string("error"), query)
        }
        // The page is checked as for `suggest`.
        assertEquals("invalid_request", client.call(search(id++, "ab", url = "example.com")).string("error"))
        val noQuery = """{"id":${id++},"type":"search","url":"$PAGE"}"""
        assertEquals("invalid_request", client.call(noQuery).string("error"))
    }

    @Test
    fun `search finds the logins by name, username and URI host, whatever the case`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        fixture.login("name", name = "My GitHub", uri = "https://code.example.org", totp = TOTP)
        fixture.login("user", username = "git-ann", uri = "https://code.example.org")
        fixture.login("host", uri = "https://GitLab.example.net/sign-in")
        // Saved without a scheme.
        fixture.login("bare", uri = "gitea.example.io")
        fixture.login("other", uri = "https://example.org")
        // An account the user hid, and an item in the trash.
        fixture.profiles.value += createProfile("hidden", hidden = true)
        fixture.login("hidden", name = "Git hidden", accountId = "hidden")
        val deleted = fixture.login("deleted", name = "Git deleted")
        val trashed = deleted.copy(deletedDate = Instant.fromEpochSeconds(1))
        fixture.ciphers.value = fixture.ciphers.value - deleted + trashed
        val client = paired(fixture)

        val response = client.call(search(10, "GIT"))
        assertEquals(false, response.bool("blocked"))
        assertEquals(false, response.bool("truncated"))
        assertEquals(setOf("My GitHub", "Login user", "Login host", "Login bare"), response.names().toSet())
        // What `suggest` lists, without passkeys.
        val item = response.items().single { it.string("name") == "My GitHub" }
        val fields = setOf("handle", "name", "username", "account", "has_password", "has_totp", "reprompt", "matched")
        assertEquals(fields, item.keys)
        assertEquals("alice", item.string("username"))
        assertEquals("Work", item.string("account"))
        assertEquals(true, item.bool("has_password"))
        assertEquals(true, item.bool("has_totp"))
        assertEquals(false, item.bool("reprompt"))
        assertEquals(false, item.bool("matched"))
        assertTrue(checkNotNull(item.string("handle")).length in 1..64)
        // Nothing reaches the request log.
        delay(100)
        assertTrue(fixture.recorded().none { it.request != CliAgentUsageHistoryRequestType.BROWSER_PAIR })
    }

    @Test
    fun `an item matches the page by its URIs only`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        // Its name is alike the page, which `suggest` counts.
        fixture.login("alike", name = "Example", uri = "https://shop.other.org")
        val client = paired(fixture)
        assertEquals(listOf("Example"), client.call(suggest(10)).names())
        assertEquals(false, client.call(search(11, "example")).items().single().bool("matched"))

        fixture.login("uri", name = "Example account", uri = "https://example.com")
        val items = client.call(search(12, "example")).items()
        assertEquals(
            mapOf<String?, Boolean?>("Example account" to true, "Example" to false),
            items.associate { it.string("name") to it.bool("matched") },
        )
    }

    @Test
    fun `on an http page, only a URI that names http matches`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        fixture.login("https", name = "Example https", uri = "https://example.com")
        fixture.login("http", name = "Example http", uri = "http://example.com")
        fixture.login("bare", name = "Example bare", uri = "example.com")
        val client = paired(fixture)

        val items = client.call(search(10, "example", "http://login.example.com/sign-in")).items()
        assertEquals(
            mapOf<String?, Boolean?>("Example http" to true, "Example https" to false, "Example bare" to false),
            items.associate { it.string("name") to it.bool("matched") },
        )
        // On the https page, every one of them matches.
        assertTrue(client.call(search(11, "example")).items().all { it.bool("matched") == true })
    }

    @Test
    fun `search lists matched items first, then names that start with the query, at most 20`() =
        browserTest { scope ->
            val fixture = BrowserFixture(scope)
            fixture.login("1", name = "Zeta git", uri = "https://example.com")
            fixture.login("2", name = "alpha git", uri = "https://www.example.com")
            fixture.login("3", name = "GitHub", uri = "https://github.com")
            fixture.login("4", name = "gitea", uri = "https://gitea.io")
            fixture.login("5", name = "My git", uri = "https://other.org")
            fixture.login("6", name = "Another git", uri = "https://other.org")
            val client = paired(fixture)

            val first = client.call(search(10, "git"))
            val order = listOf("alpha git", "Zeta git", "gitea", "GitHub", "Another git", "My git")
            assertEquals(order, first.names())
            assertEquals(listOf(true, true, false, false, false, false), first.items().map { it.bool("matched") })

            repeat(14) { fixture.login("more-$it", name = "Repo $it git", uri = "https://other.org") }
            val full = client.call(search(11, "git"))
            assertEquals(20, full.items().size)
            assertEquals(false, full.bool("truncated"))

            fixture.login("last", name = "Zulu git", uri = "https://other.org")
            val truncated = client.call(search(12, "git"))
            assertEquals(20, truncated.items().size)
            assertEquals(true, truncated.bool("truncated"))
            assertEquals(order, truncated.names().take(order.size))
        }

    @Test
    fun `search of a blocked page lists nothing`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        fixture.login("a", name = "GitHub", uri = "https://github.com")
        fixture.urlBlocks.value = listOf(
            DGlobalUrlBlock(
                name = "Example",
                description = "",
                uri = "https://example.com",
                mode = MatchDetection.Domain,
                createdDate = Instant.fromEpochSeconds(0),
                enabled = true,
                exposed = false,
            ),
        )
        val client = paired(fixture)

        val response = client.call(search(10, "git"))
        assertEquals(true, response.bool("blocked"))
        assertEquals(false, response.bool("truncated"))
        assertTrue(response.items().isEmpty())
        assertEquals(listOf("GitHub"), client.call(search(11, "git", url = "https://example.org/")).names())
    }

    @Test
    fun `a rule of saving only doesn't block search`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        fixture.login("a", name = "GitHub", uri = "https://github.com")
        fixture.urlBlocks.value = listOf(
            DGlobalUrlBlock(
                name = "Example",
                description = "",
                uri = "https://example.com",
                mode = MatchDetection.Domain,
                createdDate = Instant.fromEpochSeconds(0),
                enabled = true,
                exposed = false,
                scope = UrlBlockScope.SAVE,
            ),
        )
        val client = paired(fixture)

        val response = client.call(search(10, "git"))
        assertEquals(false, response.bool("blocked"))
        assertEquals(listOf("GitHub"), response.names())
        approve(fixture, client, fill(12, client.searchHandle(11, "git")))
    }

    @Test
    fun `an unmatched fill always asks, even with an approval of the item on the site`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        fixture.login("a", name = "GitHub", uri = "https://example.com")
        val client = paired(fixture)
        // The user approved the item on the site.
        val matched = client.suggestHandle(10)
        approve(fixture, client, fill(11, matched))
        assertEquals("secret-a", client.call(fill(12, matched)).string("password"))

        // Then it is no longer saved for the site.
        fixture.setUri("a", "https://github.com")
        val unmatched = client.searchHandle(13, "git")
        assertNotEquals(matched, unmatched)
        approve(fixture, client, fill(14, unmatched))
        approve(fixture, client, fill(15, unmatched))
    }

    @Test
    fun `a matched handle asks as unmatched once its item no longer matches the page`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        fixture.login("a", name = "GitHub", uri = "https://example.com")
        val client = paired(fixture)
        val handle = client.suggestHandle(10)
        approve(fixture, client, fill(11, handle))
        assertEquals("secret-a", client.call(fill(12, handle)).string("password"))

        // The user removed the site from the item.
        fixture.setUri("a", "https://github.com")
        client.send(fill(13, handle))
        val prompt = fixture.prompt()
        val release = assertIs<BrowserOperation.Fill>(prompt.operation).release
        assertEquals(false, release.matched)
        assertEquals("https://login.example.com", release.addUri)
        assertEquals(Duration.ZERO, release.approvalWindow)
        fixture.service.respondToApproval(prompt.id, approved = true)
        assertEquals("secret-a", client.receive()?.string("password"))

        // Saved for the site again: the approval of the item on the site covers it.
        fixture.setUri("a", "https://example.com")
        assertEquals("secret-a", client.call(fill(14, handle)).string("password"))
    }

    @Test
    fun `approving an unmatched fill is never remembered`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        fixture.login("a", name = "GitHub", uri = "https://github.com")
        val client = paired(fixture)
        approve(fixture, client, fill(11, client.searchHandle(10, "git")))

        // Once saved for the site, it asks.
        fixture.setUri("a", "https://example.com")
        approve(fixture, client, fill(13, client.suggestHandle(12)))
    }

    @Test
    fun `the prompt of an unmatched release warns and offers to add the page`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        fixture.login("a", name = "GitHub", uri = "https://github.com", totp = TOTP)
        val client = paired(fixture)
        val handle = client.searchHandle(10, "git")

        client.send(fill(11, handle))
        var prompt = fixture.prompt()
        val fill = assertIs<BrowserOperation.Fill>(prompt.operation).release
        assertEquals(false, fill.matched)
        assertEquals("https://example.com", fill.site)
        assertEquals("https://login.example.com", fill.addUri)
        // Keyguard asks every time.
        assertEquals(Duration.ZERO, fill.approvalWindow)
        fixture.service.respondToApproval(prompt.id, approved = false)
        assertEquals("user_denied", client.receive()?.string("error"))

        client.send(totp(12, handle))
        prompt = fixture.prompt()
        val totp = assertIs<BrowserOperation.Totp>(prompt.operation).release
        assertEquals(false, totp.matched)
        assertEquals("https://login.example.com", totp.addUri)
        assertEquals(Duration.ZERO, totp.approvalWindow)
        fixture.service.respondToApproval(prompt.id, approved = false)
        assertEquals("user_denied", client.receive()?.string("error"))

        // An item the user can't edit offers nothing to add.
        fixture.canWrite.value = false
        client.send(fill(13, handle))
        prompt = fixture.prompt()
        val readOnly = assertIs<BrowserOperation.Fill>(prompt.operation).release
        assertEquals(false, readOnly.matched)
        assertNull(readOnly.addUri)
        assertEquals(false, fixture.service.setAddUri(prompt.id, add = true))
        fixture.service.respondToApproval(prompt.id, approved = true)
        assertEquals("secret-a", client.receive()?.string("password"))
        assertTrue(fixture.addedUris.isEmpty())
    }

    @Test
    fun `approving with the box checked adds the page to the item`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        fixture.login("a", name = "GitHub", uri = "https://github.com", totp = TOTP)
        val client = paired(fixture)
        val handle = client.searchHandle(10, "git")

        client.send(fill(11, handle))
        var prompt = fixture.prompt()
        assertTrue(fixture.service.setAddUri(prompt.id, add = true))
        fixture.service.respondToApproval(prompt.id, approved = true)
        assertEquals("secret-a", client.receive()?.string("password"))
        val added = AddUriCipherRequest(cipherId = "a", webDomain = "login.example.com", webScheme = "https")
        assertEquals(listOf(added), fixture.addedUris)

        client.send(totp(12, handle))
        prompt = fixture.prompt()
        assertTrue(fixture.service.setAddUri(prompt.id, add = true))
        fixture.service.respondToApproval(prompt.id, approved = true)
        assertEquals(true, client.receive()?.bool("ok"))
        assertEquals(listOf(added, added), fixture.addedUris)
    }

    @Test
    fun `approving with the box unchecked adds nothing`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        fixture.login("a", name = "GitHub", uri = "https://github.com")
        val client = paired(fixture)
        val handle = client.searchHandle(10, "git")

        approve(fixture, client, fill(11, handle))
        // Checked, then unchecked.
        client.send(fill(12, handle))
        var prompt = fixture.prompt()
        assertTrue(fixture.service.setAddUri(prompt.id, add = true))
        assertTrue(fixture.service.setAddUri(prompt.id, add = false))
        fixture.service.respondToApproval(prompt.id, approved = true)
        assertEquals("secret-a", client.receive()?.string("password"))
        // Checked, then denied.
        client.send(fill(13, handle))
        prompt = fixture.prompt()
        assertTrue(fixture.service.setAddUri(prompt.id, add = true))
        fixture.service.respondToApproval(prompt.id, approved = false)
        assertEquals("user_denied", client.receive()?.string("error"))
        assertTrue(fixture.addedUris.isEmpty())
    }

    @Test
    fun `a failure to add the page still fills`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        fixture.login("a", name = "GitHub", uri = "https://github.com")
        fixture.addUriFails = true
        val client = paired(fixture)
        val handle = client.searchHandle(10, "git")

        client.send(fill(11, handle))
        val prompt = fixture.prompt()
        assertTrue(fixture.service.setAddUri(prompt.id, add = true))
        fixture.service.respondToApproval(prompt.id, approved = true)
        assertEquals("secret-a", client.receive()?.string("password"))
        assertEquals(1, fixture.addedUris.size)
        assertTrue(fixture.log.messages.any { it.startsWith("Failed to add the page") })
    }

    @Test
    fun `an unmatched item with master password re-prompt needs the user's identity`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        fixture.login("a", name = "GitHub", uri = "https://github.com", reprompt = true)
        val client = paired(fixture)
        val handle = client.searchHandle(10, "git")

        client.send(fill(11, handle))
        var prompt = fixture.prompt()
        val release = assertIs<BrowserOperation.Fill>(prompt.operation).release
        assertEquals(true, release.reprompt)
        assertEquals(false, release.matched)
        assertEquals(false, fixture.service.confirmIdentity(prompt.id) { false })
        fixture.service.respondToApproval(prompt.id, approved = true)
        assertEquals("user_denied", client.receive()?.string("error"))

        client.send(fill(12, handle))
        prompt = fixture.prompt()
        assertTrue(fixture.service.confirmIdentity(prompt.id) { true })
        fixture.service.respondToApproval(prompt.id, approved = true)
        assertEquals("secret-a", client.receive()?.string("password"))
    }

    @Test
    fun `a matched item keeps the handle of suggest and its approval window`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        fixture.login("a", name = "Example", uri = "https://example.com")
        val client = paired(fixture)
        val handle = client.suggestHandle(10)
        val found = client.call(search(11, "example")).items().single()
        assertEquals(true, found.bool("matched"))
        assertEquals(handle, found.string("handle"))

        client.send(fill(12, handle))
        val prompt = fixture.prompt()
        val release = assertIs<BrowserOperation.Fill>(prompt.operation).release
        assertEquals(true, release.matched)
        assertNull(release.addUri)
        assertEquals(1.hours, release.approvalWindow)
        assertEquals(false, fixture.service.setAddUri(prompt.id, add = true))
        fixture.service.respondToApproval(prompt.id, approved = true)
        assertEquals("secret-a", client.receive()?.string("password"))
        // Remembered.
        assertEquals("secret-a", client.call(fill(13, handle)).string("password"))
        assertTrue(fixture.addedUris.isEmpty())
    }
}

private const val PAGE = "https://login.example.com/sign-in"
private const val TOTP = "otpauth://totp/Example:alice?secret=JBSWY3DPEHPK3PXP&issuer=Example"

private fun search(id: Int, query: String, url: String = PAGE) =
    """{"id":$id,"type":"search","query":"$query","url":"$url"}"""

private fun suggest(id: Int) = """{"id":$id,"type":"suggest","url":"$PAGE"}"""

private fun fill(id: Int, handle: String) = """{"id":$id,"type":"fill","handle":"$handle","url":"$PAGE"}"""

private fun totp(id: Int, handle: String) = """{"id":$id,"type":"totp","handle":"$handle","url":"$PAGE"}"""

private fun JsonObject.items(): List<JsonObject> = (getValue("items") as JsonArray).map { it.jsonObject }

private fun JsonObject.names(): List<String?> = items().map { it.string("name") }

/** The handle of the only login that `suggest` lists for the page. */
private suspend fun BrowserClient.suggestHandle(id: Int): String =
    checkNotNull(call(suggest(id)).items().single().string("handle"))

/** The handle of the only login that `search` finds for [query]. */
private suspend fun BrowserClient.searchHandle(id: Int, query: String): String =
    checkNotNull(call(search(id, query)).items().single().string("handle"))

/** The prompt on screen, once there is one. */
private suspend fun BrowserFixture.prompt(): BrowserApprovalRequest = withTimeout(TIMEOUT) {
    service.approvals.first { it.isNotEmpty() }.single()
}

/** Sends [request], which must prompt; approves the prompt and returns the response. */
private suspend fun approve(
    fixture: BrowserFixture,
    client: BrowserClient,
    request: String,
): JsonObject {
    client.send(request)
    fixture.service.respondToApproval(fixture.prompt().id, approved = true)
    val response = checkNotNull(client.receive())
    assertEquals(true, response.bool("ok"), response.toString())
    return response
}

/** Saves the item [id] for [uri] alone. */
private fun BrowserFixture.setUri(id: String, uri: String) {
    ciphers.value = ciphers.value.map { if (it.id == id) it.copy(uris = listOf(DSecret.Uri(uri = uri))) else it }
}
