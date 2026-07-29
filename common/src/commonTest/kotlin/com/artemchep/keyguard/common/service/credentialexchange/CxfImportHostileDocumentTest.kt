package com.artemchep.keyguard.common.service.credentialexchange

import com.artemchep.keyguard.common.model.create.CreateRequest
import com.artemchep.keyguard.common.service.credentialexchange.impl.CxfImportServiceImpl
import com.artemchep.keyguard.common.service.credentialexchange.impl.CXF_MAX_COLLECTION_DEPTH
import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * A CXF document comes from another application, so `parse` has to survive
 * anything: its contract is that only a malformed payload or an unsupported
 * version fails, and everything else becomes a counted skip. These are the
 * shapes that stress that promise hardest.
 */
class CxfImportHostileDocumentTest {
    private val now = Instant.parse("2024-01-30T14:09:33Z")

    private val service = CxfImportServiceImpl(
        sshKeyImportService = FakeSshKeyImportService(),
    )

    @Test
    fun `the collection walk re-roots at the depth cap`() {
        // The bound is policy, not a `StackOverflowError` guard: the JSON parse
        // and the collection decode are both strictly deeper recursions the
        // document has already survived. Past the bound a subtree becomes a new
        // top-level folder, so nothing is dropped.
        val plan = service.parseSuccessPlan(
            payload = nestedCollectionDocument(depth = CXF_MAX_COLLECTION_DEPTH + 20),
            now = now,
        )
        assertEquals(CXF_MAX_COLLECTION_DEPTH + 20, plan.folders.size)
        assertNull(plan.folders[CXF_MAX_COLLECTION_DEPTH].parentKey)
    }

    @Test
    fun `absurdly nested collections fail the parse instead of crashing`() {
        // Deep enough that the JSON reader itself gives out — measured: this
        // document shape parses and plans fine to depth 2200 and both
        // `parseToJsonElement` and `parse` fail together at 2600, so the reader
        // is the binding constraint and the collection decode never is. What
        // matters is that it stays a controlled failure: an `Error` escaping
        // here leaves the screen spinning forever.
        val result = service.parse(
            payload = nestedCollectionDocument(depth = 10_000),
            now = now,
        )
        assertIs<CxfImportResult.Failure>(result)
    }

    @Test
    fun `deep but readable nesting keeps every item`() {
        // The other side of that ceiling, and the reason it is worth pinning: a
        // chain the reader can hold must not cost the document anything. The
        // item here has nothing to do with the deep chain.
        val plan = service.parseSuccessPlan(
            payload = deeplyLinkedDocument(depth = 1_000),
            now = now,
        )
        assertEquals(1, plan.items.size)
        assertEquals(1_000, plan.folders.size)
    }

    @Test
    fun `one malformed custom field does not cost the item the others`() {
        // `CxfEditableField.value` is a non-null String with no default, so a
        // JSON number there fails the whole `fields` array in one generated
        // serializer call. CXF v1.0 3.4.2 wants the opposite: an unusable field
        // structure is treated as absent. The two good fields must survive.
        val plan = service.parseSuccessPlan(
            payload = documentWithItems(
                itemWithCredentials(
                    """
                    {
                      "type": "custom-fields",
                      "fields": [
                        {"fieldType": "string", "value": "keep-me", "label": "A"},
                        {"fieldType": "string", "value": 12345, "label": "B"},
                        {"fieldType": "string", "value": "keep-me-too", "label": "C"}
                      ]
                    }
                    """.trimIndent(),
                ),
            ),
            now = now,
        )
        val fields = plan.items.single().request.fields
        assertEquals(listOf("keep-me", "keep-me-too"), fields.map { it.value })
    }

    private class SalvageCase(
        val name: String,
        val credentialJson: String,
        val expected: Any?,
        val read: (CreateRequest) -> Any?,
    )

    /**
     * One member a producer got wrong, in each of the two shapes the retry has
     * to recognise: an object standing where an `EditableField` belongs but
     * which is not one, and a bare scalar standing where the structure belongs.
     */
    private val salvagedCredentials = listOf(
        SalvageCase(
            name = "a shorthand username does not take the password with it",
            credentialJson = """
                {
                  "type": "basic-auth",
                  "username": "alice",
                  "password": {"fieldType": "concealed-string", "value": "hunter2"}
                }
            """.trimIndent(),
            expected = "hunter2",
            read = { request -> request.login.password },
        ),
        SalvageCase(
            name = "an unusable password structure does not take the username with it",
            credentialJson = """
                {
                  "type": "basic-auth",
                  "username": {"fieldType": "string", "value": "alice"},
                  "password": {"fieldType": "concealed-string"}
                }
            """.trimIndent(),
            expected = "alice",
            read = { request -> request.login.username },
        ),
        SalvageCase(
            name = "two unusable passport members cost only themselves",
            credentialJson = """
                {
                  "type": "passport",
                  "passportNumber": {"fieldType": "concealed-string", "value": "P123456"},
                  "birthDate": {"fieldType": "date"},
                  "sex": {"value": "X"}
                }
            """.trimIndent(),
            expected = "P123456",
            read = { request -> request.identity.passportNumber },
        ),
        SalvageCase(
            name = "an unusable ssh key date does not cost the key",
            credentialJson = """
                {
                  "type": "ssh-key",
                  "keyType": "ssh-ed25519",
                  "privateKey": "AAECAwQFBg",
                  "creationDate": {"fieldType": "date"}
                }
            """.trimIndent(),
            expected = fakeSshKeyPair().privateKey.ssh,
            read = { request -> request.sshKey.privateKey },
        ),
        SalvageCase(
            name = "a numeric label on a field bag does not cost the bag",
            credentialJson = """
                {
                  "type": "custom-fields",
                  "label": 42,
                  "fields": [{"fieldType": "string", "value": "kept", "label": "Real"}]
                }
            """.trimIndent(),
            expected = listOf("Real" to "kept"),
            read = { request -> request.fields.map { it.name to it.value } },
        ),
        SalvageCase(
            name = "a numeric totp issuer does not cost the otp",
            credentialJson = """
                {
                  "type": "totp",
                  "secret": "JBSWY3DPEHPK3PXP",
                  "period": 30,
                  "digits": 6,
                  "algorithm": "sha1",
                  "issuer": 42
                }
            """.trimIndent(),
            expected = "otpauth://totp/?secret=JBSWY3DPEHPK3PXP",
            read = { request -> request.login.totp },
        ),
    )

    @Test
    fun `one malformed credential member costs only that member`() {
        // The element-wise retry is not a `custom-fields` privilege: every kind
        // gets it, because a credential is one generated serializer call and the
        // ratios are brutal — `passport` has twelve optional members, and a
        // shorthand `username` used to take a password with it.
        salvagedCredentials.forEach { case ->
            val plan = service.parseSuccessPlan(
                payload = documentWithItems(itemWithCredentials(case.credentialJson)),
                now = now,
            )
            val request = assertNotNull(plan.items.singleOrNull(), case.name).request
            assertEquals(case.expected, case.read(request), case.name)
            // A member dropped inside a surviving credential is an uncounted
            // fidelity loss by contract — see `CxfImportSilentDropTest`.
            assertEquals(cxfImportSkips(), plan.skips, case.name)
        }
    }

    @Test
    fun `a credential whose required member is unusable is still a counted skip`() {
        // The other half of the retry's contract: it may forgive an optional
        // member and never fabricates one. Requiredness is enforced by the
        // serializer — the probe simply cannot find a version that decodes.
        val credentials = listOf(
            """{"type": "note", "content": {"fieldType": "string"}}""",
            """{"type": "passkey", "credentialId": 42, "rpId": "example.com"}""",
            """{"type": "ssh-key", "keyType": {"fieldType": "string", "value": "ssh-rsa"}}""",
            """{"type": "totp", "secret": 42, "period": 30, "digits": 6, "algorithm": "sha1"}""",
        )
        credentials.forEach { credential ->
            val plan = service.parseSuccessPlan(
                payload = documentWithItems(itemWithCredentials(credential, VALID_BASIC_AUTH)),
                now = now,
            )
            assertEquals(
                cxfImportSkips(CxfImportSkipReason.UnknownCredential to 1),
                plan.skips,
                credential,
            )
        }
    }

    @Test
    fun `a credential left with nothing usable is dropped without a count`() {
        // §3.4.2 treats an unusable field structure as absent, so a credential
        // whose every member was one becomes the content-free credential the
        // format lets a producer write on purpose — and those are an established
        // uncounted drop, see `CxfImportSilentDropTest`. The card number is not
        // "lost": there was never a readable value to lose.
        val plan = service.parseSuccessPlan(
            payload = documentWithItems(
                itemWithCredentials(
                    """{"type": "credit-card", "number": {"fieldType": "concealed-string"}}""",
                    VALID_BASIC_AUTH,
                ),
            ),
            now = now,
        )
        assertEquals("alice", plan.items.single().request.login.username)
        assertEquals(cxfImportSkips(), plan.skips)
    }

    @Test
    fun `the member-wise retry stays bounded on an absurdly wide credential`() {
        // The probe costs one decode per member, so an object built with twenty
        // thousand of them must not turn the retry into a quadratic walk — which
        // is what the probe cap buys. The bad member sits *past* the cap here, so
        // the retry gives up and the credential degrades to what it was before
        // any of this leniency existed: one counted skip.
        val padding = (0 until 20_000).joinToString(separator = ",") { index ->
            """"pad$index": $index"""
        }
        val plan = service.parseSuccessPlan(
            payload = documentWithItems(
                itemWithCredentials("""{"type": "basic-auth", $padding, "username": "alice"}"""),
            ),
            now = now,
        )
        assertTrue(plan.items.isEmpty())
        assertEquals(cxfImportSkips(CxfImportSkipReason.UnknownCredential to 1), plan.skips)
    }

    @Test
    fun `a custom fields bag whose every field is malformed stays a counted skip`() {
        // Nothing survived, so admitting an empty credential would turn a real
        // loss into a silent one. It has to reach the tally instead.
        val plan = service.parseSuccessPlan(
            payload = documentWithItems(
                itemWithCredentials(
                    VALID_BASIC_AUTH,
                    """{"type": "custom-fields", "fields": [{"fieldType": "string", "value": 1}]}""",
                ),
            ),
            now = now,
        )
        assertEquals(1, plan.skips[CxfImportSkipReason.UnknownCredential])
    }

    @Test
    fun `an out of memory during planning fails the parse instead of crashing`() {
        // Another app's file can be arbitrarily large, so a blown heap here is
        // the document being hostile, not the process being broken: the parse
        // boundary must still answer, and the half-built plan dies with it. See
        // `runCatchingUntrustedInput`.
        val service = CxfImportServiceImpl(
            sshKeyImportService = FakeSshKeyImportService(error = OutOfMemoryError("heap")),
        )
        val result = service.parse(
            payload = documentWithItems(itemWithCredentials(VALID_SSH_KEY)),
            now = now,
        )
        val failure = assertIs<CxfImportResult.Failure>(result)
        assertEquals(CxfImportError.Parse, failure.error)
    }

    @Test
    fun `a cancellation during planning is not swallowed`() {
        // The other half of the guard's contract: a cancelled scope must not be
        // reported to the caller as a malformed document.
        val service = CxfImportServiceImpl(
            sshKeyImportService = FakeSshKeyImportService(error = CancellationException("cancelled")),
        )
        assertFailsWith<CancellationException> {
            service.parse(
                payload = documentWithItems(itemWithCredentials(VALID_SSH_KEY)),
                now = now,
            )
        }
    }

    @Test
    fun `a non fatal seam failure is still only a counted skip`() {
        // The complement of the two above: the boundary must not eat a whole
        // document over one bad key.
        val service = CxfImportServiceImpl(
            sshKeyImportService = FakeSshKeyImportService(error = IllegalStateException("gone")),
        )
        val plan = service.parseSuccessPlan(
            payload = documentWithItems(itemWithCredentials(VALID_SSH_KEY)),
            now = now,
        )
        assertEquals(1, plan.skips[CxfImportSkipReason.SshKey])
        assertTrue(plan.items.isEmpty())
    }

    private val hostileCredentialElements = listOf(
        """"a bare string"""",
        "42",
        "[]",
        "{}",
        """{"type": null}""",
        """{"type": "totp", "secret": 42, "period": 30, "digits": 6, "algorithm": "sha1"}""",
    )

    @Test
    fun `a credential element of any shape becomes a counted skip`() {
        // Each element is decoded on its own, so a hostile one cannot poison the
        // item — let alone the document.
        hostileCredentialElements.forEach { element ->
            val plan = service.parseSuccessPlan(
                payload = documentWithItems(itemWithCredentials(element, VALID_BASIC_AUTH)),
                now = now,
            )
            assertEquals(
                cxfImportSkips(CxfImportSkipReason.UnknownCredential to 1),
                plan.skips,
                "element: $element",
            )
        }
    }

    @Test
    fun `a document whose only credential is an all-empty passkey yields no item`() {
        // A passkey with no credential id and no private key can never produce an
        // assertion, so importing it would put a permanently unusable record in
        // the vault. It must not survive as a phantom login either — the gate is
        // post-mapping, so the `username` and the rp-id-derived uri go nowhere.
        // Counted once: the passkey reason already explains where the item went.
        val plan = service.parseSuccessPlan(
            payload = documentWithItems(itemWithCredentials(ALL_EMPTY_PASSKEY)),
            now = now,
        )
        assertTrue(plan.items.isEmpty())
        assertEquals(cxfImportSkips(CxfImportSkipReason.Passkey to 1), plan.skips)
    }

    @Test
    fun `an item shell the decoder rejects is a counted skip`() {
        val shells = listOf(
            """{"id": "aXRlbTAx", "title": "T", "creationAt": 1e400, "credentials": []}""",
            """{"id": "aXRlbTAx", "title": "T", "favorite": "yes", "credentials": []}""",
            """{"id": 42, "title": "T", "credentials": []}""",
        )
        shells.forEach { shell ->
            val plan = service.parseSuccessPlan(documentWithItems(shell), now)
            assertEquals(cxfImportSkips(CxfImportSkipReason.Item to 1), plan.skips, "shell: $shell")
        }
    }

    @Test
    fun `a malformed adornment costs only itself, never the item's credentials`() {
        // The shells above all carry an empty `credentials` array, so they say
        // nothing about what a rejected decoration costs. `decodeItem` re-admits
        // the optional members one at a time, so an ISO-8601 or fractional
        // `modifiedAt` — both common producer conventions — costs only itself.
        val adornments = listOf(
            """"modifiedAt": "2024-01-30T14:09:33Z"""",
            """"modifiedAt": 1706623773.123""",
            """"creationAt": 1e400""",
            """"favorite": "yes"""",
            """"subtitle": 5""",
            """"tags": "work"""",
            """"scope": 5""",
            """"scope": {"urls": "x"}""",
        )
        adornments.forEach { adornment ->
            val plan = service.parseSuccessPlan(
                payload = documentWithItems(
                    """
                    {
                      "id": "aXRlbTAx",
                      "title": "Bank",
                      $adornment,
                      "credentials": [$VALID_BASIC_AUTH]
                    }
                    """.trimIndent(),
                ),
                now = now,
            )
            val request = assertNotNull(plan.items.singleOrNull(), adornment).request
            assertEquals("alice", request.login.username, adornment)
            assertEquals(cxfImportSkips(), plan.skips, adornment)
        }
    }

    @Test
    fun `one malformed adornment does not cost the well-formed ones`() {
        // Element-wise, like the collection walk: the retry re-admits each
        // optional member on its own, so the bad one is the only one lost.
        val plan = service.parseSuccessPlan(
            payload = documentWithItems(
                """
                {
                  "id": "aXRlbTAx",
                  "title": "Bank",
                  "tags": "work",
                  "favorite": true,
                  "credentials": [$VALID_BASIC_AUTH]
                }
                """.trimIndent(),
            ),
            now = now,
        )
        val request = assertNotNull(plan.items.singleOrNull()).request
        assertEquals(true, request.favorite)
        assertTrue(request.tags.isEmpty())
        assertEquals(cxfImportSkips(), plan.skips)
    }

    @Test
    fun `a scope missing one of its arrays keeps the other`() {
        // §2.1.2 requires both `urls` and `androidApps` on the wire, but a
        // producer with no Android app list routinely omits the member. The
        // scope is read array by array rather than through
        // `CxfCredentialScope`, whose serializer would refuse the whole member
        // and take every uri of the item with it — autofill matches on uri, so
        // the login would import permanently unmatched and uncounted.
        val cases = listOf(
            """{"urls": ["https://example.com"]}""" to "https://example.com",
            """{"androidApps": [{"bundleId": "com.example.app"}]}""" to
                "androidapp://com.example.app",
        )
        cases.forEach { (scope, expected) ->
            val plan = service.parseSuccessPlan(
                payload = documentWithItems(
                    """
                    {
                      "id": "aXRlbTAx",
                      "title": "Bank",
                      "scope": $scope,
                      "credentials": [$VALID_BASIC_AUTH]
                    }
                    """.trimIndent(),
                ),
                now = now,
            )
            val request = assertNotNull(plan.items.singleOrNull(), scope).request
            assertEquals(listOf(expected), request.uris.map { it.uri }, scope)
            assertEquals(cxfImportSkips(), plan.skips, scope)
        }
    }

    @Test
    fun `an item missing a required member is still a counted skip`() {
        // The retry may only forgive decoration: `id` and `title` are what
        // makes an item an item, and no amount of stripping recovers them.
        val shells = listOf(
            """{"id": 42, "title": "T", "credentials": [$VALID_BASIC_AUTH]}""",
            """{"id": "aXRlbTAx", "credentials": [$VALID_BASIC_AUTH]}""",
            """{"id": "aXRlbTAx", "title": [], "credentials": [$VALID_BASIC_AUTH]}""",
        )
        shells.forEach { shell ->
            val plan = service.parseSuccessPlan(documentWithItems(shell), now)
            assertTrue(plan.items.isEmpty(), "shell: $shell")
            assertEquals(cxfImportSkips(CxfImportSkipReason.Item to 1), plan.skips, "shell: $shell")
        }
    }

    @Test
    fun `an extreme creation timestamp does not escape as an error`() {
        // `Instant.fromEpochSeconds` clamps rather than throwing, so the item
        // simply gets a saturated date; an `Error` here would strand the screen.
        val extremes = listOf("-1", "${Long.MIN_VALUE}", "${Long.MAX_VALUE}")
        extremes.forEach { seconds ->
            val plan = service.parseSuccessPlan(
                payload = documentWithItems(
                    """
                    {
                      "id": "aXRlbTAx",
                      "title": "Dated",
                      "creationAt": $seconds,
                      "credentials": [$VALID_BASIC_AUTH]
                    }
                    """.trimIndent(),
                ),
                now = now,
            )
            assertEquals(1, plan.items.size, "creationAt: $seconds")
        }
    }

    @Test
    fun `the depth cap re-roots the overflow instead of dropping it`() {
        // Exactly at the cap the whole tree still nests. One level over it, the
        // deepest collection is kept but detached; two over, both of them are —
        // the folder count tracks the document, never the cap.
        val under = service.parseSuccessPlan(
            nestedCollectionDocument(depth = CXF_MAX_COLLECTION_DEPTH),
            now,
        )
        assertEquals(CXF_MAX_COLLECTION_DEPTH, under.folders.size)
        val over = service.parseSuccessPlan(
            nestedCollectionDocument(depth = CXF_MAX_COLLECTION_DEPTH + 1),
            now,
        )
        assertEquals(CXF_MAX_COLLECTION_DEPTH + 1, over.folders.size)
        assertNull(over.folders.last().parentKey)
        val wayOver = service.parseSuccessPlan(
            nestedCollectionDocument(depth = CXF_MAX_COLLECTION_DEPTH + 2),
            now,
        )
        assertEquals(CXF_MAX_COLLECTION_DEPTH + 2, wayOver.folders.size)
    }

    @Test
    fun `collections past the depth cap keep their item links`() {
        // The deepest collection is the only one linking the item, so re-rooting
        // has to keep both the folder and its link rather than walk off the tree.
        val depth = CXF_MAX_COLLECTION_DEPTH + 5
        val plan = service.parseSuccessPlan(
            payload = deeplyLinkedDocument(depth = depth),
            now = now,
        )
        assertEquals(depth, plan.folders.size)
        assertEquals(
            plan.folders.last().key,
            assertNotNull(plan.items.single().folderKey),
        )
        // The level that crossed the cap is the re-rooted one.
        assertNull(plan.folders[CXF_MAX_COLLECTION_DEPTH].parentKey)
    }

    @Test
    fun `breadth is not capped, only depth`() {
        // The recursion guard bounds nesting; a very wide but flat tree is
        // perfectly ordinary and must import whole.
        val width = 5_000
        val siblings = (0 until width).joinToString(separator = ",") { index ->
            """{"id": "Yy$index", "title": "c$index", "items": []}"""
        }
        val plan = service.parseSuccessPlan(documentWithCollections(siblings), now)
        assertEquals(width, plan.folders.size)
    }

}

private const val VALID_BASIC_AUTH =
    """{"type": "basic-auth", "username": {"fieldType": "string", "value": "alice"}}"""

/** Well-formed enough to reach the native seam, which is where the guards are. */
private const val VALID_SSH_KEY =
    """{"type": "ssh-key", "keyType": "ssh-ed25519", "privateKey": "AAECAwQFBg"}"""

/**
 * Every required member present and every one of them degenerate — the
 * `username` and `rpId` are attacker-supplied bait for the identity fallbacks.
 */
private const val ALL_EMPTY_PASSKEY = """
    {
      "type": "passkey",
      "credentialId": "",
      "rpId": "",
      "username": "attacker",
      "userDisplayName": "Attacker",
      "userHandle": "",
      "key": ""
    }
"""

private fun itemWithCredentials(
    vararg credentialsJson: String,
): String = """
    {
      "id": "aXRlbTAx",
      "title": "Item",
      "credentials": [${credentialsJson.joinToString(separator = ",")}]
    }
""".trimIndent()

/**
 * One account whose collection tree is [depth] levels deep.
 */
private fun nestedCollectionDocument(depth: Int): String {
    val collection = buildString {
        repeat(depth) { index ->
            append("""{"id": "Yy$index", "title": "c$index", "items": [], "subCollections": [""")
        }
        repeat(depth) {
            append("]}")
        }
    }
    return documentWithAccounts(
        """
            {
              "id": "YWNjLTE",
              "username": "Alice Example",
              "email": "alice@example.com",
              "collections": [$collection],
              "items": []
            }
        """.trimIndent(),
    )
}

/**
 * A collection tree [depth] levels deep whose *deepest* collection is the only
 * one linking the document's single item — so the item's folder link survives
 * only if the walk reaches, or re-roots, that level.
 */
private fun deeplyLinkedDocument(depth: Int): String {
    val tree = buildString {
        repeat(depth) { index ->
            append("""{"id": "Yy$index", "title": "c$index", "items": [""")
            if (index == depth - 1) {
                append("""{"item": "aXRlbTAx"}""")
            }
            append("""], "subCollections": [""")
        }
        repeat(depth) {
            append("]}")
        }
    }
    return documentWithAccounts(
        """
        {
          "id": "YWNjLTE",
          "username": "Alice Example",
          "email": "alice@example.com",
          "collections": [$tree],
          "items": [
            {
              "id": "aXRlbTAx",
              "title": "Login",
              "credentials": [$VALID_BASIC_AUTH]
            }
          ]
        }
        """.trimIndent(),
    )
}
