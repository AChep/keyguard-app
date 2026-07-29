package com.artemchep.keyguard.common.service.credentialexchange

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.create.CreateRequest
import com.artemchep.keyguard.common.service.credentialexchange.impl.CxfImportServiceImpl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Which requests a credential set assembles into, in which order, and where the
 * note and the leftover fields land.
 *
 * The last test guards the commit path: `AddCipher` fills in `ownership`
 * itself and rejects a request that already has one, so the importer must leave
 * it — and everything else it does not own — at the `CreateRequest` defaults.
 */
class CxfImportAssemblyMatrixTest {
    private val now = Instant.parse("2024-01-30T14:09:33Z")

    private val service = CxfImportServiceImpl(
        sshKeyImportService = FakeSshKeyImportService(),
    )

    private fun requests(vararg credentialsJson: String): List<CreateRequest> = service
        .parseSuccessPlan(documentWithItems(item(*credentialsJson)), now)
        .items
        .map { it.request }

    private fun types(vararg credentialsJson: String): List<DSecret.Type?> =
        requests(*credentialsJson).map { it.type }

    // region Which requests a credential set produces

    private data class AssemblyCase(
        val name: String,
        val credentials: List<String>,
        val expected: List<DSecret.Type>,
    )

    private val assemblyCases = listOf(
        AssemblyCase("basic-auth alone", listOf(BASIC_AUTH), listOf(DSecret.Type.Login)),
        AssemblyCase("a passkey alone", listOf(PASSKEY), listOf(DSecret.Type.Login)),
        AssemblyCase("a totp alone", listOf(TOTP), listOf(DSecret.Type.Login)),
        AssemblyCase("a credit card alone", listOf(CREDIT_CARD), listOf(DSecret.Type.Card)),
        AssemblyCase("a person name alone", listOf(PERSON_NAME), listOf(DSecret.Type.Identity)),
        AssemblyCase("an address alone", listOf(ADDRESS), listOf(DSecret.Type.Identity)),
        AssemblyCase("an ssh key alone", listOf(SSH_KEY), listOf(DSecret.Type.SshKey)),
        AssemblyCase("a note alone", listOf(NOTE), listOf(DSecret.Type.SecureNote)),
        AssemblyCase(
            name = "custom fields alone",
            credentials = listOf(CUSTOM_FIELDS),
            expected = listOf(DSecret.Type.SecureNote),
        ),
        AssemblyCase(
            name = "a note and custom fields together",
            credentials = listOf(NOTE, CUSTOM_FIELDS),
            expected = listOf(DSecret.Type.SecureNote),
        ),
        AssemblyCase(
            // The fixed build order, regardless of the order on the wire.
            name = "every payload at once",
            credentials = listOf(SSH_KEY, PERSON_NAME, CREDIT_CARD, BASIC_AUTH),
            expected = listOf(
                DSecret.Type.Login,
                DSecret.Type.Card,
                DSecret.Type.Identity,
                DSecret.Type.SshKey,
            ),
        ),
        AssemblyCase(
            // A note does not add a request of its own once something else exists.
            name = "a note beside a login",
            credentials = listOf(BASIC_AUTH, NOTE),
            expected = listOf(DSecret.Type.Login),
        ),
        AssemblyCase(
            name = "person name and address merge into one identity",
            credentials = listOf(PERSON_NAME, ADDRESS),
            expected = listOf(DSecret.Type.Identity),
        ),
    )

    @Test
    fun `a credential set assembles into a fixed sequence of requests`() {
        assemblyCases.forEach { case ->
            assertEquals(case.expected, types(*case.credentials.toTypedArray()), case.name)
        }
    }

    @Test
    fun `an item with nothing assemblable produces no request`() {
        assertTrue(requests().isEmpty())
        // A blank note is nothing.
        assertTrue(
            requests(
                """{"type": "note", "content": {"fieldType": "string", "value": "  "}}""",
            ).isEmpty(),
        )
    }

    @Test
    fun `a failing ssh key falls through to a note request`() {
        val failing = CxfImportServiceImpl(FakeSshKeyImportService(sshKeyImportFailure()))
        val plan = failing.parseSuccessPlan(documentWithItems(item(SSH_KEY, NOTE)), now)
        val request = plan.items.single().request
        assertEquals(DSecret.Type.SecureNote, request.type)
        assertEquals(cxfImportSkips(CxfImportSkipReason.SshKey to 1), plan.skips)
    }

    @Test
    fun `a failing passkey falls through to a note request`() {
        val plan = service.parseSuccessPlan(
            documentWithItems(item(passkey(key = "###"), NOTE)),
            now,
        )
        val request = plan.items.single().request
        assertEquals(DSecret.Type.SecureNote, request.type)
        assertEquals(cxfImportSkips(CxfImportSkipReason.Passkey to 1), plan.skips)
    }

    @Test
    fun `one bad passkey does not take the login down with it`() {
        val plan = service.parseSuccessPlan(
            documentWithItems(
                item(
                    passkey(credentialId = "AAECAwQFBg"),
                    passkey(credentialId = "BgUEAwIBAA", key = "###"),
                ),
            ),
            now,
        )
        val request = plan.items.single().request
        assertEquals(DSecret.Type.Login, request.type)
        assertEquals(1, request.fido2Credentials.size)
        assertEquals(cxfImportSkips(CxfImportSkipReason.Passkey to 1), plan.skips)
    }

    @Test
    fun `a bad passkey beside a basic-auth still produces a login`() {
        val plan = service.parseSuccessPlan(
            documentWithItems(item(passkey(key = "###"), BASIC_AUTH)),
            now,
        )
        val request = plan.items.single().request
        assertEquals(DSecret.Type.Login, request.type)
        assertEquals("alice", request.login.username)
        assertTrue(request.fido2Credentials.isEmpty())
        assertEquals(cxfImportSkips(CxfImportSkipReason.Passkey to 1), plan.skips)
    }

    // endregion

    // region Where the note and the leftovers land

    @Test
    fun `the note is copied onto every request`() {
        val requests = requests(BASIC_AUTH, CREDIT_CARD, PERSON_NAME, SSH_KEY, NOTE)
        assertEquals(4, requests.size)
        assertTrue(requests.all { it.note == "a note" }, "got ${requests.map { it.note }}")
    }

    @Test
    fun `leftover fields attach to the first request only`() {
        val requests = requests(BASIC_AUTH, CREDIT_CARD, CUSTOM_FIELDS)
        assertEquals(listOf("Extra"), requests.first().fields.map { it.name })
        assertTrue(requests.drop(1).all { it.fields.isEmpty() })
    }

    @Test
    fun `leftover fields attach to a card when no login exists`() {
        // "First", not "the login" — the rule is positional.
        val requests = requests(CREDIT_CARD, CUSTOM_FIELDS)
        assertEquals(DSecret.Type.Card, requests.single().type)
        assertEquals(listOf("Extra"), requests.single().fields.map { it.name })
    }

    @Test
    fun `an identity keeps its own overflow while leftovers go to the login`() {
        // The identity consumes the labelled field; the unlabelled remainder
        // lands on the first request, which is the login rather than the identity
        // it came from.
        val requests = requests(
            BASIC_AUTH,
            PERSON_NAME,
            """
            {
              "type": "custom-fields",
              "fields": [
                {"fieldType": "string", "value": "Acme", "label": "Company"},
                {"fieldType": "string", "value": "v", "label": "Extra"}
              ]
            }
            """.trimIndent(),
        )
        val login = requests.first { it.type == DSecret.Type.Login }
        val identity = requests.first { it.type == DSecret.Type.Identity }
        assertEquals("Acme", identity.identity.company)
        assertEquals(listOf("Extra"), login.fields.map { it.name })
        assertTrue(identity.fields.isEmpty())
    }

    // endregion

    // region How a login derives its members

    @Test
    fun `a basic-auth username wins over a passkey username`() {
        val request = requests(BASIC_AUTH, PASSKEY).single()
        assertEquals("alice", request.login.username)
    }

    @Test
    fun `a blank basic-auth username falls through to the passkey`() {
        val request = requests(
            """{"type": "basic-auth", "username": {"fieldType": "string", "value": "  "}}""",
            PASSKEY,
        ).single()
        assertEquals("passkey-user", request.login.username)
    }

    @Test
    fun `the first non-blank passkey username wins`() {
        val request = requests(
            passkey(username = "  ", credentialId = "AAECAwQFBg"),
            passkey(username = "second", credentialId = "BgUEAwIBAA"),
        ).single()
        assertEquals("second", request.login.username)
    }

    @Test
    fun `a totp only login has no username`() {
        assertNull(requests(TOTP).single().login.username)
    }

    @Test
    fun `a whitespace only password is kept, an empty one is not`() {
        // A password of only whitespace is a password. Blank-filtering it dropped
        // the secret while the username kept the credential travelling, so the
        // item imported and the tally stayed empty — a Keyguard round trip
        // destroyed the secret. The export mapper gates on `isNotEmpty` for the
        // same reason, so the two sides agree.
        val whitespace = requests(
            """
            {
              "type": "basic-auth",
              "username": {"fieldType": "string", "value": "alice"},
              "password": {"fieldType": "concealed-string", "value": "   "}
            }
            """.trimIndent(),
        ).single()
        assertEquals("   ", whitespace.login.password)
        // An empty value is how the format spells "no password": the exporter
        // never writes one, and there is nothing in it to keep.
        val empty = requests(
            """
            {
              "type": "basic-auth",
              "username": {"fieldType": "string", "value": "alice"},
              "password": {"fieldType": "concealed-string", "value": ""}
            }
            """.trimIndent(),
        ).single()
        assertNull(empty.login.password)
    }

    @Test
    fun `a whitespace only password alone still produces a login`() {
        // The post-mapping login gate reads the mapped members, so the secret has
        // to be enough on its own — otherwise the item would vanish and be
        // counted as skipped instead.
        val plan = service.parseSuccessPlan(
            documentWithItems(
                item("""{"type": "basic-auth", "password": {"fieldType": "concealed-string", "value": " "}}"""),
            ),
            now,
        )
        assertEquals(" ", plan.items.single().request.login.password)
        assertEquals(cxfImportSkips(), plan.skips)
    }

    @Test
    fun `a failed passkey donates neither its username nor its rp id`() {
        // The login exists only because of the totp, so the username and the uri
        // must come from the mapped passkeys, never from the raw wire list.
        val plan = service.parseSuccessPlan(
            documentWithItems(item(passkey(key = "###"), TOTP)),
            now,
        )
        val request = plan.items.single().request
        assertEquals(DSecret.Type.Login, request.type)
        assertNull(request.login.username)
        assertTrue(request.uris.isEmpty())
        assertEquals(cxfImportSkips(CxfImportSkipReason.Passkey to 1), plan.skips)
    }

    @Test
    fun `a scope does not resurrect an item whose only passkey failed`() {
        val plan = service.parseSuccessPlan(
            documentWithItems(
                """
                {
                  "id": "aXRlbTAx",
                  "title": "Scoped",
                  "scope": {"urls": ["https://real.example"], "androidApps": []},
                  "credentials": [${passkey(key = "###")}]
                }
                """.trimIndent(),
            ),
            now,
        )
        assertTrue(plan.items.isEmpty())
        assertEquals(cxfImportSkips(CxfImportSkipReason.Passkey to 1), plan.skips)
    }

    @Test
    fun `a passkey rp id becomes a uri only when the item has no scope`() {
        assertEquals(
            listOf("https://example.com"),
            requests(PASSKEY).single().uris.map { it.uri },
        )
        val scoped = service.parseSuccessPlan(
            documentWithItems(
                """
                {
                  "id": "aXRlbTAx",
                  "title": "Scoped",
                  "scope": {"urls": ["https://real.example"], "androidApps": []},
                  "credentials": [$PASSKEY]
                }
                """.trimIndent(),
            ),
            now,
        )
        assertEquals(
            listOf("https://real.example"),
            scoped.items.single().request.uris.map { it.uri },
        )
    }

    @Test
    fun `an rp id that already has a scheme is not prefixed twice`() {
        val request = requests(passkey(rpId = "https://example.com")).single()
        assertEquals(listOf("https://example.com"), request.uris.map { it.uri })
    }

    // endregion

    // region Titles

    @Test
    fun `a card borrows the cardholder name when the item has no title`() {
        val plan = service.parseSuccessPlan(
            documentWithItems(
                """
                {
                  "id": "aXRlbTAx",
                  "title": "",
                  "credentials": [
                    {
                      "type": "credit-card",
                      "number": {"fieldType": "concealed-string", "value": "4111"},
                      "fullName": {"fieldType": "string", "value": "Alice Example"}
                    }
                  ]
                }
                """.trimIndent(),
            ),
            now,
        )
        assertEquals("Alice Example", plan.items.single().request.title)
    }

    @Test
    fun `no other request type has a title fallback`() {
        val plan = service.parseSuccessPlan(
            documentWithItems(
                """{"id": "aXRlbTAx", "title": "  ", "credentials": [$BASIC_AUTH]}""",
            ),
            now,
        )
        // The commit step substitutes a translated placeholder later; at plan
        // level the title is simply absent.
        assertNull(plan.items.single().request.title)
    }

    // endregion

    @Test
    fun `the importer leaves everything the commit path owns at its default`() {
        // `AddCipher` builds `ownership` from `ownership2`, which the commit step
        // fills in — an importer that pre-populated either would break it.
        val requests = requests(BASIC_AUTH, CREDIT_CARD, PERSON_NAME, SSH_KEY, NOTE)
        assertTrue(requests.isNotEmpty())
        requests.forEach { request ->
            assertNull(request.ownership, "ownership must be filled in at commit time")
            assertNull(request.ownership2, "ownership2 must be filled in at commit time")
            assertNull(request.merge, "the importer never merges into existing ciphers")
            assertTrue(request.attachments.isEmpty(), "attachments are not on the wire")
            assertEquals(CreateRequest.GpgKey(), request.gpgKey, "gpg keys are not on the wire")
            assertEquals(false, request.reprompt, "reprompt is not on the wire")
            assertEquals(now, request.now)
        }
    }
}

private fun item(
    vararg credentialsJson: String,
): String = """
    {
      "id": "aXRlbTAx",
      "title": "Item",
      "credentials": [${credentialsJson.joinToString(separator = ",")}]
    }
""".trimIndent()

private const val BASIC_AUTH =
    """{"type": "basic-auth", "username": {"fieldType": "string", "value": "alice"}}"""

private const val TOTP =
    """{"type": "totp", "secret": "JBSWY3DPEHPK3PXP", "period": 30, "digits": 6, "algorithm": "sha1"}"""

private const val CREDIT_CARD =
    """{"type": "credit-card", "number": {"fieldType": "concealed-string", "value": "4111"}}"""

private const val PERSON_NAME =
    """{"type": "person-name", "given": {"fieldType": "string", "value": "Alice"}}"""

private const val ADDRESS =
    """{"type": "address", "city": {"fieldType": "string", "value": "Springfield"}}"""

private const val SSH_KEY =
    """{"type": "ssh-key", "keyType": "ssh-ed25519", "privateKey": "AAECAwQFBg"}"""

private const val NOTE =
    """{"type": "note", "content": {"fieldType": "string", "value": "a note"}}"""

private const val CUSTOM_FIELDS = """
    {
      "type": "custom-fields",
      "fields": [{"fieldType": "string", "value": "v", "label": "Extra"}]
    }
"""

private fun passkey(
    username: String = "passkey-user",
    rpId: String = "example.com",
    credentialId: String = "AAECAwQFBg",
    key: String = CXF_TEST_PASSKEY_KEY_URL,
): String = """
    {
      "type": "passkey",
      "credentialId": "$credentialId",
      "rpId": "$rpId",
      "username": "$username",
      "userDisplayName": "Alice",
      "userHandle": "AAECAwQFBg",
      "key": "$key"
    }
""".trimIndent()

private val PASSKEY = passkey()
