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
import com.artemchep.keyguard.util.webauthn.PasskeyBase64
import com.artemchep.keyguard.util.webauthn.PasskeyCredentialId
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
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
        val picker = fixture.picker()
        assertEquals("example.com", picker.rpId)
        // The pick approves the request: the window names who asks.
        assertEquals(ORIGIN, picker.origin)
        assertEquals("Google Chrome", picker.pairingName)
        assertEquals(false, picker.create)
        val mode = assertIs<AppMode.PickPasskey>(picker.mode)
        assertEquals("example.com", mode.target.rpId)
        mode.onComplete(fixture.cipher("b"), bob.credential)
        assertTrue(bob.signed(checkNotNull(client.receive()).credential()))
        // The pick approved it: no prompt.
        assertTrue(fixture.service.approvals.value.isEmpty())
        assertTrue(fixture.passkeys.passkeyPickers.value.isEmpty())

        // The pick approved the item on the site for the inline menu as well.
        val handle = client.passkeyHandle(11, userName = "bob")
        assertTrue(bob.signed(client.call(passkeyGet(12, handle = handle)).credential()))
    }

    @Test
    fun `get offers only the passkeys that allowCredentials names`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        val alice = fixture.passkeyLogin("a")
        val bob = fixture.passkeyLogin("b", userName = "bob", discoverable = false)
        val client = paired(fixture)
        val allowBob = getOptions(allow = listOf(bob.wireId()))

        client.send(passkeyGet(10, options = allowBob))
        val mode = assertIs<AppMode.PickPasskey>(fixture.picker().mode)
        assertEquals(true, mode.target.allowed.isAllowCredentialsSupplied)
        // A passkey the page doesn't allow.
        mode.onComplete(fixture.cipher("a"), alice.credential)
        assertEquals("not_allowed", client.receive()?.string("error"))
        assertTrue(fixture.passkeyHistory.isEmpty())

        // The page names a passkey that isn't discoverable.
        client.send(passkeyGet(11, options = allowBob))
        assertIs<AppMode.PickPasskey>(fixture.picker().mode).onComplete(fixture.cipher("b"), bob.credential)
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
        client.send(passkeyGet(11))
        fixture.picker().onAnotherDevice()
        assertEquals("not_supported", client.receive()?.string("error"))
        client.send(passkeyGet(12))
        val picker = fixture.picker()
        // One window per session.
        assertEquals("busy", client.call(passkeyCreate(13)).string("error"))
        picker.onDismiss()
        assertEquals("user_denied", client.receive()?.string("error"))

        // A page that matches a blocked URI.
        fixture.urlBlocks.value = listOf(block("https://example.com"))
        assertEquals("not_supported", client.call(passkeyGet(14)).string("error"))
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
    fun `a picker that the user leaves times out`() = browserTest { scope ->
        val fixture = BrowserFixture(scope, approvalTimeout = 300.milliseconds)
        fixture.passkeyLogin("a")
        val client = paired(fixture)

        assertEquals("approval_timeout", client.call(passkeyGet(10)).string("error"))
        assertTrue(fixture.passkeys.passkeyPickers.value.isEmpty())
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
        val fixture = BrowserFixture(scope)
        val passkey = fixture.passkeyLogin("a")
        fixture.login("b")
        val client = paired(fixture)
        // Without `userVerification`, WebAuthn prefers it.
        val preferred = getOptions(userVerification = null)

        // A pick in the window approves the item, but doesn't verify the user.
        client.send(passkeyGet(10, options = preferred))
        assertIs<AppMode.PickPasskey>(fixture.picker().mode).onComplete(fixture.cipher("a"), passkey.credential)
        var prompt = fixture.prompt()
        assertEquals(true, assertIs<BrowserOperation.PasskeyGet>(prompt.operation).release.userVerification)
        assertTrue(fixture.service.confirmIdentity(prompt.id) { true })
        fixture.service.respondToApproval(prompt.id, approved = true)
        val verified = checkNotNull(client.receive()).credential()
        assertTrue(passkey.signed(verified))
        assertEquals(FLAG_UP or FLAG_UV, verified.flags() and (FLAG_UP or FLAG_UV))

        // The approval of the item doesn't cover the identity.
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
    fun `a pick in the window of an item with re-prompt asks nothing more`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        val passkey = fixture.passkeyLogin("a", reprompt = true)
        val client = paired(fixture)

        // The window asked for the master password before the pick.
        client.send(passkeyGet(10, options = getOptions(userVerification = "required")))
        assertIs<AppMode.PickPasskey>(fixture.picker().mode).onComplete(fixture.cipher("a"), passkey.credential)
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
    fun `ending the session closes the picker and the prompt`() = browserTest { scope ->
        val fixture = BrowserFixture(scope)
        fixture.passkeyLogin("a")
        val client = paired(fixture)
        client.send(passkeyGet(10))
        fixture.picker()

        client.pipe.client.close()
        withTimeout(TIMEOUT) {
            fixture.passkeys.passkeyPickers.first { it.isEmpty() }
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
        fixture.picker()
        fixture.lock()
        assertEquals("vault_locked", client.receiveResponse(10).string("error"))
        assertTrue(fixture.passkeys.passkeyPickers.value.isEmpty())
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
        val passkey = fixture.passkeyLogin("a")
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

        // A pick in the window.
        fixture.urlBlocks.value = emptyList()
        client.send(passkeyGet(12))
        val pick = assertIs<AppMode.PickPasskey>(fixture.picker().mode)
        fixture.urlBlocks.value = blocked
        pick.onComplete(fixture.cipher("a"), passkey.credential)
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
        assertIs<AppMode.PickPasskey>(fixture.picker().mode).onComplete(fixture.cipher("a"), passkey.credential)
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

private suspend fun BrowserFixture.picker(): BrowserPasskeyPicker =
    withTimeout(TIMEOUT) { passkeys.passkeyPickers.first { it.isNotEmpty() }.single() }

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
