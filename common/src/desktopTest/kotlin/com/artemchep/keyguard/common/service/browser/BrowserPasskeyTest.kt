@file:Suppress("TooManyFunctions") // The requests and the checks of the responses.

package com.artemchep.keyguard.common.service.browser

import com.artemchep.keyguard.AppMode
import com.artemchep.keyguard.common.model.AddCliAgentUsageHistoryRequest
import com.artemchep.keyguard.common.model.CliAgentUsageHistoryRequestType
import com.artemchep.keyguard.common.model.CliAgentUsageHistoryResponseType
import com.artemchep.keyguard.common.model.DGlobalUrlBlock
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.model.MatchDetection
import com.artemchep.keyguard.common.model.UrlBlockScope
import com.artemchep.keyguard.common.service.agent.AGENT_APPROVAL_WINDOW_NEVER_ASK
import com.artemchep.keyguard.util.webauthn.PasskeyBase64
import com.artemchep.keyguard.util.webauthn.PasskeyCredentialId
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TestTimeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** The passkeys a paired extension asks the app for, README section 5. */
@Suppress("LargeClass")
class BrowserPasskeyTest {
    @Test
    fun `suggest lists the passkeys of the relying party`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        fixture.passkeyLogin("a")
        // The passkey of the relying party matches the page, whatever the website of the login.
        fixture.passkeyLogin("b", uri = "https://other.org", userName = "bob")
        fixture.passkeyLogin("c", rpId = "other.org")
        // A passkey that answers only the requests that name it.
        fixture.passkeyLogin("d", discoverable = false)
        fixture.login("e")
        val client = paired(fixture)

        val items = client.call(suggestPasskeys(10)).items()
        val byName = items.associateBy { it.string("name") }
        assertEquals(setOf("Login a", "Login b", "Login d", "Login e"), byName.keys)
        val passkey = byName.getValue("Login a").passkeys().single()
        assertEquals("alice", passkey.string("user_name"))
        assertEquals("ALICE", passkey.string("user_display_name"))
        assertNotEquals(byName.getValue("Login a").string("handle"), passkey.string("handle"))
        assertEquals("bob", byName.getValue("Login b").passkeys().single().string("user_name"))
        assertTrue(byName.getValue("Login d").passkeys().isEmpty())
        assertTrue(byName.getValue("Login e").passkeys().isEmpty())
        // A passkey keeps its handle for the session.
        assertEquals(passkey.string("handle"), client.passkeyHandle(11))

        val passkeysOnly = client.call(suggestPasskeys(12, kinds = """["passkey"]""")).items()
        assertEquals(setOf("Login a", "Login b"), passkeysOnly.map { it.string("name") }.toSet())
        val loginsOnly = client.call("""{"id":13,"type":"suggest","url":"$PAGE"}""").items()
        assertTrue(loginsOnly.all { it["passkeys"] == null })
    }

    @Test
    fun `suggest checks the relying party against the page`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        // A login of another site, which the page doesn't match.
        fixture.passkeyLogin("a", rpId = "other.org")
        fixture.login("b")
        val client = paired(fixture)

        assertEquals("security", client.call(suggestPasskeys(10, rpId = "other.org")).string("error"))
        // A public suffix is no relying party.
        assertEquals("security", client.call(suggestPasskeys(11, rpId = "com")).string("error"))
        assertTrue(fixture.fetched.contains("https://other.org/.well-known/webauthn"))
        // The relying party lists the page among its related origins.
        fixture.wellKnown["other.org"] = """{"origins":["$ORIGIN"]}"""
        val related = client.call(suggestPasskeys(12, rpId = "other.org")).items()
        // The logins of the page come first, then the logins of the passkeys.
        assertEquals(listOf("Login b", "Login a"), related.map { it.string("name") })
        assertEquals("alice", related.last().passkeys().single().string("user_name"))
        // The host of the page, and its registrable domain.
        assertEquals(true, client.call(suggestPasskeys(13, rpId = "login.example.com")).bool("ok"))
        assertEquals(true, client.call(suggestPasskeys(14, rpId = "example.com")).bool("ok"))
    }

    @Test
    fun `get with a handle asks once, then the approval covers it`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        val passkey = fixture.passkeyLogin("a")
        val client = paired(fixture)
        val handle = client.passkeyHandle(10)

        client.send(passkeyGet(11, handle = handle))
        val prompt = fixture.prompt()
        val operation = assertIs<BrowserOperation.PasskeyGet>(prompt.operation)
        assertEquals(ORIGIN, operation.release.origin)
        assertEquals("https://example.com", operation.release.site)
        // The relying party is of the page's site.
        assertNull(operation.release.otherSiteRpId)
        assertEquals("Login a", operation.release.name)
        assertEquals("alice", operation.release.username)
        assertEquals(false, operation.release.userVerification)
        assertEquals(false, prompt.requiresIdentity)
        fixture.service.respondToApproval(prompt.id, approved = true)
        val credential = checkNotNull(client.receive()).credential()
        assertEquals("public-key", credential.string("type"))
        val clientData = credential.clientData()
        assertEquals("webauthn.get", clientData.string("type"))
        assertEquals(ORIGIN, clientData.string("origin"))
        assertEquals(CHALLENGE, clientData.string("challenge"))
        assertTrue(passkey.signed(credential))
        // Present, but not verified: the page discourages user verification.
        assertEquals(FLAG_UP, credential.flags() and (FLAG_UP or FLAG_UV))
        assertEquals(
            PasskeyBase64.encodeToString("user-a".encodeToByteArray()),
            credential.response().string("userHandle"),
        )

        // Remembered for the item on the site.
        val again = client.call(passkeyGet(12, handle = handle)).credential()
        assertTrue(passkey.signed(again))
        assertTrue(fixture.service.approvals.value.isEmpty())
        assertEquals(listOf("a", "a"), fixture.passkeyHistory.map { it.cipherId })
        assertEquals(passkey.credential.credentialId, fixture.passkeyHistory.first().credentialId)
        val log = fixture.awaitRecorded(CliAgentUsageHistoryRequestType.BROWSER_PASSKEY_GET, count = 2)
        log.forEach { entry ->
            assertEquals(CliAgentUsageHistoryResponseType.SUCCESS, entry.response)
            assertEquals("a", entry.cipherId)
            assertEquals(ORIGIN, entry.browserDetails.pageOrigin)
        }
    }

    @Test
    fun `a sign-in of a private window records neither the request nor a use`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        val passkey = fixture.passkeyLogin("a")
        val client = paired(fixture)
        val handle = client.passkeyHandle(10)

        assertTrue(passkey.signed(fixture.approve(client, passkeyGet(11, handle = handle, privateWindow = true))))
        assertTrue(fixture.passkeyHistory.isEmpty())

        // A sign-in of a normal window records both; the approval covers it.
        assertTrue(passkey.signed(client.call(passkeyGet(12, handle = handle)).credential()))
        val entry = fixture.awaitRecorded(CliAgentUsageHistoryRequestType.BROWSER_PASSKEY_GET).single()
        assertEquals(CliAgentUsageHistoryResponseType.SUCCESS, entry.response)
        assertEquals("a", entry.cipherId)
        assertEquals(ORIGIN, entry.browserDetails.pageOrigin)
        assertEquals(listOf("a"), fixture.passkeyHistory.map { it.cipherId })
        delay(100)
        assertEquals(listOf(entry), fixture.recorded().filter { it.request == entry.request })
    }

    @Test
    fun `get without a handle lets the user pick a passkey`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        fixture.passkeyLogin("a")
        val bob = fixture.passkeyLogin("b", userName = "bob")
        val client = paired(fixture)

        client.send(passkeyGet(10))
        val prompt = fixture.pickPrompt()
        assertEquals("Google Chrome", prompt.pairingName)
        // Neither was used: in the order of the vault.
        val choices = prompt.choices()
        assertEquals(listOf("a", "b"), choices.map { it.release.cipherId })
        val release = choices[1].release
        assertEquals(ORIGIN, release.origin)
        assertEquals("https://example.com", release.site)
        assertNull(release.otherSiteRpId)
        assertEquals("Login b", release.name)
        assertEquals("bob", release.username)
        assertEquals(false, release.userVerification)
        assertTrue(choices.none { it.requiresIdentity })
        assertEquals(false, prompt.requiresIdentity)
        assertTrue(fixture.service.selectPasskey(prompt.id, 1))
        fixture.service.respondToApproval(prompt.id, approved = true)
        assertTrue(bob.signed(checkNotNull(client.receive()).credential()))
        assertTrue(fixture.service.approvals.value.isEmpty())
        assertTrue(fixture.passkeys.passkeyPickers.value.isEmpty())

        // The pick approved the item on the site for the inline menu as well.
        val handle = client.passkeyHandle(11, userName = "bob")
        assertTrue(bob.signed(client.call(passkeyGet(12, handle = handle)).credential()))
    }

    @Test
    fun `a pick asks despite an approval of the only passkey`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        val passkey = fixture.passkeyLogin("a")
        val client = paired(fixture)
        fixture.approve(client, passkeyGet(10, handle = client.passkeyHandle(11)))

        // The pick is the user's gesture of the sign-in.
        client.send(passkeyGet(12))
        val prompt = fixture.pickPrompt()
        assertEquals(listOf("a"), prompt.choices().map { it.release.cipherId })
        fixture.service.respondToApproval(prompt.id, approved = true)
        assertTrue(passkey.signed(checkNotNull(client.receive()).credential()))
    }

    @Test
    fun `a pick lists the most recently used passkey first`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        fixture.passkeyLogin("a")
        val bob = fixture.passkeyLogin("b", userName = "bob")
        fixture.passkeyLogin("c", userName = "carol")
        val client = paired(fixture)
        fixture.approve(client, passkeyGet(10, handle = client.passkeyHandle(11, userName = "bob")))

        client.send(passkeyGet(12))
        var prompt = fixture.pickPrompt()
        assertEquals(listOf("b", "a", "c"), prompt.choices().map { it.release.cipherId })
        // The first one is selected.
        fixture.service.respondToApproval(prompt.id, approved = true)
        assertTrue(bob.signed(checkNotNull(client.receive()).credential()))

        // Without the history, in the order of the vault.
        fixture.lastUsedFails = true
        client.send(passkeyGet(13))
        prompt = fixture.pickPrompt()
        assertEquals(listOf("a", "b", "c"), prompt.choices().map { it.release.cipherId })
        fixture.service.respondToApproval(prompt.id, approved = false)
        assertEquals("user_denied", client.receive()?.string("error"))
    }

    @Test
    fun `get offers only the passkeys that allowCredentials names`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        fixture.passkeyLogin("a")
        val bob = fixture.passkeyLogin("b", userName = "bob", discoverable = false)
        val client = paired(fixture)
        val allowBob = getOptions(allow = listOf(bob.wireId()))

        // The page names a passkey that isn't discoverable; the others aren't offered.
        client.send(passkeyGet(11, options = allowBob))
        val prompt = fixture.pickPrompt()
        assertEquals(listOf("b"), prompt.choices().map { it.release.cipherId })
        assertFalse(fixture.service.selectPasskey(prompt.id, 1))
        assertFalse(fixture.service.selectPasskey(prompt.id, -1))
        fixture.service.respondToApproval(prompt.id, approved = true)
        assertTrue(bob.signed(checkNotNull(client.receive()).credential()))

        val handle = client.passkeyHandle(12)
        assertEquals("not_allowed", client.call(passkeyGet(13, handle = handle, options = allowBob)).string("error"))
        // None of the passkeys it names is in the vault.
        val unknown = getOptions(allow = listOf(PasskeyBase64.encodeToString(ByteArray(16) { 7 })))
        assertEquals("not_supported", client.call(passkeyGet(14, options = unknown)).string("error"))
    }

    @Test
    fun `get hands the call back to the browser`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        val client = paired(fixture)
        // No passkey of the relying party.
        assertEquals("not_supported", client.call(passkeyGet(10)).string("error"))

        fixture.passkeyLogin("a")
        fixture.login("b")
        client.send(passkeyGet(11))
        var prompt = fixture.pickPrompt()
        assertTrue(fixture.service.useAnotherDevice(prompt.id))
        fixture.service.respondToApproval(prompt.id, approved = false)
        assertEquals("not_supported", client.receive()?.string("error"))
        client.send(passkeyGet(12))
        prompt = fixture.pickPrompt()
        // One passkey prompt or window per session.
        assertEquals("busy", client.call(passkeyGet(13)).string("error"))
        assertEquals("busy", client.call(passkeyCreate(14)).string("error"))
        fixture.service.respondToApproval(prompt.id, approved = false)
        assertEquals("user_denied", client.receive()?.string("error"))
        assertFalse(fixture.service.useAnotherDevice(prompt.id))

        client.send(passkeyCreate(15))
        val picker = fixture.picker()
        assertEquals("busy", client.call(passkeyGet(16)).string("error"))
        picker.onDismiss()
        assertEquals("user_denied", client.receive()?.string("error"))

        // A page that matches a blocked URI.
        fixture.urlBlocks.value = listOf(block("https://example.com"))
        assertEquals("not_supported", client.call(passkeyGet(17)).string("error"))
        assertTrue(fixture.service.approvals.value.isEmpty())
        assertTrue(fixture.passkeys.passkeyPickers.value.isEmpty())
    }

    @Test
    fun `a rule of saving only keeps passkeys`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        val passkey = fixture.passkeyLogin("a")
        fixture.login("b", username = "bob")
        fixture.urlBlocks.value = listOf(block("https://example.com", scope = UrlBlockScope.SAVE))
        val client = paired(fixture)

        val suggested = client.call(suggestPasskeys(10))
        assertEquals(false, suggested.bool("blocked"))
        assertEquals(true, suggested.bool("save_blocked"))
        val handle = client.passkeyHandle(11)
        assertTrue(passkey.signed(fixture.approve(client, passkeyGet(12, handle = handle))))

        client.send(passkeyCreate(13))
        val mode = assertIs<AppMode.SavePasskey>(fixture.picker().mode)
        mode.onComplete(fixture.cipher("b"))
        assertEquals(true, client.receive()?.bool("ok"))
        assertEquals("bob", checkNotNull(fixture.cipher("b").login).fido2Credentials.single().userName)
    }

    @Test
    fun `a pick that the user leaves times out`() = browserTest { scope ->
        val fixture = BrowserFixture(scope, approvalTimeout = 300.milliseconds)
        fixture.passkeyLogin("a")
        val client = paired(fixture)

        assertEquals("approval_timeout", client.call(passkeyGet(10)).string("error"))
        assertTrue(fixture.service.approvals.value.isEmpty())
        // The session may ask again.
        assertEquals("approval_timeout", client.call(passkeyGet(11)).string("error"))
    }

    @Test
    fun `required user verification asks for the identity despite an approval`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        val passkey = fixture.passkeyLogin("a")
        val client = paired(fixture)
        val handle = client.passkeyHandle(10)
        fixture.approve(client, passkeyGet(11, handle = handle))
        val required = getOptions(userVerification = "required")

        client.send(passkeyGet(12, handle = handle, options = required))
        var prompt = fixture.prompt()
        assertEquals(true, assertIs<BrowserOperation.PasskeyGet>(prompt.operation).release.userVerification)
        assertTrue(prompt.requiresIdentity)
        // Approving without confirming the identity denies it.
        assertEquals(false, fixture.service.confirmIdentity(prompt.id) { false })
        fixture.service.respondToApproval(prompt.id, approved = true)
        assertEquals("user_denied", client.receive()?.string("error"))

        client.send(passkeyGet(13, handle = handle, options = required))
        prompt = fixture.prompt()
        assertTrue(fixture.service.confirmIdentity(prompt.id) { true })
        fixture.service.respondToApproval(prompt.id, approved = true)
        val verified = checkNotNull(client.receive()).credential()
        assertTrue(passkey.signed(verified))
        assertEquals(FLAG_UP or FLAG_UV, verified.flags() and (FLAG_UP or FLAG_UV))

        // Discouraged: the approval covers it, and the user isn't verified.
        val discouraged = client.call(passkeyGet(14, handle = handle)).credential()
        assertEquals(FLAG_UP, discouraged.flags() and (FLAG_UP or FLAG_UV))
    }

    @Test
    fun `preferred user verification asks for the identity`() = browserTest { scope ->
        val time = TestTimeSource()
        val fixture = BrowserFixture(scope, timeSource = time)
        val passkey = fixture.passkeyLogin("a")
        fixture.login("b")
        val client = paired(fixture)
        // Without `userVerification`, WebAuthn prefers it.
        val preferred = getOptions(userVerification = null)

        // The pick asks for the identity in the same prompt.
        client.send(passkeyGet(9, options = preferred))
        var prompt = fixture.pickPrompt()
        val choice = prompt.choices().single()
        assertEquals(true, choice.release.userVerification)
        assertTrue(choice.requiresIdentity)
        assertTrue(prompt.requiresIdentity)
        // Approving without confirming the identity denies it.
        fixture.service.respondToApproval(prompt.id, approved = true)
        assertEquals("user_denied", client.receive()?.string("error"))

        client.send(passkeyGet(10, options = preferred))
        prompt = fixture.pickPrompt()
        assertTrue(fixture.service.confirmIdentity(prompt.id) { true })
        fixture.service.respondToApproval(prompt.id, approved = true)
        val verified = checkNotNull(client.receive()).credential()
        assertTrue(passkey.signed(verified))
        assertEquals(FLAG_UP or FLAG_UV, verified.flags() and (FLAG_UP or FLAG_UV))

        // Once the identity check expires, the approval of the item doesn't cover the identity.
        time += BROWSER_IDENTITY_CHECK_MAX_WINDOW
        val handle = client.passkeyHandle(11)
        client.send(passkeyGet(12, handle = handle, options = getOptions(userVerification = "preferred")))
        prompt = fixture.prompt()
        assertEquals(true, assertIs<BrowserOperation.PasskeyGet>(prompt.operation).release.userVerification)
        fixture.service.respondToApproval(prompt.id, approved = false)
        assertEquals("user_denied", client.receive()?.string("error"))

        // Saving a passkey asks as well.
        client.send(passkeyCreate(13, options = createOptions(userVerification = null)))
        assertIs<AppMode.SavePasskey>(fixture.picker().mode).onComplete(fixture.cipher("b"))
        prompt = fixture.prompt()
        assertEquals(true, assertIs<BrowserOperation.PasskeyCreate>(prompt.operation).release.userVerification)
        assertTrue(fixture.service.confirmIdentity(prompt.id) { true })
        fixture.service.respondToApproval(prompt.id, approved = true)
        val created = checkNotNull(client.receive()).credential()
        assertEquals(FLAG_UP or FLAG_UV, created.flags() and (FLAG_UP or FLAG_UV))
    }

    @Test
    fun `a pick of an item with re-prompt asks for the identity in the same prompt`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        val passkey = fixture.passkeyLogin("a", reprompt = true)
        val client = paired(fixture)

        client.send(passkeyGet(10, options = getOptions(userVerification = "required")))
        val pick = fixture.pickPrompt()
        assertEquals(true, pick.choices().single().release.reprompt)
        assertTrue(pick.requiresIdentity)
        assertTrue(fixture.service.confirmIdentity(pick.id) { true })
        fixture.service.respondToApproval(pick.id, approved = true)
        val credential = checkNotNull(client.receive()).credential()
        assertTrue(passkey.signed(credential))
        assertEquals(FLAG_UP or FLAG_UV, credential.flags() and (FLAG_UP or FLAG_UV))
        assertTrue(fixture.service.approvals.value.isEmpty())

        // Never remembered: once the item no longer asks, the inline menu still does.
        fixture.ciphers.update { list -> list.map { if (it.id == "a") it.copy(reprompt = false) else it } }
        val handle = client.passkeyHandle(11)
        client.send(passkeyGet(12, handle = handle))
        val prompt = fixture.prompt()
        assertEquals(false, assertIs<BrowserOperation.PasskeyGet>(prompt.operation).release.reprompt)
        fixture.service.respondToApproval(prompt.id, approved = true)
        assertTrue(passkey.signed(checkNotNull(client.receive()).credential()))
    }

    @Test
    fun `an unlock during the request verifies the user`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        fixture.passkeyLogin("a")
        val client = paired(fixture)
        val handle = client.passkeyHandle(10)
        val required = getOptions(userVerification = "required")
        fixture.lock()

        // Closing the unlock prompt declines.
        client.send(passkeyGet(11, handle = handle, options = required))
        val declined = fixture.prompt()
        assertIs<BrowserOperation.Unlock>(declined.operation)
        fixture.service.respondToApproval(declined.id, approved = false)
        assertEquals("user_denied", client.receiveResponse(11).string("error"))

        client.send(passkeyGet(12, handle = handle, options = required))
        assertIs<BrowserOperation.Unlock>(fixture.prompt().operation)
        fixture.unlock()
        // Still the approval of the item, but not of the identity.
        val prompt = fixture.service.approvals
            .first { list -> list.any { it.operation is BrowserOperation.PasskeyGet } }
            .single()
        assertEquals(false, assertIs<BrowserOperation.PasskeyGet>(prompt.operation).release.userVerification)
        fixture.service.respondToApproval(prompt.id, approved = true)
        val credential = client.receiveResponse(12).credential()
        assertEquals(FLAG_UP or FLAG_UV, credential.flags() and (FLAG_UP or FLAG_UV))
        // The unlock started an identity check: the approval covers the next request.
        val covered = client.call(passkeyGet(20, handle = handle, options = required)).credential()
        assertEquals(FLAG_UP or FLAG_UV, covered.flags() and (FLAG_UP or FLAG_UV))
        assertTrue(fixture.service.approvals.value.isEmpty())

        // A page that discourages user verification never learns of it.
        fixture.lock()
        client.send(passkeyGet(13, handle = handle, options = getOptions(userVerification = "discouraged")))
        assertIs<BrowserOperation.Unlock>(fixture.prompt().operation)
        fixture.unlock()
        val again = fixture.service.approvals
            .first { list -> list.any { it.operation is BrowserOperation.PasskeyGet } }
            .single()
        fixture.service.respondToApproval(again.id, approved = true)
        val discouraged = client.receiveResponse(13).credential()
        assertEquals(FLAG_UP, discouraged.flags() and (FLAG_UP or FLAG_UV))
    }

    @Test
    fun `a recent identity check stands in for user verification`() = browserTest { scope ->
        val time = TestTimeSource()
        val fixture = BrowserFixture(scope, timeSource = time)
        val alice = fixture.passkeyLogin("a")
        val bob = fixture.passkeyLogin("b", userName = "bob")
        fixture.login("c")
        val dave = fixture.passkeyLogin("d", userName = "dave")
        val client = paired(fixture)
        val aliceHandle = client.passkeyHandle(10)
        val bobHandle = client.passkeyHandle(11, userName = "bob")
        val required = getOptions(userVerification = "required")
        fixture.approve(client, passkeyGet(12, handle = aliceHandle))

        // No check yet: the prompt asks for the identity despite the approval, which starts a check.
        val verified = fixture.verify(client, passkeyGet(13, handle = aliceHandle, options = required))
        assertEquals(FLAG_UP or FLAG_UV, verified.flags() and (FLAG_UP or FLAG_UV))

        // The check and an approval of the item: no prompt, and the user is verified.
        time += 20.seconds
        val covered = client.call(passkeyGet(14, handle = aliceHandle, options = required)).credential()
        assertTrue(alice.signed(covered))
        assertEquals(FLAG_UP or FLAG_UV, covered.flags() and (FLAG_UP or FLAG_UV))
        assertTrue(fixture.service.approvals.value.isEmpty())

        // The check without an approval of the item: the prompt asks only for the approval.
        client.send(passkeyGet(15, handle = bobHandle, options = required))
        var prompt = fixture.prompt()
        assertEquals(true, assertIs<BrowserOperation.PasskeyGet>(prompt.operation).release.userVerification)
        assertEquals(false, prompt.requiresIdentity)
        fixture.service.respondToApproval(prompt.id, approved = true)
        val approved = checkNotNull(client.receive()).credential()
        assertTrue(bob.signed(approved))
        assertEquals(FLAG_UP or FLAG_UV, approved.flags() and (FLAG_UP or FLAG_UV))

        // A pick asks, but the check verifies the user.
        time += 9.seconds
        client.send(passkeyGet(16, options = required))
        prompt = fixture.pickPrompt()
        val choices = prompt.choices()
        assertTrue(choices.none { it.requiresIdentity })
        assertTrue(fixture.service.selectPasskey(prompt.id, choices.indexOfFirst { it.release.cipherId == "d" }))
        fixture.service.respondToApproval(prompt.id, approved = true)
        val picked = checkNotNull(client.receive()).credential()
        assertTrue(dave.signed(picked))
        assertEquals(FLAG_UP or FLAG_UV, picked.flags() and (FLAG_UP or FLAG_UV))
        assertTrue(fixture.service.approvals.value.isEmpty())

        // Saving a passkey prompts, but doesn't ask for the identity.
        client.send(passkeyCreate(17, options = createOptions(userVerification = "required")))
        assertIs<AppMode.SavePasskey>(fixture.picker().mode).onComplete(fixture.cipher("c"))
        prompt = fixture.prompt()
        assertEquals(false, prompt.requiresIdentity)
        fixture.service.respondToApproval(prompt.id, approved = true)
        val created = checkNotNull(client.receive()).credential()
        assertEquals(FLAG_UP or FLAG_UV, created.flags() and (FLAG_UP or FLAG_UV))

        // Approving without the identity started no check: it expires 30 seconds after the first one.
        time += 2.seconds
        client.send(passkeyGet(18, handle = aliceHandle, options = required))
        prompt = fixture.prompt()
        assertTrue(prompt.requiresIdentity)
        fixture.service.respondToApproval(prompt.id, approved = false)
        assertEquals("user_denied", client.receive()?.string("error"))
    }

    @Test
    fun `an identity check lasts for a shorter approval window`() = browserTest { scope ->
        val time = TestTimeSource()
        val fixture = BrowserFixture(scope, approvalWindow = 10.seconds, timeSource = time)
        fixture.passkeyLogin("a")
        val client = paired(fixture)
        val handle = client.passkeyHandle(10)
        val required = getOptions(userVerification = "required")
        fixture.verify(client, passkeyGet(11, handle = handle, options = required))

        time += 9.seconds
        val covered = client.call(passkeyGet(12, handle = handle, options = required)).credential()
        assertEquals(FLAG_UP or FLAG_UV, covered.flags() and (FLAG_UP or FLAG_UV))

        time += 2.seconds
        client.send(passkeyGet(13, handle = handle, options = required))
        assertTrue(fixture.prompt().requiresIdentity)
    }

    @Test
    fun `an identity check lasts at most 30 seconds, also for never ask`() = browserTest { scope ->
        for (approvalWindow in listOf(1.hours, AGENT_APPROVAL_WINDOW_NEVER_ASK)) {
            val time = TestTimeSource()
            val fixture = BrowserFixture(scope, approvalWindow = approvalWindow, timeSource = time)
            fixture.passkeyLogin("a")
            val client = paired(fixture)
            val handle = client.passkeyHandle(10)
            val required = getOptions(userVerification = "required")
            // "Never ask" covers the item, but not the identity.
            fixture.verify(client, passkeyGet(11, handle = handle, options = required))

            time += BROWSER_IDENTITY_CHECK_MAX_WINDOW - 1.seconds
            val covered = client.call(passkeyGet(12, handle = handle, options = required)).credential()
            assertEquals(FLAG_UP or FLAG_UV, covered.flags() and (FLAG_UP or FLAG_UV), "$approvalWindow")

            time += 2.seconds
            client.send(passkeyGet(13, handle = handle, options = required))
            val prompt = fixture.prompt()
            assertTrue(prompt.requiresIdentity, "$approvalWindow")
            fixture.service.respondToApproval(prompt.id, approved = false)
            assertEquals("user_denied", client.receive()?.string("error"))
            client.pipe.client.close()
        }
    }

    @Test
    fun `an approval window of zero remembers no identity check`() = browserTest { scope ->
        val fixture = BrowserFixture(scope, approvalWindow = Duration.ZERO)
        fixture.passkeyLogin("a")
        val client = paired(fixture)
        val handle = client.passkeyHandle(10)
        val required = getOptions(userVerification = "required")

        fixture.verify(client, passkeyGet(11, handle = handle, options = required))
        val again = fixture.verify(client, passkeyGet(12, handle = handle, options = required))
        assertEquals(FLAG_UP or FLAG_UV, again.flags() and (FLAG_UP or FLAG_UV))
    }

    @Test
    fun `locking the vault or turning the integration off ends the identity check`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        fixture.passkeyLogin("a")
        fixture.passkeyLogin("b", userName = "bob")
        val original = paired(fixture)
        val required = getOptions(userVerification = "required")
        fixture.verify(original, passkeyGet(10, handle = original.passkeyHandle(11), options = required))

        // Another vault session: the prompt of an item without an approval asks for the identity.
        fixture.lock()
        fixture.unlock()
        val bobHandle = original.passkeyHandle(13, userName = "bob")
        fixture.verify(original, passkeyGet(12, handle = bobHandle, options = required))

        // The same pairing, after the integration was off.
        fixture.service.setEnabled(false)
        fixture.service.setEnabled(true)
        val client = fixture.connect(original.keyPair, pinned = original.noise.remoteStatic)
        assertEquals(true, client.hello().bool("paired"))
        fixture.verify(client, passkeyGet(14, handle = client.passkeyHandle(15), options = required))
    }

    @Test
    fun `an identity check never covers an item with re-prompt`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        val alice = fixture.passkeyLogin("a", reprompt = true)
        val bob = fixture.passkeyLogin("b", userName = "bob")
        val client = paired(fixture)
        val aliceHandle = client.passkeyHandle(10)
        val bobHandle = client.passkeyHandle(11, userName = "bob")
        val required = getOptions(userVerification = "required")

        // The master password that the pick asks for starts a check.
        client.send(passkeyGet(12, options = required))
        val pick = fixture.pickPrompt()
        assertEquals("a", pick.choices().first().release.cipherId)
        assertTrue(fixture.service.confirmIdentity(pick.id) { true })
        fixture.service.respondToApproval(pick.id, approved = true)
        assertTrue(alice.signed(checkNotNull(client.receive()).credential()))
        client.send(passkeyGet(13, handle = bobHandle, options = required))
        val prompt = fixture.prompt()
        assertEquals(false, prompt.requiresIdentity)
        fixture.service.respondToApproval(prompt.id, approved = true)
        assertTrue(bob.signed(checkNotNull(client.receive()).credential()))

        // The item with re-prompt asks for the identity, whatever the page wants.
        val verified = fixture.verify(client, passkeyGet(14, handle = aliceHandle, options = required))
        assertEquals(FLAG_UP or FLAG_UV, verified.flags() and (FLAG_UP or FLAG_UV))
        fixture.verify(client, passkeyGet(15, handle = aliceHandle))
    }

    @Test
    fun `get refuses calls that the app doesn't serve`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        fixture.passkeyLogin("a")
        val client = paired(fixture)
        val handle = client.passkeyHandle(10)
        val itemHandle = checkNotNull(client.call(suggestPasskeys(11)).items().single().string("handle"))

        for ((request, error) in listOf(
            // A frame of another site, and a silent call.
            passkeyGet(20, sameOrigin = false) to "not_supported",
            passkeyGet(21, mediation = "silent") to "not_supported",
            // A conditional call comes with the user's pick in the inline menu.
            passkeyGet(22, mediation = "conditional") to "invalid_request",
            // A relying party of another site, and a page without HTTPS.
            passkeyGet(23, options = getOptions(rpId = "other.org")) to "security",
            passkeyGet(24, origin = "http://login.example.com") to "security",
            passkeyGet(25, origin = "https://login.example.com/sign-in") to "security",
            // Malformed options.
            passkeyGet(26, options = getOptions(challenge = null)) to "invalid_request",
            passkeyGet(27, options = """{"challenge":"$CHALLENGE","rpId":7}""") to "invalid_request",
            // A handle of another relying party, an unknown one, and an item handle.
            passkeyGet(28, handle = handle, options = getOptions(rpId = "login.example.com")) to "not_allowed",
            passkeyGet(29, handle = "unknown") to "invalid_request",
            passkeyGet(30, handle = itemHandle) to "invalid_request",
            // A local page is a web origin, without passkeys here.
            passkeyGet(31, origin = LOCALHOST, options = getOptions(rpId = "localhost")) to "not_supported",
            // A mediation that version 1 doesn't know.
            passkeyGet(32, mediation = "immediate") to "not_supported",
            // A challenge in standard Base64, not base64url.
            passkeyGet(34, options = getOptions(challenge = "+/8=")) to "invalid_request",
        )) {
            assertEquals(error, client.call(request).string("error"), request)
        }
        assertTrue(fixture.service.approvals.value.isEmpty())
        assertTrue(fixture.passkeys.passkeyPickers.value.isEmpty())

        // Before the unlock prompt.
        fixture.lock()
        client.send(passkeyGet(33, mediation = "immediate"))
        assertEquals("not_supported", client.receiveResponse(33).string("error"))
        assertTrue(fixture.service.approvals.value.isEmpty())
    }

    @Test
    fun `create saves a new passkey in the login the user picks`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        fixture.login("a", username = "bob")
        val client = paired(fixture)

        client.send(passkeyCreate(10))
        val picker = fixture.picker()
        assertEquals(true, picker.create)
        val mode = assertIs<AppMode.SavePasskey>(picker.mode)
        assertEquals("example.com", mode.rpId)
        mode.onComplete(fixture.cipher("a"))
        val credential = checkNotNull(client.receive()).credential()
        assertEquals("public-key", credential.string("type"))
        val clientData = credential.clientData()
        assertEquals("webauthn.create", clientData.string("type"))
        assertEquals(ORIGIN, clientData.string("origin"))
        val response = credential.response()
        assertEquals(-7, response.getValue("publicKeyAlgorithm").jsonPrimitive.content.toInt())
        assertEquals(listOf("internal"), response.getValue("transports").jsonArray.map { it.jsonPrimitive.content })
        assertTrue(response.string("attestationObject").orEmpty().isNotEmpty())
        // Present, not verified, with attested credential data.
        assertEquals(FLAG_UP or FLAG_AT, credential.flags() and (FLAG_UP or FLAG_UV or FLAG_AT))
        val stored = checkNotNull(fixture.cipher("a").login).fido2Credentials.single()
        assertEquals("example.com", stored.rpId)
        assertEquals("bob", stored.userName)
        assertEquals(credential.string("id"), stored.wireId())
        val log = fixture.awaitRecorded(CliAgentUsageHistoryRequestType.BROWSER_PASSKEY_CREATE).single()
        assertEquals(CliAgentUsageHistoryResponseType.SUCCESS, log.response)
        assertEquals("a", log.cipherId)

        // The new passkey signs in.
        val handle = client.passkeyHandle(11, userName = "bob")
        fixture.approve(client, passkeyGet(12, handle = handle))

        // A login that the user adds in the window.
        client.send(passkeyCreate(13, options = createOptions(userName = "carol")))
        val next = assertIs<AppMode.SavePasskey>(fixture.picker().mode)
        val added = fixture.login("new", username = "carol")
        next.onComplete(added)
        assertEquals(true, client.receive()?.bool("ok"))
        assertEquals("carol", checkNotNull(fixture.cipher("new").login).fido2Credentials.single().userName)
    }

    @Test
    fun `create refuses an excluded passkey once the user picks a login`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        val existing = fixture.passkeyLogin("a")
        fixture.login("b")
        val client = paired(fixture)

        client.send(passkeyCreate(10, options = createOptions(exclude = listOf(existing.wireId()))))
        // The page learns nothing before the user consents.
        assertIs<AppMode.SavePasskey>(fixture.picker().mode).onComplete(fixture.cipher("b"))
        assertEquals("invalid_state", client.receive()?.string("error"))
        assertTrue(checkNotNull(fixture.cipher("b").login).fido2Credentials.isEmpty())
        val log = fixture.awaitRecorded(CliAgentUsageHistoryRequestType.BROWSER_PASSKEY_CREATE).single()
        assertEquals("invalid_state", log.errorCode)
    }

    @Test
    fun `create refuses calls that the app doesn't serve`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        fixture.login("a")
        val client = paired(fixture)

        for ((request, error) in listOf(
            // The browser may support the algorithm.
            passkeyCreate(10, options = createOptions(alg = -257)) to "not_supported",
            passkeyCreate(11, sameOrigin = false) to "not_supported",
            passkeyCreate(12, options = createOptions(rpId = "other.org")) to "security",
            passkeyCreate(13, options = """{"challenge":"$CHALLENGE"}""") to "invalid_request",
            // Binary fields in standard Base64, not base64url.
            passkeyCreate(14, options = createOptions(challenge = "+/8=")) to "invalid_request",
            passkeyCreate(15, options = createOptions(userId = "dXNlcg==")) to "invalid_request",
            // A user handle of 0 or 65 bytes.
            passkeyCreate(16, options = createOptions(userId = "")) to "invalid_request",
            passkeyCreate(17, options = createOptions(userId = PasskeyBase64.encodeToString(ByteArray(65)))) to
                "invalid_request",
        )) {
            assertEquals(error, client.call(request).string("error"), request)
        }
        assertTrue(fixture.passkeys.passkeyPickers.value.isEmpty())

        // Before the unlock prompt.
        fixture.lock()
        client.send(passkeyCreate(18, options = createOptions(userId = "")))
        assertEquals("invalid_request", client.receiveResponse(18).string("error"))
        assertTrue(fixture.service.approvals.value.isEmpty())
    }

    @Test
    fun `create takes a user handle of 64 bytes`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        fixture.login("a")
        val client = paired(fixture)
        val userHandle = ByteArray(64) { it.toByte() }

        client.send(passkeyCreate(10, options = createOptions(userId = PasskeyBase64.encodeToString(userHandle))))
        assertIs<AppMode.SavePasskey>(fixture.picker().mode).onComplete(fixture.cipher("a"))
        assertEquals(true, client.receive()?.bool("ok"))
        val stored = checkNotNull(fixture.cipher("a").login).fido2Credentials.single()
        assertContentEquals(userHandle, PasskeyBase64.decodeUserHandleOrNull(checkNotNull(stored.userHandle)))
    }

    @Test
    fun `create with required user verification asks for the identity`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        fixture.login("a")
        val client = paired(fixture)

        client.send(passkeyCreate(10, options = createOptions(userVerification = "required")))
        assertIs<AppMode.SavePasskey>(fixture.picker().mode).onComplete(fixture.cipher("a"))
        val prompt = fixture.prompt()
        val operation = assertIs<BrowserOperation.PasskeyCreate>(prompt.operation)
        assertEquals("Login a", operation.release.name)
        assertEquals("bob", operation.release.username)
        assertEquals(true, operation.release.userVerification)
        assertTrue(prompt.requiresIdentity)
        assertTrue(fixture.service.confirmIdentity(prompt.id) { true })
        fixture.service.respondToApproval(prompt.id, approved = true)
        val credential = checkNotNull(client.receive()).credential()
        assertEquals(FLAG_UP or FLAG_UV, credential.flags() and (FLAG_UP or FLAG_UV))
    }

    @Test
    fun `create into a login the user can't edit asks nothing`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        fixture.login("a")
        val client = paired(fixture)
        fixture.canWrite.value = false

        client.send(passkeyCreate(10, options = createOptions(userVerification = "required")))
        assertIs<AppMode.SavePasskey>(fixture.picker().mode).onComplete(fixture.cipher("a"))
        assertEquals("not_allowed", client.receive()?.string("error"))
        assertTrue(fixture.service.approvals.value.isEmpty())
        assertTrue(checkNotNull(fixture.cipher("a").login).fido2Credentials.isEmpty())
    }

    @Test
    fun `create ignores an attestation that WebAuthn doesn't know`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        fixture.login("a")
        val client = paired(fixture)

        client.send(passkeyCreate(10, options = createOptions(attestation = "unknown")))
        assertIs<AppMode.SavePasskey>(fixture.picker().mode).onComplete(fixture.cipher("a"))
        val credential = checkNotNull(client.receive()).credential()
        assertTrue(credential.response().string("attestationObject").orEmpty().isNotEmpty())
        assertEquals(1, checkNotNull(fixture.cipher("a").login).fido2Credentials.size)
    }

    @Test
    fun `locking the vault during a prompt answers vault_locked`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        fixture.passkeyLogin("a")
        fixture.login("b")
        val client = paired(fixture)
        val handle = client.passkeyHandle(10)

        // Locking denies the prompts, here before the vault session ends.
        client.send(passkeyGet(11, handle = handle))
        fixture.prompt()
        fixture.session.value = MasterSession.Empty()
        assertEquals("vault_locked", client.receiveResponse(11).string("error"))

        // The prompt that asks for the identity.
        fixture.unlock()
        client.send(passkeyCreate(12, options = createOptions(userVerification = "required")))
        assertIs<AppMode.SavePasskey>(fixture.picker().mode).onComplete(fixture.cipher("b"))
        fixture.prompt()
        fixture.session.value = MasterSession.Empty()
        assertEquals("vault_locked", client.receiveResponse(12).string("error"))
        assertTrue(checkNotNull(fixture.cipher("b").login).fido2Credentials.isEmpty())
    }

    @Test
    fun `ending the session closes the prompts`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        fixture.passkeyLogin("a")
        val client = paired(fixture)
        client.send(passkeyGet(10))
        fixture.pickPrompt()

        client.pipe.client.close()
        withTimeout(TIMEOUT) {
            fixture.service.approvals.first { it.isEmpty() }
        }
        val log = fixture.awaitRecorded(CliAgentUsageHistoryRequestType.BROWSER_PASSKEY_GET).single()
        assertEquals(CliAgentUsageHistoryResponseType.CANCELLED, log.response)

        val other = paired(fixture)
        other.send(passkeyGet(10, handle = other.passkeyHandle(11)))
        fixture.prompt()
        other.pipe.client.close()
        withTimeout(TIMEOUT) {
            fixture.service.approvals.first { list -> list.none { it.operation is BrowserOperation.PasskeyGet } }
        }
    }

    @Test
    fun `locking the vault ends the request`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        fixture.passkeyLogin("a")
        val client = paired(fixture)

        client.send(passkeyGet(10))
        fixture.pickPrompt()
        fixture.lock()
        assertEquals("vault_locked", client.receiveResponse(10).string("error"))
        assertTrue(fixture.service.approvals.value.isEmpty())
    }

    @Test
    fun `passkeys need the capability`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        val client = fixture.connect()
        client.call("""{"id":1,"type":"hello","protocol":1,"capabilities":["save"]}""")
        assertEquals("not_supported", client.call(passkeyGet(2)).string("error"))
        assertEquals("not_supported", client.call(passkeyCreate(3)).string("error"))
    }

    @Test
    fun `a page blocked while the user is asked gets not_supported`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        fixture.passkeyLogin("a")
        fixture.login("b")
        val client = paired(fixture)
        val handle = client.passkeyHandle(10)
        val blocked = listOf(block("https://example.com"))

        // The prompt of a pick in the inline menu.
        client.send(passkeyGet(11, handle = handle))
        var prompt = fixture.prompt()
        fixture.urlBlocks.value = blocked
        fixture.service.respondToApproval(prompt.id, approved = true)
        assertEquals("not_supported", client.receive()?.string("error"))

        // The prompt of a pick.
        fixture.urlBlocks.value = emptyList()
        client.send(passkeyGet(12))
        prompt = fixture.pickPrompt()
        fixture.urlBlocks.value = blocked
        fixture.service.respondToApproval(prompt.id, approved = true)
        assertEquals("not_supported", client.receive()?.string("error"))
        assertTrue(fixture.passkeyHistory.isEmpty())

        // The login that stores a new passkey.
        fixture.urlBlocks.value = emptyList()
        client.send(passkeyCreate(13))
        val save = assertIs<AppMode.SavePasskey>(fixture.picker().mode)
        fixture.urlBlocks.value = blocked
        save.onComplete(fixture.cipher("b"))
        assertEquals("not_supported", client.receive()?.string("error"))

        // The prompt that asks for the identity to store it.
        fixture.urlBlocks.value = emptyList()
        client.send(passkeyCreate(14, options = createOptions(userVerification = "required")))
        assertIs<AppMode.SavePasskey>(fixture.picker().mode).onComplete(fixture.cipher("b"))
        prompt = fixture.prompt()
        fixture.urlBlocks.value = blocked
        assertTrue(fixture.service.confirmIdentity(prompt.id) { true })
        fixture.service.respondToApproval(prompt.id, approved = true)
        assertEquals("not_supported", client.receive()?.string("error"))
        assertTrue(checkNotNull(fixture.cipher("b").login).fido2Credentials.isEmpty())
    }

    @Test
    fun `re-prompt turned on while the user is asked stops the sign-in`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        val passkey = fixture.passkeyLogin("a")
        val login = fixture.cipher("a")
        val client = paired(fixture)
        val handle = client.passkeyHandle(10)

        client.send(passkeyGet(11, handle = handle))
        var prompt = fixture.prompt()
        assertEquals(false, assertIs<BrowserOperation.PasskeyGet>(prompt.operation).release.reprompt)
        fixture.ciphers.value = listOf(login.copy(reprompt = true))
        fixture.service.respondToApproval(prompt.id, approved = true)
        assertEquals("not_allowed", client.receive()?.string("error"))
        assertTrue(fixture.passkeyHistory.isEmpty())

        // A prompt that confirmed the identity, for user verification, covers it.
        fixture.ciphers.value = listOf(login)
        client.send(passkeyGet(12, handle = handle, options = getOptions(userVerification = "required")))
        prompt = fixture.prompt()
        assertEquals(false, assertIs<BrowserOperation.PasskeyGet>(prompt.operation).release.reprompt)
        assertTrue(fixture.service.confirmIdentity(prompt.id) { true })
        fixture.ciphers.value = listOf(login.copy(reprompt = true))
        fixture.service.respondToApproval(prompt.id, approved = true)
        assertTrue(passkey.signed(checkNotNull(client.receive()).credential()))
        assertEquals(listOf("a"), fixture.passkeyHistory.map { it.cipherId })

        // The prompt of a pick, which didn't ask for the identity.
        fixture.ciphers.value = listOf(login)
        client.send(passkeyGet(13))
        prompt = fixture.pickPrompt()
        assertEquals(false, prompt.requiresIdentity)
        fixture.ciphers.value = listOf(login.copy(reprompt = true))
        fixture.service.respondToApproval(prompt.id, approved = true)
        assertEquals("not_allowed", client.receive()?.string("error"))
        assertEquals(listOf("a"), fixture.passkeyHistory.map { it.cipherId })
    }

    @Test
    fun `a prompt names a relying party of another site`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        val passkey = fixture.passkeyLogin("a", rpId = "other.org")
        fixture.login("b")
        // The relying party lists the page among its related origins.
        fixture.wellKnown["other.org"] = """{"origins":["$ORIGIN"]}"""
        val client = paired(fixture)
        val handle = client.passkeyHandle(10, rpId = "other.org")

        client.send(passkeyGet(11, handle = handle, options = getOptions(rpId = "other.org")))
        var prompt = fixture.prompt()
        val get = assertIs<BrowserOperation.PasskeyGet>(prompt.operation)
        assertEquals("https://example.com", get.release.site)
        assertEquals("other.org", get.release.otherSiteRpId)
        fixture.service.respondToApproval(prompt.id, approved = true)
        assertTrue(passkey.signed(checkNotNull(client.receive()).credential()))

        val options = createOptions(rpId = "other.org", userVerification = "required")
        client.send(passkeyCreate(12, options = options))
        val picker = fixture.picker()
        assertEquals("other.org", picker.rpId)
        assertIs<AppMode.SavePasskey>(picker.mode).onComplete(fixture.cipher("b"))
        prompt = fixture.prompt()
        assertEquals("other.org", assertIs<BrowserOperation.PasskeyCreate>(prompt.operation).release.otherSiteRpId)
        fixture.service.respondToApproval(prompt.id, approved = false)
        assertEquals("user_denied", client.receive()?.string("error"))
    }

    @Test
    fun `a pick binds the identity to the selected passkey`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        fixture.passkeyLogin("a")
        fixture.passkeyLogin("b", userName = "bob", reprompt = true)
        fixture.passkeyLogin("c", userName = "carol", reprompt = true)
        val client = paired(fixture)

        // Selecting an item with re-prompt requires the identity.
        client.send(passkeyGet(10))
        var prompt = fixture.pickPrompt()
        assertEquals(listOf(false, true, true), prompt.choices().map { it.requiresIdentity })
        assertTrue(fixture.service.confirmIdentity(prompt.id) { error("Not required") })
        assertTrue(fixture.service.selectPasskey(prompt.id, 1))
        fixture.service.respondToApproval(prompt.id, approved = true)
        assertEquals("user_denied", client.receive()?.string("error"))

        // The identity confirmed for one passkey doesn't hold for another one, nor once it is selected again.
        client.send(passkeyGet(11))
        prompt = fixture.pickPrompt()
        assertTrue(fixture.service.selectPasskey(prompt.id, 1))
        assertTrue(fixture.service.confirmIdentity(prompt.id) { true })
        assertTrue(fixture.service.selectPasskey(prompt.id, 2))
        assertTrue(fixture.service.selectPasskey(prompt.id, 1))
        fixture.service.respondToApproval(prompt.id, approved = true)
        assertEquals("user_denied", client.receive()?.string("error"))

        // A selection that changes while the user confirms their identity.
        client.send(passkeyGet(12))
        prompt = fixture.pickPrompt()
        assertTrue(fixture.service.selectPasskey(prompt.id, 1))
        val id = prompt.id
        assertFalse(fixture.service.confirmIdentity(id) { fixture.service.selectPasskey(id, 2) })
        fixture.service.respondToApproval(id, approved = true)
        assertEquals("user_denied", client.receive()?.string("error"))
        assertTrue(fixture.passkeyHistory.isEmpty())
    }

    @Test
    fun `a recent identity check covers user verification of a pick, never re-prompt`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        val alice = fixture.passkeyLogin("a")
        fixture.passkeyLogin("b", userName = "bob", reprompt = true)
        val client = paired(fixture)
        val required = getOptions(userVerification = "required")

        // No check yet: both require the identity.
        client.send(passkeyGet(10, options = required))
        var prompt = fixture.pickPrompt()
        assertEquals(mapOf("a" to true, "b" to true), prompt.requiresIdentityById())
        assertTrue(fixture.service.confirmIdentity(prompt.id) { true })
        fixture.service.respondToApproval(prompt.id, approved = true)
        val verified = checkNotNull(client.receive()).credential()
        assertTrue(alice.signed(verified))
        assertEquals(FLAG_UP or FLAG_UV, verified.flags() and (FLAG_UP or FLAG_UV))

        // The check that the pick started covers user verification only.
        client.send(passkeyGet(11, options = required))
        prompt = fixture.pickPrompt()
        assertEquals(mapOf("a" to false, "b" to true), prompt.requiresIdentityById())
        assertEquals("a", prompt.choices().first().release.cipherId)
        assertEquals(false, prompt.requiresIdentity)
        fixture.service.respondToApproval(prompt.id, approved = true)
        val covered = checkNotNull(client.receive()).credential()
        assertEquals(FLAG_UP or FLAG_UV, covered.flags() and (FLAG_UP or FLAG_UV))
    }

    @Test
    fun `the origin must be serialized as a browser serializes it`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        val passkey = fixture.passkeyLogin("a")
        fixture.login("b")
        val client = paired(fixture)

        // A trailing slash, the default port, and uppercase.
        listOf(
            "https://login.example.com/",
            "https://login.example.com:443",
            "https://Login.Example.com",
            "HTTPS://login.example.com",
        ).forEachIndexed { i, origin ->
            assertEquals("security", client.call(passkeyGet(10 + 2 * i, origin = origin)).string("error"), origin)
            assertEquals("security", client.call(passkeyCreate(11 + 2 * i, origin = origin)).string("error"), origin)
        }
        assertTrue(fixture.passkeys.passkeyPickers.value.isEmpty())

        // Another port is part of the origin, which `clientDataJSON` names.
        val withPort = "https://login.example.com:8443"
        client.send(passkeyGet(20, origin = withPort))
        val prompt = fixture.pickPrompt()
        assertEquals(withPort, prompt.choices().single().release.origin)
        fixture.service.respondToApproval(prompt.id, approved = true)
        val credential = checkNotNull(client.receive()).credential()
        assertEquals(withPort, credential.clientData().string("origin"))
        assertTrue(passkey.signed(credential))
    }
}

private const val ORIGIN = "https://login.example.com"
private const val PAGE = "https://login.example.com/sign-in"
private const val LOCALHOST = "http://localhost:8080"

/** The flags of the authenticator data: user present, user verified, attested credential data. */
private const val FLAG_UP = 0x01
private const val FLAG_UV = 0x04
private const val FLAG_AT = 0x40

private val CHALLENGE = PasskeyBase64.encodeToString("challenge-of-the-site".encodeToByteArray())

private fun suggestPasskeys(
    id: Int,
    rpId: String = "example.com",
    kinds: String = """["login","passkey"]""",
) = """{"id":$id,"type":"suggest","url":"$PAGE","kinds":$kinds,"rp_id":"$rpId"}"""

/**
 * `PublicKeyCredentialRequestOptionsJSON`. The [userVerification] is
 * `discouraged`, which asks nothing more, unless a test names another; `null`
 * leaves it out, which means `preferred`.
 */
private fun getOptions(
    rpId: String? = "example.com",
    userVerification: String? = "discouraged",
    allow: List<String> = emptyList(),
    challenge: String? = CHALLENGE,
): String = buildJsonObject {
    challenge?.let { put("challenge", it) }
    rpId?.let { put("rpId", it) }
    userVerification?.let { put("userVerification", it) }
    if (allow.isNotEmpty()) {
        putJsonArray("allowCredentials") {
            allow.forEach { id ->
                add(
                    buildJsonObject {
                        put("type", "public-key")
                        put("id", id)
                    },
                )
            }
        }
    }
}.toString()

@Suppress("LongParameterList")
private fun passkeyGet(
    id: Int,
    options: String = getOptions(),
    handle: String? = null,
    mediation: String = if (handle != null) "conditional" else "optional",
    origin: String = ORIGIN,
    sameOrigin: Boolean = true,
    privateWindow: Boolean = false,
): String = buildJsonObject {
    put("id", id)
    put("type", "passkey_get")
    put("origin", origin)
    put("options", Json.parseToJsonElement(options))
    put("mediation", mediation)
    put("same_origin_with_ancestors", sameOrigin)
    handle?.let { put("handle", it) }
    if (privateWindow) put("private_window", true)
}.toString()

/** `PublicKeyCredentialCreationOptionsJSON`; the [userVerification] as for [getOptions]. */
@Suppress("LongParameterList")
private fun createOptions(
    rpId: String? = "example.com",
    userName: String = "bob",
    alg: Int = -7,
    exclude: List<String> = emptyList(),
    userVerification: String? = "discouraged",
    challenge: String = CHALLENGE,
    userId: String = PasskeyBase64.encodeToString("user-$userName".encodeToByteArray()),
    attestation: String? = null,
): String = buildJsonObject {
    put("challenge", challenge)
    attestation?.let { put("attestation", it) }
    putJsonObject("rp") {
        rpId?.let { put("id", it) }
        put("name", "Example")
    }
    putJsonObject("user") {
        put("id", userId)
        put("name", userName)
        put("displayName", userName.uppercase())
    }
    putJsonArray("pubKeyCredParams") {
        add(
            buildJsonObject {
                put("type", "public-key")
                put("alg", alg)
            },
        )
    }
    putJsonArray("excludeCredentials") {
        exclude.forEach { id ->
            add(
                buildJsonObject {
                    put("type", "public-key")
                    put("id", id)
                },
            )
        }
    }
    putJsonObject("authenticatorSelection") {
        put("residentKey", "required")
        userVerification?.let { put("userVerification", it) }
    }
}.toString()

private fun passkeyCreate(
    id: Int,
    options: String = createOptions(),
    origin: String = ORIGIN,
    sameOrigin: Boolean = true,
): String = buildJsonObject {
    put("id", id)
    put("type", "passkey_create")
    put("origin", origin)
    put("options", Json.parseToJsonElement(options))
    put("same_origin_with_ancestors", sameOrigin)
}.toString()

private fun block(uri: String, scope: UrlBlockScope = UrlBlockScope.ALL) = DGlobalUrlBlock(
    name = "Blocked",
    description = "",
    uri = uri,
    mode = MatchDetection.Domain,
    createdDate = Instant.fromEpochSeconds(0),
    enabled = true,
    exposed = false,
    scope = scope,
)

private fun JsonObject.items(): List<JsonObject> = (getValue("items") as JsonArray).map { it.jsonObject }

private fun JsonObject.passkeys(): List<JsonObject> = (getValue("passkeys") as JsonArray).map { it.jsonObject }

private fun JsonObject.credential(): JsonObject {
    assertEquals(true, bool("ok"), toString())
    return getValue("credential").jsonObject
}

private fun JsonObject.response(): JsonObject = getValue("response").jsonObject

private fun JsonObject.clientData(): JsonObject {
    val bytes = PasskeyBase64.decode(checkNotNull(response().string("clientDataJSON")))
    return Json.parseToJsonElement(bytes.decodeToString()).jsonObject
}

/** The flags byte of the authenticator data of a credential. */
private fun JsonObject.flags(): Int {
    val data = PasskeyBase64.decode(checkNotNull(response().string("authenticatorData")))
    return data[32].toInt() and 0xff
}

/** The ID of a passkey, as a page names it. */
private fun TestPasskey.wireId(): String = credential.wireId()

private fun DSecret.Login.Fido2Credentials.wireId(): String =
    PasskeyBase64.encodeToString(PasskeyCredentialId.encode(credentialId))

/** Whether the [credential] carries this passkey's signature over its authenticator and client data. */
private fun TestPasskey.signed(credential: JsonObject): Boolean {
    val response = credential.response()
    val authenticatorData = PasskeyBase64.decode(checkNotNull(response.string("authenticatorData")))
    val clientData = PasskeyBase64.decode(checkNotNull(response.string("clientDataJSON")))
    val hash = MessageDigest.getInstance("SHA-256").digest(clientData)
    val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicKey))
    return Signature.getInstance("SHA256withECDSA").run {
        initVerify(key)
        update(authenticatorData + hash)
        verify(PasskeyBase64.decode(checkNotNull(response.string("signature"))))
    }
}

/** The handle of the only passkey of [userName] that `suggest` lists for [rpId]. */
private suspend fun BrowserClient.passkeyHandle(
    id: Int,
    userName: String = "alice",
    rpId: String = "example.com",
): String =
    call(suggestPasskeys(id, rpId = rpId))
        .items()
        .flatMap { it.passkeys() }
        .single { it.string("user_name") == userName }
        .let { checkNotNull(it.string("handle")) }

private fun BrowserFixture.cipher(id: String): DSecret = ciphers.value.single { it.id == id }

private suspend fun BrowserFixture.prompt(): BrowserApprovalRequest =
    withTimeout(TIMEOUT) { service.approvals.first { it.isNotEmpty() }.single() }

/** The pending prompt of a pick, see [BrowserOperation.PasskeyPick]. */
private suspend fun BrowserFixture.pickPrompt(): BrowserApprovalRequest {
    val prompt = prompt()
    assertIs<BrowserOperation.PasskeyPick>(prompt.operation)
    return prompt
}

private fun BrowserApprovalRequest.choices(): List<BrowserPasskeyChoice> =
    assertIs<BrowserOperation.PasskeyPick>(operation).choices

private fun BrowserApprovalRequest.requiresIdentityById(): Map<String, Boolean> =
    choices().associate { it.release.cipherId to it.requiresIdentity }

private suspend fun BrowserFixture.picker(): BrowserPasskeyPicker =
    withTimeout(TIMEOUT) { passkeys.passkeyPickers.first { it.isNotEmpty() }.single() }

/** Sends [request], which asks for the identity; confirms it, approves the prompt and returns the credential. */
private suspend fun BrowserFixture.verify(client: BrowserClient, request: String): JsonObject {
    client.send(request)
    val prompt = prompt()
    assertTrue(prompt.requiresIdentity)
    assertTrue(service.confirmIdentity(prompt.id) { true })
    service.respondToApproval(prompt.id, approved = true)
    return checkNotNull(client.receive()).credential()
}

/** Sends [request], which prompts; approves the prompt and returns the credential. */
private suspend fun BrowserFixture.approve(client: BrowserClient, request: String): JsonObject {
    client.send(request)
    val prompt = prompt()
    service.respondToApproval(prompt.id, approved = true)
    return checkNotNull(client.receive()).credential()
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
