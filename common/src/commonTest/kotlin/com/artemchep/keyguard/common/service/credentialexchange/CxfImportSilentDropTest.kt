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
 * A catalogue of everything the importer discards **without counting it**.
 *
 * `CxfImportSkips` backs the review screen's promise that nothing is dropped
 * silently, so each row here asserts both halves: the data really is gone, and
 * `plan.skips` really is empty. Every row is a deliberate decision — adding a
 * counter for one of them flips exactly one row.
 *
 * There is no exception: every row asserts an *uncounted* loss. A loss worth a
 * number belongs in `CxfImportSkipCounterMatrixTest` instead.
 */
class CxfImportSilentDropTest {
    private val now = Instant.parse("2024-01-30T14:09:33Z")

    private val service = CxfImportServiceImpl(
        sshKeyImportService = FakeSshKeyImportService(),
    )

    private fun plan(payload: String): CxfImportPlan = service.parseSuccessPlan(
        payload = payload,
        now = now,
    )

    private fun assertNothingCounted(plan: CxfImportPlan) {
        assertEquals(cxfImportSkips(), plan.skips, "a loss here is uncounted by design")
    }

    private fun singleRequest(payload: String): CreateRequest {
        val plan = plan(payload)
        assertNothingCounted(plan)
        return plan.items.single().request
    }

    // region Fields

    private val blankFieldValues = listOf("", " ")

    @Test
    fun `a custom field with a blank value is dropped`() {
        // The export side is the opposite: `mapCustomFields` keeps a field whose
        // value is "" and only drops a null one. So a field that survives the
        // export cannot necessarily survive the import.
        blankFieldValues.forEach { value ->
            val request = singleRequest(
                documentWithItems(
                    customFieldsItem(
                        """{"fieldType": "string", "value": "$value", "label": "Empty"}""",
                        """{"fieldType": "string", "value": "kept", "label": "Real"}""",
                    ),
                ),
            )
            assertEquals(
                listOf("Real"),
                request.fields.map { it.name },
                "value: <$value>",
            )
        }
    }

    // endregion

    // region Certificates

    private data class FingerprintCase(
        val name: String,
        val json: String,
    )

    private val droppedFingerprints = listOf(
        FingerprintCase(
            name = "a sha512 hash algorithm",
            json = """{"fingerprint": "${"A".repeat(86)}", "hashAlg": "sha512"}""",
        ),
        FingerprintCase(
            name = "31 bytes under a sha256 label",
            json = """{"fingerprint": "${"A".repeat(41)}Q", "hashAlg": "sha256"}""",
        ),
        FingerprintCase(
            name = "one byte under a sha256 label",
            json = """{"fingerprint": "AQ", "hashAlg": "sha256"}""",
        ),
        FingerprintCase(
            name = "a fingerprint that is not base64url",
            json = """{"fingerprint": "not base64!", "hashAlg": "sha256"}""",
        ),
    )

    @Test
    fun `a certificate the importer cannot use is dropped but its app survives`() {
        // Keyguard's own export applies the same 32-byte gate, so only a
        // foreign exporter can put a truncated or SHA-512 hash on the wire
        // under a sha256 label.
        droppedFingerprints.forEach { case ->
            val request = singleRequest(
                documentWithItems(scopedItem(androidAppsJson = androidApp(case.json))),
            )
            val uri = request.uris.single()
            assertTrue(uri.uri.endsWith("com.example.app"), case.name)
            assertTrue(uri.signatures.isEmpty(), case.name)
        }
    }

    // endregion

    // region Scope

    @Test
    fun `blank urls and blank bundle ids are dropped`() {
        val request = singleRequest(
            documentWithItems(
                scopedItem(
                    urlsJson = """"", "   ", "https://example.com"""",
                    androidAppsJson = """{"bundleId": "  "}, {"bundleId": "com.example.app"}""",
                ),
            ),
        )
        assertEquals(
            listOf("https://example.com", "androidapp://com.example.app"),
            request.uris.map { it.uri },
        )
    }

    @Test
    fun `an unreadable scope entry is dropped, and only it`() {
        // The two arrays are read element by element, so a url that is not a
        // string and an app whose `bundleId` is not one cost only themselves —
        // the same bargain the collection walk makes.
        val request = singleRequest(
            documentWithItems(
                scopedItem(
                    urlsJson = """5, "https://example.com"""",
                    androidAppsJson = """{"bundleId": 5}, {"bundleId": "com.example.app"}""",
                ),
            ),
        )
        assertEquals(
            listOf("https://example.com", "androidapp://com.example.app"),
            request.uris.map { it.uri },
        )
    }

    // endregion

    // region Collections

    @Test
    fun `a bad item link is dropped and its collection survives`() {
        // `items` is read entry by entry rather than inside the node's shell, so
        // an entry with no usable `item` id costs only its own link — the folder
        // keeps its title and its readable links. Decoded inside the shell, one
        // such entry took the whole node with it. What a lost link costs is one
        // item's placement, never the item: it imports folder-less.
        val plan = plan(
            documentWithAccounts(
                """
                {
                  "id": "YWNjLTE",
                  "username": "Alice Example",
                  "email": "alice@example.com",
                  "collections": [
                    {
                      "id": "YmFk",
                      "title": "Broken",
                      "items": [{"account": "x"}, {"item": 42}, {"item": "aXRlbTAx"}]
                    },
                    ${collection(id = "Zm9sZGVyLTI", title = "Home")}
                  ],
                  "items": [${basicAuthItem()}]
                }
                """.trimIndent(),
            ),
        )
        assertEquals(listOf("Broken", "Home"), plan.folders.map { it.title })
        // The usable link in the same array still places its item.
        assertEquals("account-0/0", plan.items.single().folderKey)
        assertNothingCounted(plan)
    }

    @Test
    fun `a collection omitting its required items member keeps its title`() {
        // §2.1.2 puts `items` on the wire even when empty, so `{"id":…,
        // "title":…}` is a producer bug — but a common one, and decoding the
        // member inside the node used to cost every such node its folder. For a
        // producer that spells every empty array that way, that was the whole
        // hierarchy of the document, counted node by node.
        val plan = plan(
            documentWithCollections(
                """{"id": "Zm9sZGVyLTE", "title": "Work"}""",
                collection(id = "Zm9sZGVyLTI", title = "Home"),
            ),
        )
        assertEquals(listOf("Work", "Home"), plan.folders.map { it.title })
        assertNothingCounted(plan)
    }

    @Test
    fun `only the first collection linking an item wins`() {
        val plan = plan(
            documentWithAccounts(
                """
                {
                  "id": "YWNjLTE",
                  "username": "Alice Example",
                  "email": "alice@example.com",
                  "collections": [
                    ${collection(id = "Zm9sZGVyLTE", title = "First", itemIds = listOf("aXRlbTAx"))},
                    ${collection(id = "Zm9sZGVyLTI", title = "Second", itemIds = listOf("aXRlbTAx"))}
                  ],
                  "items": [${basicAuthItem()}]
                }
                """.trimIndent(),
            ),
        )
        // Keyguard folders are single-valued, so the second link is unrepresentable.
        assertEquals("account-0/0", plan.items.single().folderKey)
        assertNothingCounted(plan)
    }

    // endregion

    // region Credentials that map to nothing

    @Test
    fun `a credit card that maps to an empty card is dropped`() {
        val plan = plan(
            documentWithItems(
                """
                {
                  "id": "aXRlbTAx",
                  "title": "Card",
                  "credentials": [
                    {"type": "credit-card", "expiryDate": {"fieldType": "year-month", "value": "nope"}},
                    {
                      "type": "basic-auth",
                      "username": {"fieldType": "string", "value": "alice"}
                    }
                  ]
                }
                """.trimIndent(),
            ),
        )
        val request = plan.items.single().request
        assertEquals(DSecret.Type.Login, request.type, "the card produced no request")
        assertNothingCounted(plan)
    }

    @Test
    fun `an unreadable item adornment is dropped, and only it`() {
        // `decodeItem` re-admits the optional members one at a time, so a
        // malformed decorative member — an ISO-8601 `modifiedAt`, a scalar
        // `tags` — costs only itself and the item keeps its credentials. The
        // loss is uncounted by design: `CxfImportSkipReason` excludes
        // field-level fidelity losses inside a surviving item. `scope` is no
        // longer one of these members: it is decoded outside the shell, array
        // by array, so a missing `androidApps` no longer costs the item its
        // urls. See `a scope missing one of its arrays keeps the other`.
        val request = singleRequest(
            documentWithItems(
                """
                {
                  "id": "aXRlbTAx",
                  "title": "Bank",
                  "modifiedAt": "2024-01-30T14:09:33Z",
                  "tags": "work",
                  "credentials": [
                    {
                      "type": "basic-auth",
                      "username": {"fieldType": "string", "value": "alice"},
                      "password": {"fieldType": "concealed-string", "value": "hunter2"}
                    }
                  ]
                }
                """.trimIndent(),
            ),
        )
        assertEquals("alice", request.login.username)
        assertEquals("hunter2", request.login.password)
        assertTrue(request.tags.isEmpty(), "the malformed tags member is gone")
    }

    @Test
    fun `an unreadable credential member is dropped, and only it`() {
        // The credential-level twin of the row above, and the register the
        // member-wise credential retry points at: a bare-string `username` is an
        // ordinary producer shorthand, and it now costs only itself instead of
        // taking the password with it. The wider matrix is in
        // `CxfImportHostileDocumentTest`.
        val request = singleRequest(
            documentWithItems(
                """
                {
                  "id": "aXRlbTAx",
                  "title": "Bank",
                  "credentials": [
                    {
                      "type": "basic-auth",
                      "username": "alice",
                      "password": {"fieldType": "concealed-string", "value": "hunter2"}
                    }
                  ]
                }
                """.trimIndent(),
            ),
        )
        assertEquals("hunter2", request.login.password)
        assertNull(request.login.username, "the shorthand username is gone")
    }

    @Test
    fun `a basic-auth with no members is dropped beside a real credential`() {
        // Deliberate no-counter drop: the credential carried nothing to lose.
        // `UnknownCredential` (it decoded fine) and `DuplicateCredential` (it is
        // the only basic-auth) both misdescribe it. Alone in an item it is not
        // silent — the item then yields nothing and `Item` fires; see
        // CxfImportSkipCounterMatrixTest.
        val plan = plan(
            documentWithItems(
                """
                {
                  "id": "aXRlbTAx",
                  "title": "Card",
                  "credentials": [
                    {"type": "basic-auth"},
                    {"type": "credit-card", "number": {"fieldType": "concealed-string", "value": "4111"}}
                  ]
                }
                """.trimIndent(),
            ),
        )
        val request = plan.items.single().request
        assertEquals(DSecret.Type.Card, request.type, "the basic-auth produced no request")
        assertNothingCounted(plan)
    }

    @Test
    fun `a blank note is dropped from a multi credential item`() {
        val request = singleRequest(
            documentWithItems(
                """
                {
                  "id": "aXRlbTAx",
                  "title": "Login",
                  "credentials": [
                    {
                      "type": "basic-auth",
                      "username": {"fieldType": "string", "value": "alice"}
                    },
                    {"type": "note", "content": {"fieldType": "string", "value": "   "}}
                  ]
                }
                """.trimIndent(),
            ),
        )
        assertNull(request.note)
    }

    // endregion

    // region Members with nowhere to go

    @Test
    fun `members the vault cannot hold are discarded`() {
        val request = singleRequest(
            documentWithItems(
                """
                {
                  "id": "aXRlbTAx",
                  "title": "Everything",
                  "subtitle": "a subtitle the vault has no slot for",
                  "credentials": [
                    {
                      "type": "custom-fields",
                      "id": "YmFnLTE",
                      "label": "The bag's own label",
                      "fields": [{"fieldType": "string", "value": "v", "label": "Real"}]
                    },
                    {"type": "ssh-key", "keyType": "ssh-ed25519", "privateKey": "AAECAwQFBg"}
                  ]
                }
                """.trimIndent(),
            ),
        )
        // The item's subtitle, the field bag's id and label, and the ssh key's
        // declared keyType are all read and thrown away — the key type comes
        // back from the native seam instead.
        assertEquals(listOf("Real"), request.fields.map { it.name })
        assertTrue(request.fields.none { it.name == "The bag's own label" })
    }

    @Test
    fun `blank passkey names are stored as null`() {
        val request = singleRequest(
            documentWithItems(
                """
                {
                  "id": "aXRlbTAx",
                  "title": "Passkey",
                  "credentials": [
                    {
                      "type": "passkey",
                      "credentialId": "AAECAwQFBg",
                      "rpId": "example.com",
                      "username": "  ",
                      "userDisplayName": "",
                      "userHandle": "AAECAwQFBg",
                      "key": "$CXF_TEST_PASSKEY_KEY_URL"
                    }
                  ]
                }
                """.trimIndent(),
            ),
        )
        val passkey = request.fido2Credentials.single()
        assertNull(passkey.userName)
        assertNull(passkey.userDisplayName)
    }

    @Test
    fun `blank tags pass through unfiltered`() {
        // Every other member is blank-checked; tags are not. Pinned as the odd
        // one out so a future clean-up is a deliberate change.
        val request = singleRequest(
            documentWithItems(
                """
                {
                  "id": "aXRlbTAx",
                  "title": "Tagged",
                  "tags": ["work", "", "  "],
                  "credentials": [
                    {
                      "type": "basic-auth",
                      "username": {"fieldType": "string", "value": "alice"}
                    }
                  ]
                }
                """.trimIndent(),
            ),
        )
        assertEquals(listOf("work", "", "  "), request.tags)
    }

    // endregion

    // region Item ids

    @Test
    fun `two items sharing an id both import and share a folder`() {
        val plan = plan(
            documentWithAccounts(
                """
                {
                  "id": "YWNjLTE",
                  "username": "Alice Example",
                  "email": "alice@example.com",
                  "collections": [
                    ${collection(id = "Zm9sZGVyLTE", title = "Work", itemIds = listOf("aXRlbTAx"))}
                  ],
                  "items": [
                    ${basicAuthItem(username = "first")},
                    ${basicAuthItem(username = "second")}
                  ]
                }
                """.trimIndent(),
            ),
        )
        // Item ids are the exporter's, not ours, so a duplicate is not an error
        // — but both items then resolve to the same folder link.
        assertEquals(2, plan.items.size)
        assertTrue(plan.items.all { it.folderKey == "account-0/0" })
        assertNothingCounted(plan)
    }

    // endregion
}

private fun androidApp(
    certificateJson: String,
): String = """{"bundleId": "com.example.app", "certificate": $certificateJson}"""

private fun scopedItem(
    urlsJson: String = "",
    androidAppsJson: String = "",
): String = """
    {
      "id": "aXRlbTAx",
      "title": "Scoped",
      "scope": {"urls": [$urlsJson], "androidApps": [$androidAppsJson]},
      "credentials": [
        {
          "type": "basic-auth",
          "username": {"fieldType": "string", "value": "alice"}
        }
      ]
    }
""".trimIndent()

private fun customFieldsItem(
    vararg fieldsJson: String,
): String = """
    {
      "id": "aXRlbTAx",
      "title": "Fields",
      "credentials": [
        {
          "type": "custom-fields",
          "fields": [${fieldsJson.joinToString(separator = ",")}]
        }
      ]
    }
""".trimIndent()

private fun basicAuthItem(
    id: String = "aXRlbTAx",
    username: String = "alice",
): String = """
    {
      "id": "$id",
      "title": "Login",
      "credentials": [
        {
          "type": "basic-auth",
          "username": {"fieldType": "string", "value": "$username"}
        }
      ]
    }
""".trimIndent()

private fun collection(
    id: String,
    title: String,
    itemIds: List<String> = emptyList(),
): String {
    val items = itemIds.joinToString(separator = ",") { """{"item": "$it"}""" }
    return """{"id": "$id", "title": "$title", "items": [$items]}"""
}
