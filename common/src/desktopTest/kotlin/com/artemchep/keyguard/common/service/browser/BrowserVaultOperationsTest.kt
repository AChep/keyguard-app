@file:Suppress("TooManyFunctions") // The requests and the checks of the responses.

package com.artemchep.keyguard.common.service.browser

import com.artemchep.keyguard.common.model.AddCipherOpenedHistoryRequest
import com.artemchep.keyguard.common.model.AddCipherUsedPasskeyHistoryRequest
import com.artemchep.keyguard.common.model.AddCliAgentUsageHistoryRequest
import com.artemchep.keyguard.common.model.CliAgentUsageHistoryClient
import com.artemchep.keyguard.common.model.CliAgentUsageHistoryRequestType
import com.artemchep.keyguard.common.model.CliAgentUsageHistoryResponseType
import com.artemchep.keyguard.common.model.DCollection
import com.artemchep.keyguard.common.model.DEquivalentDomains
import com.artemchep.keyguard.common.model.DGlobalUrlBlock
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.MatchDetection
import com.artemchep.keyguard.common.model.PasswordGeneratorConfig
import com.artemchep.keyguard.common.model.UrlBlockScope
import com.artemchep.keyguard.common.service.agent.AGENT_APPROVAL_WINDOW_NEVER_ASK
import com.artemchep.keyguard.feature.browserextension.protocol.BrowserVaultState
import com.artemchep.keyguard.test.createProfile
import com.artemchep.keyguard.test.createSecret
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** The requests of a paired extension that reach the vault, README section 5. */
@Suppress("LargeClass", "TooManyFunctions")
class BrowserVaultOperationsTest {
    @Test
    fun `suggest lists the logins of the page's site with their handles`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        fixture.login("a", uri = "https://example.com", totp = TOTP)
        fixture.login("b", uri = "https://other.org")
        // An account the user hid, an item in the trash, and an item of another type.
        fixture.profiles.value += createProfile("hidden", hidden = true)
        fixture.login("c", accountId = "hidden")
        val deleted = fixture.login("d")
        val trashed = deleted.copy(deletedDate = Instant.fromEpochSeconds(1))
        fixture.ciphers.value = fixture.ciphers.value - deleted + trashed
        fixture.ciphers.value += createSecret(
            id = "e",
            name = "Example card",
            accountId = ACCOUNT_ID,
            type = DSecret.Type.Card,
            uris = listOf(DSecret.Uri(uri = "https://example.com")),
        )
        val client = paired(fixture)

        val response = client.call(suggest(10, "https://login.example.com/sign-in?next=/"))
        assertEquals(false, response.bool("blocked"))
        assertEquals(false, response.bool("save_blocked"))
        assertEquals(false, response.bool("truncated"))
        val item = response.items().single()
        assertEquals("Login a", item.string("name"))
        assertEquals("alice", item.string("username"))
        assertEquals("Work", item.string("account"))
        assertEquals(true, item.bool("has_password"))
        assertEquals(true, item.bool("has_totp"))
        assertEquals(false, item.bool("reprompt"))
        assertNull(item["passkeys"])
        val handle = checkNotNull(item.string("handle"))
        assertTrue(handle.length in 1..64)
        // The item keeps its handle on the site for the session.
        val again = client.call(suggest(11, "https://www.example.com/"))
        assertEquals(handle, again.items().single().string("handle"))
        // Nothing reaches the request log.
        delay(100)
        assertTrue(fixture.recorded().none { it.request != CliAgentUsageHistoryRequestType.BROWSER_PAIR })
    }

    @Test
    fun `suggest of a blocked page lists nothing`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        fixture.login("a")
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

        val response = client.call(suggest(10, "https://login.example.com/"))
        assertEquals(true, response.bool("blocked"))
        assertEquals(true, response.bool("save_blocked"))
        assertTrue(response.items().isEmpty())
        // Saving is refused there as well.
        assertEquals("not_allowed", client.call(save(11, "https://example.com", "bob")).string("error"))
    }

    @Test
    fun `a rule of saving only fills, and refuses to save`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        fixture.login("a")
        fixture.urlBlocks.value = listOf(urlBlock(scope = UrlBlockScope.SAVE))
        val client = paired(fixture)

        val response = client.call(suggest(10, "https://login.example.com/"))
        assertEquals(false, response.bool("blocked"))
        assertEquals(true, response.bool("save_blocked"))
        val handle = checkNotNull(response.items().single().string("handle"))
        assertEquals("secret-a", approve(fixture, client, fill(11, handle, PAGE)).string("password"))
        assertEquals("not_allowed", client.call(save(12, PAGE, "bob")).string("error"))
        assertTrue(fixture.service.saves.value.isEmpty())

        // A rule that is off blocks nothing.
        fixture.urlBlocks.value = listOf(urlBlock(scope = UrlBlockScope.SAVE, enabled = false))
        assertEquals(false, client.call(suggest(13, PAGE)).bool("save_blocked"))
    }

    @Test
    fun `suggest of no kinds lists nothing, and tells only the blocks of the page`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        fixture.login("a")
        fixture.used("a", seconds = 1)
        val client = paired(fixture)

        // The save bar's check: whether saving is blocked there, without the logins.
        for ((id, block) in listOf(10 to null, 11 to UrlBlockScope.SAVE, 12 to UrlBlockScope.ALL)) {
            fixture.urlBlocks.value = listOfNotNull(block?.let { urlBlock(scope = it) })
            val response = client.call(suggestNoKinds(id, PAGE))
            assertEquals(block == UrlBlockScope.ALL, response.bool("blocked"), "$block")
            assertEquals(block != null, response.bool("save_blocked"), "$block")
            assertEquals(false, response.bool("truncated"), "$block")
            assertTrue(response.items().isEmpty(), "$block")
        }
        // The logins are still there.
        fixture.urlBlocks.value = emptyList()
        assertEquals(listOf("Login a"), client.call(suggest(13, PAGE)).items().names())
    }

    @Test
    fun `never_save adds a rule of saving only for the site`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        fixture.login("a")
        val client = paired(fixture)

        val before = Clock.System.now()
        val response = client.call(neverSave(10, "https://login.example.com/sign-in?next=/"))
        assertEquals(setOf("id", "ok"), response.keys)
        assertEquals(true, response.bool("ok"))
        val rule = fixture.urlBlocks.value.single()
        assertEquals("login.example.com", rule.name)
        assertEquals("", rule.description)
        assertEquals("https://login.example.com", rule.uri)
        assertEquals(MatchDetection.Domain, rule.mode)
        assertEquals(true, rule.enabled)
        assertEquals(true, rule.exposed)
        assertEquals(UrlBlockScope.SAVE, rule.scope)
        assertTrue(rule.createdDate in before..Clock.System.now())

        // The whole site: it still fills, and never offers to save.
        val suggested = client.call(suggest(11, "https://www.example.com/"))
        assertEquals(true, suggested.bool("save_blocked"))
        assertEquals(1, suggested.items().size)
        // Saving is blocked there already: nothing more to add.
        assertEquals(true, client.call(neverSave(12, PAGE)).bool("ok"))
        assertEquals(listOf(rule), fixture.urlBlocks.value)

        val log = fixture.awaitRecorded(CliAgentUsageHistoryRequestType.BROWSER_NEVER_SAVE, count = 2)
        assertEquals(17L, log.first().request.code)
        log.forEach { entry ->
            assertEquals(CliAgentUsageHistoryResponseType.SUCCESS, entry.response)
            assertEquals("Google Chrome", entry.clientName)
        }
        assertEquals(setOf("https://login.example.com", "https://example.com"), log.map { it.path }.toSet())
    }

    @Test
    fun `never_save adds nothing where a rule of autofill and saving blocks the page`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        fixture.urlBlocks.value = listOf(urlBlock(scope = UrlBlockScope.ALL))
        val client = paired(fixture)

        assertEquals(true, client.call(neverSave(10, PAGE)).bool("ok"))
        assertEquals(UrlBlockScope.ALL, fixture.urlBlocks.value.single().scope)
    }

    @Test
    fun `never_save checks its fields, the capability and the vault`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        val client = paired(fixture)

        assertEquals("invalid_request", client.call(neverSave(10, "example.com")).string("error"))
        assertEquals("invalid_request", client.call(neverSave(11, "file:///etc/passwd")).string("error"))
        assertEquals("invalid_request", client.call("""{"id":12,"type":"never_save"}""").string("error"))
        // A rule the app can't store.
        fixture.addUrlBlockFails = true
        assertEquals("not_allowed", client.call(neverSave(13, PAGE)).string("error"))
        fixture.addUrlBlockFails = false

        fixture.lock()
        assertEquals("vault_locked", client.callSkippingEvents(neverSave(14, PAGE)).string("error"))
        assertTrue(fixture.urlBlocks.value.isEmpty())

        val other = fixture.connect()
        other.call("""{"id":1,"type":"hello","protocol":1,"capabilities":[]}""")
        assertEquals("not_supported", other.call(neverSave(2, PAGE)).string("error"))
    }

    @Test
    fun `suggest returns at most 50 items`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        repeat(51) { fixture.login("$it") }
        val client = paired(fixture)

        val response = client.call(suggest(10, PAGE))
        assertEquals(true, response.bool("truncated"))
        assertEquals(50, response.items().size)
    }

    @Test
    fun `suggest lists the logins used on this device first, the one used last marked`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        listOf("a", "b", "c", "d").forEach { fixture.login(it) }
        val client = paired(fixture)
        val matchOrder = client.call(suggest(10, PAGE)).items().names()

        fixture.used("c", seconds = 1)
        fixture.used("b", seconds = 2)
        fixture.used("c", seconds = 3)
        var items = client.call(suggest(11, PAGE)).items()
        var used = listOf("Login c", "Login b")
        // The logins never used keep the order of the match.
        assertEquals(used + matchOrder.filter { it !in used }, items.names())
        assertEquals(true, items.first().bool("last_used"))
        assertTrue(items.drop(1).all { it["last_used"] == null })

        // A fill is a use.
        val handle = checkNotNull(items.single { it.string("name") == "Login d" }.string("handle"))
        approve(fixture, client, fill(12, handle, PAGE))
        items = client.call(suggest(13, PAGE)).items()
        used = listOf("Login d") + used
        assertEquals(used + matchOrder.filter { it !in used }, items.names())
        assertEquals(listOf(true, null, null, null), items.map { it.bool("last_used") })
    }

    @Test
    fun `suggest keeps the order of the match and marks nothing without a use of a match`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        listOf("a", "b", "c").forEach { fixture.login(it) }
        fixture.login("x", uri = "https://other.org")
        val client = paired(fixture)
        val matched = client.call(suggest(10, PAGE)).items()
        assertEquals(3, matched.size)
        assertTrue(matched.all { it["last_used"] == null })

        // A use of a login of another site.
        fixture.used("x", seconds = 1)
        val other = client.call(suggest(11, PAGE)).items()
        assertEquals(matched.names(), other.names())
        assertTrue(other.all { it["last_used"] == null })

        // A history the app can't read.
        fixture.used("c", seconds = 2)
        fixture.lastUsedFails = true
        val unread = client.call(suggest(12, PAGE)).items()
        assertEquals(matched.names(), unread.names())
        assertTrue(unread.all { it["last_used"] == null })
        assertTrue(fixture.log.messages.any { it.startsWith("Failed to read the last use") })
    }

    @Test
    fun `suggest counts a passkey sign-in as a use`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        val passkeys = mapOf(
            "a" to fixture.passkeyLogin("a"),
            // A login of another site with a passkey of the page.
            "b" to fixture.passkeyLogin("b", uri = "https://other.org", userName = "bob"),
        )
        val client = paired(fixture)
        val matchOrder = client.call(suggestPasskeys(10)).items().names()
        assertEquals(setOf("Login a", "Login b"), matchOrder.toSet())

        // The one listed last signs in with its passkey.
        val id = fixture.ciphers.value.single { it.name == matchOrder.last() }.id
        synchronized(fixture.passkeyHistory) {
            fixture.passkeyHistory += AddCipherUsedPasskeyHistoryRequest(
                accountId = ACCOUNT_ID,
                cipherId = id,
                credentialId = passkeys.getValue(id).credential.credentialId,
                instant = Instant.fromEpochSeconds(1),
            )
        }
        val items = client.call(suggestPasskeys(11)).items()
        assertEquals(matchOrder.reversed(), items.names())
        assertEquals(listOf(true, null), items.map { it.bool("last_used") })
    }

    @Test
    fun `a login listed only for its passkey gets a handle that isn't matched`() = browserTest { scope ->
        val fixture = BrowserFixture(scope, approvalWindow = AGENT_APPROVAL_WINDOW_NEVER_ASK)
        fixture.login("a")
        // A login of another site, with a passkey of a relying party that lists the page among its related origins.
        fixture.passkeyLogin("b", rpId = "other.org", userName = "bob")
        fixture.wellKnown["other.org"] = """{"origins":["https://example.com"]}"""
        val client = paired(fixture)
        val request = """{"id":10,"type":"suggest","url":"$PAGE","kinds":["login","passkey"],"rp_id":"other.org"}"""
        val items = client.call(request).items().associateBy { it.string("name") }
        assertEquals(setOf("Login a", "Login b"), items.keys)
        assertEquals(1, (items.getValue("Login b").getValue("passkeys") as JsonArray).size)

        // The login of the page fills without a prompt.
        val login = checkNotNull(items.getValue("Login a").string("handle"))
        assertEquals("secret-a", client.call(fill(11, login, PAGE)).string("password"))
        // The other one always asks, and offers to add the page.
        client.send(fill(12, checkNotNull(items.getValue("Login b").string("handle")), PAGE))
        val prompt = fixture.service.approvals.first { it.isNotEmpty() }.single()
        val release = assertIs<BrowserOperation.Fill>(prompt.operation).release
        assertEquals(false, release.matched)
        assertEquals("https://example.com", release.addUri)
        fixture.service.respondToApproval(prompt.id, approved = false)
        assertEquals("user_denied", client.receive()?.string("error"))
    }

    @Test
    fun `a blocked URI covers the equivalent domains of every account`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        // A group of an account without logins: a rule belongs to no account.
        fixture.equivalentDomains.value = listOf(
            DEquivalentDomains(
                id = "group",
                accountId = "other",
                global = false,
                excluded = false,
                domains = listOf("example.com", "example.net"),
            ),
        )
        fixture.login("a", uri = "https://example.net")
        fixture.urlBlocks.value = listOf(urlBlock(scope = UrlBlockScope.SAVE))
        val client = paired(fixture)

        val saveOnly = client.call(suggest(10, "https://example.net/"))
        assertEquals(false, saveOnly.bool("blocked"))
        assertEquals(true, saveOnly.bool("save_blocked"))
        assertEquals("not_allowed", client.call(save(11, "https://example.net/", "bob")).string("error"))
        // A site of no group isn't covered.
        assertEquals(false, client.call(suggest(12, "https://example.org/")).bool("save_blocked"))

        fixture.urlBlocks.value = listOf(urlBlock(scope = UrlBlockScope.ALL))
        val blocked = client.call(suggest(13, "https://example.net/"))
        assertEquals(true, blocked.bool("blocked"))
        assertTrue(blocked.items().isEmpty())
    }

    @Test
    fun `on an http page, only a login saved for http fills without a prompt`() = browserTest { scope ->
        val fixture = BrowserFixture(scope, approvalWindow = AGENT_APPROVAL_WINDOW_NEVER_ASK)
        fixture.login("a", uri = "https://example.com")
        fixture.login("b", uri = "http://example.com")
        // A URI without a scheme doesn't name `http://` either.
        fixture.login("c", uri = "example.com")
        val client = paired(fixture)
        val items = client.call(suggest(10, HTTP_PAGE)).items().associateBy { it.string("name") }
        assertEquals(setOf("Login a", "Login b", "Login c"), items.keys)
        fun handleOf(name: String) = checkNotNull(items.getValue(name).string("handle"))

        assertEquals("secret-b", client.call(fill(11, handleOf("Login b"), HTTP_PAGE)).string("password"))
        // The others always ask, warn, and offer to add the http page.
        assertUnmatchedFill(fixture, client, fill(12, handleOf("Login a"), HTTP_PAGE))
        assertUnmatchedFill(fixture, client, fill(13, handleOf("Login c"), HTTP_PAGE))
        // A matched handle asks once its login names `https://` only.
        fixture.ciphers.value = fixture.ciphers.value.map { cipher ->
            if (cipher.id == "b") cipher.copy(uris = listOf(DSecret.Uri(uri = "https://example.com"))) else cipher
        }
        assertUnmatchedFill(fixture, client, fill(14, handleOf("Login b"), HTTP_PAGE))
    }

    @Test
    fun `a host with a trailing dot or in capitals is the same host`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        fixture.login("a", uri = "https://bank.com")
        val client = paired(fixture)
        assertEquals(listOf("Login a"), client.call(suggest(10, "https://Bank.com./")).items().names())

        // A rule of the host blocks every spelling of it.
        fixture.urlBlocks.value = listOf(
            urlBlock(scope = UrlBlockScope.ALL).copy(uri = "https://bank.com", mode = MatchDetection.Host),
        )
        for ((id, url) in listOf(11 to "https://bank.com./", 12 to "https://BANK.com/sign-in")) {
            assertEquals(true, client.call(suggest(id, url)).bool("blocked"), url)
        }
    }

    @Test
    fun `an item whose collections hide passwords says so, and still fills`() = browserTest { scope ->
        val fixture = BrowserFixture(scope, approvalWindow = AGENT_APPROVAL_WINDOW_NEVER_ASK)
        fixture.collections.value = listOf(
            collection("hidden", hidePasswords = true),
            collection("shown", hidePasswords = false),
            // Of another account: it doesn't apply.
            collection("shown", hidePasswords = true, accountId = "other"),
        )
        fixture.login("a", name = "Hidden")
        fixture.login("b", name = "Mixed")
        fixture.login("c", name = "Plain")
        fixture.ciphers.value = fixture.ciphers.value.map { cipher ->
            when (cipher.id) {
                "a" -> cipher.copy(collectionIds = setOf("hidden"))
                "b" -> cipher.copy(collectionIds = setOf("hidden", "shown"))
                else -> cipher
            }
        }
        val client = paired(fixture)

        val items = client.call(suggest(10, PAGE)).items().associateBy { it.string("name") }
        assertEquals(
            mapOf<String?, Boolean?>("Hidden" to true, "Mixed" to null, "Plain" to null),
            items.mapValues { (_, item) -> item.bool("password_hidden") },
        )
        val search = """{"id":11,"type":"search","query":"hidden","url":"$PAGE"}"""
        assertEquals(true, client.call(search).items().single().bool("password_hidden"))
        // A fill still releases the password.
        val handle = checkNotNull(items.getValue("Hidden").string("handle"))
        assertEquals("secret-a", client.call(fill(12, handle, PAGE)).string("password"))
    }

    @Test
    fun `suggest keeps the login used last when more than 50 match`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        repeat(51) { fixture.login("$it") }
        val client = paired(fixture)
        val listed = client.call(suggest(10, PAGE)).items().names()
        val cut = fixture.ciphers.value.single { it.name !in listed }

        fixture.used(cut.id, seconds = 1)
        val response = client.call(suggest(11, PAGE))
        assertEquals(true, response.bool("truncated"))
        val items = response.items()
        assertEquals(50, items.size)
        assertEquals(cut.name, items.first().string("name"))
        assertEquals(true, items.first().bool("last_used"))
    }

    @Test
    fun `suggest checks its fields and the vault`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        fixture.login("a")
        val client = paired(fixture)

        assertEquals("invalid_request", client.call(suggest(10, "example.com")).string("error"))
        assertEquals("invalid_request", client.call(suggest(11, "file:///etc/passwd")).string("error"))
        val passkeys = """{"id":12,"type":"suggest","url":"$PAGE","kinds":["passkey"]}"""
        assertEquals("invalid_request", client.call(passkeys).string("error"))
        // No login of the page has a passkey.
        val withRpId = """{"id":13,"type":"suggest","url":"$PAGE","kinds":["passkey"],"rp_id":"example.com"}"""
        assertTrue(client.call(withRpId).items().isEmpty())

        fixture.lock()
        assertEquals("vault_locked", client.callSkippingEvents(suggest(14, PAGE)).string("error"))
    }

    @Test
    fun `fill asks once, then the approval window covers the item on the site`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        fixture.login("a")
        val client = paired(fixture)
        val handle = client.handle(10, "https://login.example.com/")

        client.send(fill(11, handle, "https://login.example.com/"))
        val prompt = fixture.service.approvals.first { it.isNotEmpty() }.single()
        val operation = assertIs<BrowserOperation.Fill>(prompt.operation)
        assertEquals("https://login.example.com", operation.release.origin)
        assertEquals("https://example.com", operation.release.site)
        assertEquals("Login a", operation.release.name)
        assertEquals("alice", operation.release.username)
        assertEquals("Work", operation.release.accountName)
        assertEquals(false, operation.release.reprompt)
        assertEquals(1.hours, operation.release.approvalWindow)
        assertEquals("Google Chrome", prompt.pairingName)
        fixture.service.respondToApproval(prompt.id, approved = true)
        val first = checkNotNull(client.receive())
        assertEquals("alice", first.string("username"))
        assertEquals("secret-a", first.string("password"))
        assertEquals(listOf("a"), fixture.autofillHistory.map { it.cipherId })

        // Another page of the same site.
        val second = client.call(fill(12, handle, "https://www.example.com/account"))
        assertEquals("secret-a", second.string("password"))
        assertTrue(fixture.service.approvals.value.isEmpty())
    }

    @Test
    fun `fill and totp name the top-level site of a frame of another site in the prompt`() = browserTest { scope ->
        val fixture = BrowserFixture(scope, approvalWindow = Duration.ZERO)
        fixture.login("a", totp = TOTP)
        val client = paired(fixture)
        val handle = client.handle(10, PAGE)

        client.send(fill(11, handle, PAGE, topOrigin = "https://shop.example.net"))
        var prompt = fixture.service.approvals.first { it.isNotEmpty() }.single()
        val fill = assertIs<BrowserOperation.Fill>(prompt.operation).release
        assertEquals("https://example.com", fill.site)
        assertEquals("https://example.net", fill.topSite)
        fixture.service.respondToApproval(prompt.id, approved = true)
        assertEquals("secret-a", client.receive()?.string("password"))

        client.send(totp(12, handle, topOrigin = "http://example.com"))
        prompt = fixture.service.approvals.first { it.isNotEmpty() }.single()
        // Another scheme is another site.
        assertEquals("http://example.com", assertIs<BrowserOperation.Totp>(prompt.operation).release.topSite)
        fixture.service.respondToApproval(prompt.id, approved = true)
        assertEquals(true, client.receive()?.bool("ok"))

        // A page of the same site names none.
        val sameSite = listOf(
            13 to fill(13, handle, PAGE),
            14 to fill(14, handle, PAGE, "https://www.example.com"),
        )
        for ((id, request) in sameSite) {
            client.send(request)
            prompt = fixture.service.approvals.first { it.isNotEmpty() }.single()
            assertNull(assertIs<BrowserOperation.Fill>(prompt.operation).release.topSite, "$id")
            fixture.service.respondToApproval(prompt.id, approved = true)
            assertEquals("secret-a", client.receive()?.string("password"))
        }
    }

    @Test
    fun `a frame of another site always asks, even with never ask`() = browserTest { scope ->
        val fixture = BrowserFixture(scope, approvalWindow = AGENT_APPROVAL_WINDOW_NEVER_ASK)
        fixture.login("a", totp = TOTP)
        val client = paired(fixture)
        val handle = client.handle(10, PAGE)
        assertEquals("secret-a", client.call(fill(11, handle, PAGE)).string("password"))

        // Approving one doesn't cover the next.
        val framed = listOf(
            fill(12, handle, PAGE, topOrigin = SHOP_ORIGIN),
            fill(13, handle, PAGE, topOrigin = SHOP_ORIGIN),
            totp(14, handle, topOrigin = SHOP_ORIGIN),
        )
        for (request in framed) {
            client.send(request)
            val prompt = fixture.service.approvals.first { it.isNotEmpty() }.single()
            val release = assertIs<BrowserOperation.Release>(prompt.operation).release
            assertEquals("https://example.com", release.site, request)
            assertEquals("https://example.net", release.topSite, request)
            // The prompt says that it asks every time.
            assertEquals(Duration.ZERO, release.approvalWindow, request)
            fixture.service.respondToApproval(prompt.id, approved = true)
            assertEquals(true, client.receive()?.bool("ok"), request)
        }
    }

    @Test
    fun `a frame of the same site follows the approval window`() = browserTest { scope ->
        val fixture = BrowserFixture(scope, approvalWindow = AGENT_APPROVAL_WINDOW_NEVER_ASK)
        fixture.login("a")
        val client = paired(fixture)
        val handle = client.handle(10, LOGIN_PAGE)

        val framed = client.call(fill(11, handle, LOGIN_PAGE, topOrigin = "https://example.com"))
        assertEquals("secret-a", framed.string("password"))
        assertTrue(fixture.service.approvals.value.isEmpty())
    }

    @Test
    fun `an approval covers a frame of the same site, never one of another site`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        fixture.login("a")
        val client = paired(fixture)
        val handle = client.handle(10, PAGE)

        // Approving a frame of another site remembers nothing.
        approve(fixture, client, fill(11, handle, PAGE, topOrigin = SHOP_ORIGIN))
        approve(fixture, client, fill(12, handle, PAGE))
        val sameSite = client.call(fill(13, handle, LOGIN_PAGE, topOrigin = "https://www.example.com"))
        assertEquals("secret-a", sameSite.string("password"))
        assertTrue(fixture.service.approvals.value.isEmpty())
        // The approval of the site doesn't cover a frame of another site.
        approve(fixture, client, fill(14, handle, PAGE, topOrigin = SHOP_ORIGIN))
    }

    @Test
    fun `fill and totp take only an http or https origin as the top origin`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        fixture.login("a", totp = TOTP)
        val client = paired(fixture)
        val handle = client.handle(10, PAGE)

        val invalid = listOf(
            "",
            "null",
            "example.net",
            "https://example.net/",
            "https://example.net/cart",
            "https://example.net?next=1",
            "https://user@example.net",
            "file:///etc/passwd",
            "chrome-extension://abcdefghijklmnopabcdefghijklmnop",
        )
        var id = 11
        for (origin in invalid) {
            assertEquals("invalid_request", client.call(fill(id++, handle, PAGE, origin)).string("error"), origin)
            assertEquals("invalid_request", client.call(totp(id++, handle, topOrigin = origin)).string("error"), origin)
        }
        val mistyped = """{"id":${id++},"type":"fill","handle":"$handle","url":"$PAGE","top_origin":7}"""
        assertEquals("invalid_request", client.call(mistyped).string("error"))
        assertTrue(fixture.service.approvals.value.isEmpty())
    }

    @Test
    fun `a handle on another site is not allowed`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        fixture.login("a")
        val client = paired(fixture)
        val handle = client.handle(10, PAGE)

        assertEquals("not_allowed", client.call(fill(11, handle, "https://example.org/")).string("error"))
        // Another scheme is another site.
        assertEquals("not_allowed", client.call(fill(12, handle, "http://example.com/")).string("error"))
        assertEquals("invalid_request", client.call(fill(13, "unknown", PAGE)).string("error"))
        assertTrue(fixture.service.approvals.value.isEmpty())
    }

    @Test
    fun `another site of the same item asks again`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        fixture.login("a").also { login ->
            fixture.ciphers.value = listOf(
                login.copy(
                    uris = login.uris + DSecret.Uri(uri = "https://example.org"),
                ),
            )
        }
        val client = paired(fixture)
        val com = client.handle(10, PAGE)
        val org = client.handle(11, "https://example.org/")

        approve(fixture, client, fill(12, com, PAGE))
        // Remembered on the first site only.
        assertEquals("secret-a", client.call(fill(13, com, PAGE)).string("password"))
        approve(fixture, client, fill(14, org, "https://example.org/"))
    }

    @Test
    fun `a rule enabled while the prompt is open stops the release`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        fixture.login("a")
        val client = paired(fixture)
        val handle = client.handle(10, PAGE)

        client.send(fill(11, handle, PAGE))
        val prompt = fixture.service.approvals.first { it.isNotEmpty() }.single()
        fixture.urlBlocks.value = listOf(urlBlock(scope = UrlBlockScope.ALL))
        fixture.service.respondToApproval(prompt.id, approved = true)
        assertEquals("not_allowed", client.receive()?.string("error"))
        assertTrue(fixture.autofillHistory.isEmpty())
    }

    @Test
    fun `an item with master password re-prompt always asks, and needs the user's identity`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        fixture.login("a", reprompt = true)
        val client = paired(fixture)
        val handle = client.handle(10, PAGE)

        client.send(fill(11, handle, PAGE))
        var prompt = fixture.service.approvals.first { it.isNotEmpty() }.single()
        assertEquals(true, assertIs<BrowserOperation.Fill>(prompt.operation).release.reprompt)
        assertTrue(fixture.service.confirmIdentity(prompt.id) { true })
        fixture.service.respondToApproval(prompt.id, approved = true)
        assertEquals("secret-a", client.receive()?.string("password"))

        // Asks again; approving without confirming the identity denies it.
        client.send(fill(12, handle, PAGE))
        prompt = fixture.service.approvals.first { it.isNotEmpty() }.single()
        assertEquals(false, fixture.service.confirmIdentity(prompt.id) { false })
        fixture.service.respondToApproval(prompt.id, approved = true)
        assertEquals("user_denied", client.receive()?.string("error"))
    }

    @Test
    fun `re-prompt turned on while the prompt is open stops the release`() = browserTest { scope ->
        // Asks every time: an approval is remembered before the release is refused.
        val fixture = BrowserFixture(scope, approvalWindow = Duration.ZERO)
        val login = fixture.login("a", totp = TOTP)
        val client = paired(fixture)
        val handle = client.handle(10, PAGE)

        for (request in listOf(fill(11, handle, PAGE), totp(12, handle))) {
            fixture.ciphers.value = listOf(login)
            client.send(request)
            val prompt = fixture.service.approvals.first { it.isNotEmpty() }.single()
            assertEquals(false, assertIs<BrowserOperation.Release>(prompt.operation).release.reprompt, request)
            fixture.ciphers.value = listOf(login.copy(reprompt = true))
            fixture.service.respondToApproval(prompt.id, approved = true)
            assertEquals("not_allowed", client.receive()?.string("error"), request)
        }
        assertTrue(fixture.autofillHistory.isEmpty())
    }

    @Test
    fun `an item that stops matching while the prompt is open is not released`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        val login = fixture.login("a")
        val client = paired(fixture)
        val handle = client.handle(10, PAGE)

        client.send(fill(11, handle, PAGE))
        val prompt = fixture.service.approvals.first { it.isNotEmpty() }.single()
        assertEquals(true, assertIs<BrowserOperation.Fill>(prompt.operation).release.matched)
        fixture.ciphers.value = listOf(login.copy(uris = listOf(DSecret.Uri(uri = "https://example.org"))))
        fixture.service.respondToApproval(prompt.id, approved = true)
        assertEquals("not_allowed", client.receive()?.string("error"))
        assertTrue(fixture.autofillHistory.isEmpty())
    }

    @Test
    fun `an approval with identity still covers an item that turned re-prompt off`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        val login = fixture.login("a", reprompt = true)
        val client = paired(fixture)
        val handle = client.handle(10, PAGE)

        client.send(fill(11, handle, PAGE))
        val prompt = fixture.service.approvals.first { it.isNotEmpty() }.single()
        assertTrue(fixture.service.confirmIdentity(prompt.id) { true })
        fixture.ciphers.value = listOf(login.copy(reprompt = false))
        fixture.service.respondToApproval(prompt.id, approved = true)
        assertEquals("secret-a", client.receive()?.string("password"))
        assertEquals(listOf("a"), fixture.autofillHistory.map { it.cipherId })
    }

    @Test
    fun `a denied fill and a timed out fill are refused`() = browserTest { scope ->
        val fixture = BrowserFixture(scope, approvalTimeout = 300.milliseconds)
        fixture.login("a")
        val client = paired(fixture)
        val handle = client.handle(10, PAGE)

        client.send(fill(11, handle, PAGE))
        val prompt = fixture.service.approvals.first { it.isNotEmpty() }.single()
        fixture.service.respondToApproval(prompt.id, approved = false)
        assertEquals("user_denied", client.receive()?.string("error"))

        assertEquals("approval_timeout", client.call(fill(12, handle, PAGE)).string("error"))
        assertTrue(fixture.autofillHistory.isEmpty())
    }

    @Test
    fun `locking the vault forgets the approvals`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        fixture.login("a")
        val client = paired(fixture)
        val handle = client.handle(10, PAGE)
        approve(fixture, client, fill(11, handle, PAGE))

        fixture.lock()
        assertEquals("vault_locked", client.callSkippingEvents(fill(12, handle, PAGE)).string("error"))
        fixture.unlock()
        fixture.vault.first { it == BrowserVaultState.UNLOCKED }
        approve(fixture, client, fill(13, handle, PAGE), skipEvents = true)
    }

    @Test
    fun `an approval window of zero asks every time, never ask asks never`() = browserTest { scope ->
        val always = BrowserFixture(scope, approvalWindow = Duration.ZERO)
        always.login("a")
        val client = paired(always)
        val handle = client.handle(10, PAGE)
        approve(always, client, fill(11, handle, PAGE))
        approve(always, client, fill(12, handle, PAGE))

        val never = BrowserFixture(scope, approvalWindow = AGENT_APPROVAL_WINDOW_NEVER_ASK)
        never.login("a")
        never.login("b", reprompt = true)
        val other = paired(never)
        val items = other.call(suggest(10, PAGE)).items().associateBy { it.string("name") }
        val login = checkNotNull(items.getValue("Login a").string("handle"))
        assertEquals("secret-a", other.call(fill(11, login, PAGE)).string("password"))
        // Re-prompt items still ask.
        val reprompt = checkNotNull(items.getValue("Login b").string("handle"))
        other.send(fill(12, reprompt, PAGE))
        val prompt = never.service.approvals.first { it.isNotEmpty() }.single()
        never.service.respondToApproval(prompt.id, approved = false)
        assertEquals("user_denied", other.receive()?.string("error"))
    }

    @Test
    fun `totp releases the current code of a time-based token`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        fixture.login("a", totp = TOTP)
        fixture.login("b", username = "bob")
        fixture.login("c", username = "carol", totp = HOTP)
        val client = paired(fixture)
        val items = client.call(suggest(10, PAGE)).items().associateBy { it.string("name") }
        assertEquals(false, items.getValue("Login c").bool("has_totp"))

        client.send(totp(11, checkNotNull(items.getValue("Login a").string("handle"))))
        val prompt = fixture.service.approvals.first { it.isNotEmpty() }.single()
        assertIs<BrowserOperation.Totp>(prompt.operation)
        fixture.service.respondToApproval(prompt.id, approved = true)
        val response = checkNotNull(client.receive())
        assertEquals(setOf("id", "ok", "code"), response.keys)
        assertEquals("123456", response.string("code"))

        // Without a TOTP, or with a counter-based one.
        for ((id, name) in listOf(12 to "Login b", 13 to "Login c")) {
            val handle = checkNotNull(items.getValue(name).string("handle"))
            assertEquals("invalid_request", client.call(totp(id, handle)).string("error"))
        }
    }

    @Test
    fun `generate uses the saved settings of the password generator`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        fixture.screenState.value = mapOf(
            "generator@*" to mapOf(
                "password.length" to 24L,
                "password.include_symbols" to false,
                // A value of another type is ignored.
                "password.include_numbers" to "yes",
            ),
        )
        val client = paired(fixture)
        // No prompt, and no unlocked vault needed.
        fixture.lock()
        val response = client.callSkippingEvents(
            """{"id":10,"type":"generate","url":"https://login.example.com/sign-up"}""",
        )
        assertEquals("generated-1", response.string("password"))
        val (context, config) = fixture.generated.single()
        assertEquals("login.example.com", context.host)
        val password = assertIs<PasswordGeneratorConfig.Password>(config)
        assertEquals(24, password.length)
        assertTrue(password.symbolChars.isEmpty())
        assertTrue(password.numberChars.isNotEmpty())

        val bare = client.callSkippingEvents("""{"id":11,"type":"generate"}""")
        assertEquals("generated-2", bare.string("password"))
        assertNull(fixture.generated.last().first.host)
    }

    @Test
    fun `save opens a window with a new login, one per session`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        fixture.login("a")
        val client = paired(fixture)

        client.send(save(10, "https://login.example.com/sign-up", "bob", password = "new"))
        val request = fixture.service.saves.first { it.isNotEmpty() }.single()
        assertEquals("https", request.webScheme)
        assertEquals("login.example.com", request.webDomain)
        assertEquals("bob", request.username)
        assertEquals("new", request.password)
        assertNull(request.cipherId)
        assertEquals("Google Chrome", request.pairingName)
        fixture.service.onSaveShown(request.id)
        // No fields: the window is on screen.
        val opened = checkNotNull(client.receive())
        assertEquals(true, opened.bool("ok"))
        assertEquals(setOf("id", "ok"), opened.keys)

        // The window is still open.
        assertEquals("busy", client.call(save(11, PAGE, "carol")).string("error"))
        fixture.service.closeSave(request.id)
        client.send(save(12, PAGE, "carol"))
        val next = fixture.service.saves.first { it.isNotEmpty() }.single()
        fixture.service.onSaveShown(next.id)
        assertEquals(true, client.receive()?.bool("ok"))
    }

    @Test
    fun `save opens the login of the same site and username`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        fixture.login("a", username = "alice", password = "old")
        fixture.login("b", username = "alice", password = "older")
        fixture.login("c", username = "alice", reprompt = true)
        val client = paired(fixture)

        client.send(save(10, PAGE, "alice", password = "new", previousPassword = "older"))
        val request = fixture.service.saves.first { it.isNotEmpty() }.single()
        // The one with the previous password; never one that asks for the master password.
        assertEquals("b", request.cipherId)
        assertEquals("new", request.password)
        fixture.service.closeSave(request.id)
        // Closed before it showed.
        assertEquals("user_denied", client.receive()?.string("error"))
    }

    @Test
    fun `save never opens a login the user can't edit`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        fixture.login("a")
        val client = paired(fixture)
        assertEquals("a", client.savedCipherId(fixture, save(10, PAGE, "alice")))

        // The window shows a new login instead.
        fixture.canWrite.value = false
        assertNull(client.savedCipherId(fixture, save(11, PAGE, "alice")))
    }

    @Test
    fun `save never opens a login that only its name ties to the page`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        // Its name is alike the page, which `suggest` counts.
        fixture.login("alike", name = "Example", uri = "https://shop.other.org", password = "old")
        val client = paired(fixture)
        assertEquals(listOf("Example"), client.call(suggest(10, PAGE)).items().names())
        // Not even with the previous password.
        assertNull(client.savedCipherId(fixture, save(11, PAGE, "alice", previousPassword = "old")))

        fixture.login("a", password = "other")
        assertEquals("a", client.savedCipherId(fixture, save(12, PAGE, "alice", previousPassword = "old")))
    }

    @Test
    fun `save needs the capability`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        val client = fixture.connect()
        client.call("""{"id":1,"type":"hello","protocol":1,"capabilities":[]}""")
        assertEquals("not_supported", client.call(save(2, PAGE, "bob")).string("error"))
    }

    @Test
    fun `the request log records releases and pairings`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        fixture.login("a")
        val client = paired(fixture)
        val handle = client.handle(10, PAGE)
        approve(fixture, client, fill(11, handle, "https://login.example.com/"))
        client.send(fill(12, handle, PAGE))
        // Remembered: no prompt.
        client.receive()

        val pair = fixture.awaitRecorded(CliAgentUsageHistoryRequestType.BROWSER_PAIR).single()
        assertEquals(CliAgentUsageHistoryClient.BROWSER, pair.client)
        assertEquals(CliAgentUsageHistoryResponseType.SUCCESS, pair.response)
        assertEquals("Google Chrome", pair.clientName)
        val fills = fixture.awaitRecorded(CliAgentUsageHistoryRequestType.BROWSER_FILL, count = 2)
        fills.forEach { fill ->
            assertEquals(CliAgentUsageHistoryResponseType.SUCCESS, fill.response)
            assertEquals("a", fill.cipherId)
            assertEquals("Google Chrome", fill.clientName)
            assertTrue(fill.caller.orEmpty().contains("Google Chrome"))
        }
        assertEquals(setOf("https://login.example.com", "https://example.com"), fills.map { it.path }.toSet())

        assertEquals("not_allowed", client.call(fill(13, handle, "https://example.org/")).string("error"))
        val refused = fixture.awaitRecorded(CliAgentUsageHistoryRequestType.BROWSER_FILL, count = 3)
            .single { it.response == CliAgentUsageHistoryResponseType.FAILURE }
        assertEquals("not_allowed", refused.errorCode)
    }

    @Test
    fun `a fill of a private window records neither the request nor a use`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        fixture.login("a")
        val client = paired(fixture)
        val handle = client.handle(10, PAGE)

        assertEquals("secret-a", approve(fixture, client, privateFill(11, handle)).string("password"))
        assertTrue(fixture.autofillHistory.isEmpty())
        assertNull(client.call(suggest(12, PAGE)).items().single().bool("last_used"))
        // A refused one isn't recorded either.
        assertEquals("not_allowed", client.call(privateFill(13, handle, "https://example.org/")).string("error"))

        // A fill of a normal window records both; the approval covers it.
        assertEquals("secret-a", client.call(fill(14, handle, PAGE)).string("password"))
        val entry = fixture.awaitRecorded(CliAgentUsageHistoryRequestType.BROWSER_FILL).single()
        assertEquals(CliAgentUsageHistoryResponseType.SUCCESS, entry.response)
        assertEquals("a", entry.cipherId)
        assertEquals("https://example.com", entry.path)
        assertEquals(listOf("a"), fixture.autofillHistory.map { it.cipherId })
        assertEquals(true, client.call(suggest(15, PAGE)).items().single().bool("last_used"))
        delay(100)
        assertEquals(listOf(entry), fixture.recorded().filter { it.request == entry.request })
    }

    @Test
    fun `never_save of a private window adds the rule, and records no request`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        val client = paired(fixture)

        val request = """{"id":10,"type":"never_save","url":"$PAGE","private_window":true}"""
        assertEquals(true, client.call(request).bool("ok"))
        assertEquals("https://example.com", fixture.urlBlocks.value.single().uri)

        // One of a normal window is recorded.
        assertEquals(true, client.call(neverSave(11, PAGE)).bool("ok"))
        val entry = fixture.awaitRecorded(CliAgentUsageHistoryRequestType.BROWSER_NEVER_SAVE).single()
        assertEquals(CliAgentUsageHistoryResponseType.SUCCESS, entry.response)
        assertEquals("https://example.com", entry.path)
        delay(100)
        assertEquals(listOf(entry), fixture.recorded().filter { it.request == entry.request })
    }
}

private const val PAGE = "https://example.com/"
private const val HTTP_PAGE = "http://example.com/"
private const val TOTP = "otpauth://totp/Example:alice?secret=JBSWY3DPEHPK3PXP&issuer=Example"
private const val HOTP = "otpauth://hotp/Example:alice?secret=JBSWY3DPEHPK3PXP&counter=0"

/** A page of the site of [PAGE] on another host. */
private const val LOGIN_PAGE = "https://login.example.com/"

/** The origin of a top-level page of another site than [PAGE]. */
private const val SHOP_ORIGIN = "https://shop.example.net"

private fun suggest(id: Int, url: String) = """{"id":$id,"type":"suggest","url":"$url"}"""

/** A `suggest` of no kinds, as the save bar checks the blocked URIs. */
private fun suggestNoKinds(id: Int, url: String) = """{"id":$id,"type":"suggest","url":"$url","kinds":[]}"""

/** A `suggest` of the logins and the passkeys of `example.com`. */
private fun suggestPasskeys(id: Int) =
    """{"id":$id,"type":"suggest","url":"$PAGE","kinds":["login","passkey"],"rp_id":"example.com"}"""

private fun fill(id: Int, handle: String, url: String, topOrigin: String? = null) =
    """{"id":$id,"type":"fill","handle":"$handle","url":"$url"${topOriginField(topOrigin)}}"""

private fun totp(id: Int, handle: String, url: String = PAGE, topOrigin: String? = null) =
    """{"id":$id,"type":"totp","handle":"$handle","url":"$url"${topOriginField(topOrigin)}}"""

private fun topOriginField(origin: String?) = origin?.let { ""","top_origin":"$it"""" }.orEmpty()

/** A `fill` of [url] in a private window. */
private fun privateFill(id: Int, handle: String, url: String = PAGE) =
    """{"id":$id,"type":"fill","handle":"$handle","url":"$url","private_window":true}"""

private fun save(
    id: Int,
    url: String,
    username: String,
    password: String = "password",
    previousPassword: String? = null,
): String {
    val previous = previousPassword?.let { ""","previous_password":"$it"""" }.orEmpty()
    return """{"id":$id,"type":"save","url":"$url","username":"$username","password":"$password"$previous}"""
}

private fun neverSave(id: Int, url: String) = """{"id":$id,"type":"never_save","url":"$url"}"""

/** A blocked URI of the site `example.com`. */
private fun urlBlock(scope: UrlBlockScope, enabled: Boolean = true) = DGlobalUrlBlock(
    name = "Example",
    description = "",
    uri = "https://example.com",
    mode = MatchDetection.Domain,
    createdDate = Instant.fromEpochSeconds(0),
    enabled = enabled,
    exposed = false,
    scope = scope,
)

private fun JsonObject.items(): List<JsonObject> = (getValue("items") as JsonArray).map { it.jsonObject }

private fun List<JsonObject>.names(): List<String?> = map { it.string("name") }

private fun collection(id: String, hidePasswords: Boolean, accountId: String = ACCOUNT_ID) = DCollection(
    id = id,
    externalId = null,
    organizationId = null,
    accountId = accountId,
    revisionDate = Instant.fromEpochSeconds(0),
    name = id,
    readOnly = false,
    hidePasswords = hidePasswords,
)

/** Records a use of the item [cipherId] at [seconds] after the epoch, as a fill does. */
private fun BrowserFixture.used(cipherId: String, seconds: Long) {
    val request = AddCipherOpenedHistoryRequest(
        accountId = ACCOUNT_ID,
        cipherId = cipherId,
        instant = Instant.fromEpochSeconds(seconds),
    )
    synchronized(autofillHistory) { autofillHistory += request }
}

/** The handle of the only login that matches the page of [url]. */
private suspend fun BrowserClient.handle(id: Int, url: String): String =
    checkNotNull(call(suggest(id, url)).items().single().string("handle"))

/**
 * Sends the `save` [request] and closes its window before it shows; returns
 * the item the window opened, or `null` for a new login.
 */
private suspend fun BrowserClient.savedCipherId(fixture: BrowserFixture, request: String): String? {
    send(request)
    val window = fixture.service.saves.first { it.isNotEmpty() }.single()
    fixture.service.closeSave(window.id)
    assertEquals("user_denied", receive()?.string("error"))
    return window.cipherId
}

/** Sends [request], skipping the events the app sends meanwhile; returns its response. */
private suspend fun BrowserClient.callSkippingEvents(request: String): JsonObject {
    send(request)
    return receiveResponse(idOf(request))
}

private fun idOf(request: String): Long = checkNotNull(Regex(""""id":(\d+)""").find(request))
    .groupValues[1]
    .toLong()

/** Sends [request], which prompts; approves the prompt and returns the response. */
private suspend fun approve(
    fixture: BrowserFixture,
    client: BrowserClient,
    request: String,
    skipEvents: Boolean = false,
): JsonObject {
    client.send(request)
    val prompt = fixture.service.approvals.first { it.isNotEmpty() }.single()
    fixture.service.respondToApproval(prompt.id, approved = true)
    val response = if (skipEvents) client.receiveResponse(idOf(request)) else checkNotNull(client.receive())
    assertEquals(true, response.bool("ok"), response.toString())
    return response
}

/**
 * Sends the `fill` [request], which must prompt as for an item not saved for
 * the page, offering to add the `http` page; denies the prompt.
 */
private suspend fun assertUnmatchedFill(fixture: BrowserFixture, client: BrowserClient, request: String) {
    client.send(request)
    val prompt = fixture.service.approvals.first { it.isNotEmpty() }.single()
    val release = assertIs<BrowserOperation.Fill>(prompt.operation).release
    assertEquals(false, release.matched, request)
    assertEquals("http://example.com", release.addUri, request)
    fixture.service.respondToApproval(prompt.id, approved = false)
    assertEquals("user_denied", client.receive()?.string("error"))
}

/** Waits until the request log holds [count] entries of [type]. */
private suspend fun BrowserFixture.awaitRecorded(
    type: CliAgentUsageHistoryRequestType,
    count: Int = 1,
): List<AddCliAgentUsageHistoryRequest> = withTimeout(TIMEOUT) {
    var recorded = recorded().filter { it.request == type }
    while (recorded.size < count) {
        delay(10)
        recorded = recorded().filter { it.request == type }
    }
    recorded
}
