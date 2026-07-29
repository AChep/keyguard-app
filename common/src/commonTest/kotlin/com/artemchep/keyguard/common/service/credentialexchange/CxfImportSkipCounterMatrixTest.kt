package com.artemchep.keyguard.common.service.credentialexchange

import com.artemchep.keyguard.common.service.credentialexchange.impl.CxfImportServiceImpl
import com.artemchep.keyguard.common.service.credentialexchange.impl.MAX_SSH_PRIVATE_KEY_B64_LENGTH
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Every [CxfImportSkipReason], and the exact input that increments it.
 *
 * Each row asserts the **whole** [CxfImportSkips] tally rather than one reason,
 * so a cause cannot leak into a neighbouring reason unnoticed — a passkey
 * failure that started also raising [CxfImportSkipReason.Item] would fail here
 * even though both numbers are individually plausible.
 *
 * The complement lives in `CxfImportSilentDropTest`: everything that is dropped
 * with *no* count at all.
 */
class CxfImportSkipCounterMatrixTest {
    private val now = Instant.parse("2024-01-30T14:09:33Z")

    private fun skips(
        payload: String,
        service: CxfImportServiceImpl = CxfImportServiceImpl(FakeSshKeyImportService()),
    ): CxfImportSkips = service.parseSuccessPlan(payload = payload, now = now).skips

    private fun assertCases(cases: List<SkipCase>) {
        cases.forEach { case ->
            assertEquals(case.expected, skips(documentWithItems(case.itemsJson)), case.name)
        }
    }

    @Test
    fun `each loss is attributed to the item it came from`() {
        // The review screen expands a warning row into the items behind it. The
        // sharp edge is that titling is applied to a *running accumulator* here:
        // attributing the fold rather than each item's own tally would relabel
        // every earlier item as the last one, which two differently-named items
        // is the smallest case that catches.
        val document = documentWithItems(
            """
                {
                  "id": "aXRlbTAx",
                  "title": "Netflix",
                  "credentials": [${passkey(key = "###")}, ${passkey(key = "###")}]
                }
            """.trimIndent(),
            """
                {
                  "id": "aXRlbTAy",
                  "title": "GitHub",
                  "credentials": [${passkey(key = "###")}]
                }
            """.trimIndent(),
        )
        val skips = skips(document)
        assertEquals(cxfImportSkips(CxfImportSkipReason.Passkey to 3), skips)
        assertEquals(
            mapOf("Netflix" to 2, "GitHub" to 1),
            skips.titlesOf(CxfImportSkipReason.Passkey),
        )
    }

    // region Passkeys

    @Test
    fun `the passkey counter fires per credential`() {
        assertCases(passkeyCases)
    }

    @Test
    fun `the passkey counter fires for a degenerate member too`() {
        assertCases(degeneratePasskeyCases)
    }

    @Test
    fun `a passkey only item whose passkey fails yields no request`() {
        // The gate is evaluated *after* mapping, so an item whose only
        // credential could not be imported does not survive as a
        // credential-less login carrying the failed passkey's username and
        // rp-id-derived uri. Counted once: the passkey reason already explains
        // where the item went.
        val plan = CxfImportServiceImpl(FakeSshKeyImportService())
            .parseSuccessPlan(documentWithItems(itemWith(passkey(key = "###"))), now)
        assertEquals(cxfImportSkips(CxfImportSkipReason.Passkey to 1), plan.skips)
        assertTrue(plan.items.isEmpty())
    }

    // endregion

    // region One-time passwords

    @Test
    fun `the otp counter fires only when the uri cannot be rebuilt`() {
        assertCases(otpCases)
    }

    @Test
    fun `a totp only item whose totp fails yields no request`() {
        // The totp analogue of the passkey-only item: the rows above all pair a
        // bad totp with a surviving basic-auth.
        val plan = CxfImportServiceImpl(FakeSshKeyImportService())
            .parseSuccessPlan(documentWithItems(itemWith(totp(secret = ""))), now)
        assertEquals(cxfImportSkips(CxfImportSkipReason.Otp to 1), plan.skips)
        assertTrue(plan.items.isEmpty())
    }

    // endregion

    // region SSH keys

    @Test
    fun `the ssh key counter covers every failure of the seam`() {
        val oversized = "A".repeat(MAX_SSH_PRIVATE_KEY_B64_LENGTH + 1)
        val atLimit = "A".repeat(MAX_SSH_PRIVATE_KEY_B64_LENGTH)
        // The item is emptied by the failing key, and the key is already
        // counted — so the loss is reported once, not twice.
        val sshKeyOnly = cxfImportSkips(CxfImportSkipReason.SshKey to 1)

        assertEquals(
            sshKeyOnly,
            skips(documentWithItems(itemWith(sshKey(privateKey = oversized)))),
            "one past the size cap",
        )
        assertEquals(
            cxfImportSkips(),
            skips(documentWithItems(itemWith(sshKey(privateKey = atLimit)))),
            "exactly at the size cap",
        )
        assertEquals(
            sshKeyOnly,
            skips(documentWithItems(itemWith(sshKey(privateKey = "not base64url!")))),
            "a private key that is not base64url",
        )
        assertEquals(
            sshKeyOnly,
            skips(
                payload = documentWithItems(itemWith(sshKey())),
                service = CxfImportServiceImpl(FakeSshKeyImportService(sshKeyImportFailure())),
            ),
            "the seam returned an error",
        )
        assertEquals(
            sshKeyOnly,
            skips(
                payload = documentWithItems(itemWith(sshKey())),
                service = CxfImportServiceImpl(
                    FakeSshKeyImportService(sshKeyImportNeedsPassphrase()),
                ),
            ),
            "the seam needs a passphrase we do not have",
        )
        assertEquals(
            sshKeyOnly,
            skips(
                payload = documentWithItems(itemWith(sshKey())),
                service = CxfImportServiceImpl(
                    // The real seam raises rather than returning an error result
                    // on an oversized key or an unavailable backend.
                    FakeSshKeyImportService(error = IllegalStateException("native backend gone")),
                ),
            ),
            "the seam threw",
        )
    }

    // endregion

    // region Unknown and duplicate credentials

    @Test
    fun `a second credential of a single instance kind is counted`() {
        singleInstanceKinds.forEach { (kind, json) ->
            assertEquals(
                cxfImportSkips(CxfImportSkipReason.DuplicateCredential to 1),
                skips(documentWithItems(itemWith(json, json))),
                kind,
            )
        }
    }

    @Test
    fun `a duplicate does not explain away an item that yielded nothing`() {
        // A duplicate is discarded because a sibling was KEPT, so it can never
        // be the reason the item came out empty. If it were allowed to suppress
        // `Item`, adding a second copy of a credential would silence the warning
        // the single-copy item raises.
        val blank = basicAuth(username = "   ")
        assertEquals(
            cxfImportSkips(CxfImportSkipReason.Item to 1),
            skips(documentWithItems(itemWith(blank))),
            "the single-copy item is the baseline",
        )
        assertEquals(
            cxfImportSkips(
                CxfImportSkipReason.DuplicateCredential to 1,
                CxfImportSkipReason.Item to 1,
            ),
            skips(documentWithItems(itemWith(blank, blank))),
            "adding a duplicate must not remove the item count",
        )
    }

    @Test
    fun `passkeys and custom fields are never duplicates`() {
        // Both kinds are deliberately many-per-item: every passkey is kept, and
        // several field bags concatenate.
        assertEquals(
            cxfImportSkips(),
            skips(
                documentWithItems(
                    itemWith(passkey(credentialId = "AAECAwQFBg"), passkey(credentialId = "BgUEAwIBAA")),
                ),
            ),
            "two passkeys",
        )
        assertEquals(
            cxfImportSkips(),
            skips(documentWithItems(itemWith(customFields("A"), customFields("B")))),
            "two custom-field bags",
        )
    }

    @Test
    fun `the unknown credential counter covers undecodable elements`() {
        assertCases(unknownCases)
    }

    /**
     * The import mirror of `CxfExportSkipAccountingTest`'s non-double-count
     * rule: an item emptied by a credential that is already counted is not
     * counted again, whichever layer rejected the credential.
     */
    @Test
    fun `an item emptied by a counted credential is not counted twice`() {
        val cases = listOf(
            Triple(
                "a passkey the decoder rejects",
                itemWith("""{"type": "passkey", "credentialId": "AAECAwQFBg"}"""),
                cxfImportSkips(CxfImportSkipReason.UnknownCredential to 1),
            ),
            Triple(
                "an unrepresentable totp",
                itemWith(totp(digits = 10)),
                cxfImportSkips(CxfImportSkipReason.Otp to 1),
            ),
        )
        cases.forEach { (name, itemsJson, expected) ->
            assertEquals(expected, skips(documentWithItems(itemsJson)), name)
        }
    }

    @Test
    fun `a surviving item does not suppress a sibling's item count`() {
        // The suppression is per item, not per account.
        assertEquals(
            cxfImportSkips(
                CxfImportSkipReason.UnknownCredential to 1,
                CxfImportSkipReason.Item to 1,
            ),
            skips(
                documentWithItems(
                    itemWith("""{"type": "wifi", "ssid": {"fieldType": "string", "value": "g"}}"""),
                    """{"id": "aXRlbTAy", "title": "Empty", "credentials": []}""",
                ),
            ),
        )
    }

    // endregion

    // region Items and accounts

    @Test
    fun `the item counter covers every item that yields nothing`() {
        assertCases(itemCases)
    }

    @Test
    fun `an items member counts the same whether it is absent, null, or not an array`() {
        // CXF v1.0 §2.1.2 requires a required array to be present even when
        // empty, so `[]` is the only conforming spelling of an account with no
        // items; absent, null and non-array are one violation with one policy —
        // the account decoded, but an unknown number of its items is unreachable.
        val unread = cxfImportSkips(CxfImportSkipReason.Account to 1)
        val cases = listOf(
            Triple("an entry that is not an object", """"not-an-account"""", unread),
            Triple("an items member that is not an array", accountWithItems("""{"id": "x"}"""), unread),
            Triple("a null items member", accountWithItems("null"), unread),
            Triple("an absent items member", accountWithoutItems(), unread),
            Triple("a string items member", accountWithItems(""""nope""""), unread),
            Triple("a numeric items member", accountWithItems("0"), unread),
            Triple("a boolean items member", accountWithItems("false"), unread),
            Triple("an empty items array", accountWithItems("[]"), cxfImportSkips()),
        )
        cases.forEach { (name, accountJson, expected) ->
            assertEquals(expected, skips(documentWithAccounts(accountJson)), name)
        }
    }

    @Test
    fun `account skips do not hide the accounts that did read`() {
        val plan = CxfImportServiceImpl(FakeSshKeyImportService()).parseSuccessPlan(
            payload = documentWithAccounts(
                """"not-an-account"""",
                accountWithItems("""{"id": "x"}"""),
                accountWithoutItems(),
                // A readable account with no items at all: it adds no warning,
                // and it still counts as a source account.
                accountWithItems("[]"),
                """
                {
                  "id": "YWNjLTM",
                  "username": "Alice Example",
                  "email": "alice@example.com",
                  "collections": [],
                  "items": [${itemWith(basicAuth())}]
                }
                """.trimIndent(),
            ),
            now = now,
        )
        // The `?: return` exits `parseAccount`, not the loop in `buildPlan`.
        assertEquals(cxfImportSkips(CxfImportSkipReason.Account to 3), plan.skips)
        // Only object entries are counted as source accounts, and the readable
        // ones still contributed their items.
        assertEquals(4, plan.sourceAccountCount)
        assertEquals(1, plan.items.size)
    }

    @Test
    fun `folders survive an unread account`() {
        // `folders +=` happens before the unread-account return, and a readable
        // folder set on an unreadable account is not a collection loss.
        val plan = CxfImportServiceImpl(FakeSshKeyImportService()).parseSuccessPlan(
            payload = documentWithAccounts(
                """
                {
                  "id": "YWNjLTE",
                  "username": "Alice Example",
                  "email": "alice@example.com",
                  "collections": [${goodCollection("Zm9sZGVyLTE", "Work")}]
                }
                """.trimIndent(),
            ),
            now = now,
        )
        assertEquals(listOf("Work"), plan.folders.map { it.title })
        assertEquals(1, plan.skips[CxfImportSkipReason.Account])
        assertEquals(0, plan.skips[CxfImportSkipReason.Collection])
    }

    // endregion

    // region Collections

    @Test
    fun `one malformed sibling costs only itself`() {
        val plan = CxfImportServiceImpl(FakeSshKeyImportService()).parseSuccessPlan(
            payload = documentWithAccounts(
                accountWithCollections(
                    """
                    [
                      ${goodCollection("Zm9sZGVyLTE", "Work")},
                      ${goodCollection("Zm9sZGVyLTI", "Home")},
                      ${goodCollection("Zm9sZGVyLTM", "Archive")},
                      {"id": "YmFk", "title": 42, "items": []}
                    ]
                    """.trimIndent(),
                ),
            ),
            now = now,
        )
        assertEquals(listOf("Work", "Home", "Archive"), plan.folders.map { it.title })
        assertEquals(1, plan.skips[CxfImportSkipReason.Collection])
        assertEquals(1, plan.skips.totalCount)
    }

    @Test
    fun `a malformed child costs only itself and its parent survives`() {
        val plan = collectionPlan(
            """
            [
              {
                "id": "Zm9sZGVyLTE",
                "title": "Work",
                "items": [],
                "subCollections": [{"id": "YmFk", "title": 42, "items": []}]
              }
            ]
            """.trimIndent(),
        )
        assertEquals(listOf("Work"), plan.folders.map { it.title })
        assertEquals(1, plan.skips[CxfImportSkipReason.Collection])
    }

    @Test
    fun `a malformed node is replaced by its children`() {
        val plan = collectionPlan(
            """
            [
              {
                "id": "YmFk",
                "title": 42,
                "items": [],
                "subCollections": [
                  ${goodCollection("Zm9sZGVyLTE", "Kept One")},
                  ${goodCollection("Zm9sZGVyLTI", "Kept Two")}
                ]
              }
            ]
            """.trimIndent(),
        )
        // Re-rooted, not dropped: the tree loses one level, not a subtree.
        assertEquals(listOf("Kept One", "Kept Two"), plan.folders.map { it.title })
        assertTrue(plan.folders.all { it.parentKey == null })
        assertEquals(1, plan.skips[CxfImportSkipReason.Collection])
    }

    @Test
    fun `a bad node does not touch a good sibling subtree`() {
        val plan = collectionPlan(
            """
            [
              {
                "id": "Zm9sZGVyLTE",
                "title": "Good",
                "items": [],
                "subCollections": [
                  ${goodCollection("Zm9sZGVyLTI", "Child One")},
                  ${goodCollection("Zm9sZGVyLTM", "Child Two")}
                ]
              },
              {"id": "YmFk", "title": 42, "items": []}
            ]
            """.trimIndent(),
        )
        assertEquals(
            listOf("Good", "Child One", "Child Two"),
            plan.folders.map { it.title },
        )
        assertEquals(1, plan.skips[CxfImportSkipReason.Collection])
    }

    @Test
    fun `a dropped collection releases its item links and the items still import`() {
        val plan = CxfImportServiceImpl(FakeSshKeyImportService()).parseSuccessPlan(
            payload = documentWithAccount(
                collectionsJson = """
                    [
                      {"id": "YmFk", "title": 42, "items": [{"item": "aXRlbTAx"}]},
                      ${goodCollection("Zm9sZGVyLTI", "Kept", itemId = "aXRlbTAy")}
                    ]
                """.trimIndent(),
                itemsJson = """
                    [
                      ${itemWith(basicAuth())},
                      ${secondItemWith(basicAuth("bob"))}
                    ]
                """.trimIndent(),
            ),
            now = now,
        )
        // We lost organization, not data.
        assertEquals(2, plan.items.size)
        assertEquals(null, plan.items[0].folderKey)
        assertTrue(plan.items[1].folderKey != null)
        assertEquals(1, plan.skips[CxfImportSkipReason.Collection])
    }

    @Test
    fun `collection entries that are not objects are counted one by one`() {
        val plan = collectionPlan(
            """[${goodCollection("Zm9sZGVyLTE", "Work")}, 42, "x", null]""",
        )
        assertEquals(listOf("Work"), plan.folders.map { it.title })
        assertEquals(3, plan.skips[CxfImportSkipReason.Collection])
    }

    @Test
    fun `a collection missing its required items member is not a loss at all`() {
        // `items` is read outside the node's shell, so an omitted array is read
        // as "no links" rather than costing the folder. It used to cost one
        // `Collection` count per node — for a producer that omits every empty
        // required array, the whole hierarchy of the document.
        val plan = collectionPlan(
            """[{"id": "Zm9sZGVyLTE", "title": "T"}, ${goodCollection("Zm9sZGVyLTI", "Work")}]""",
        )
        assertEquals(listOf("T", "Work"), plan.folders.map { it.title })
        assertEquals(cxfImportSkips(), plan.skips)
    }

    @Test
    fun `an items member that is not an array still costs only its links`() {
        // The node reads fine; only its links are unreachable. Unlike an
        // unreadable `subCollections`, which hides an unknown number of folders
        // and costs its node a count, a folder with no links is an ordinary
        // folder — the items it would have held import folder-less, the same
        // uncounted organization loss `CxfImportSilentDropTest` registers for a
        // single unreadable link.
        val plan = collectionPlan(
            """[{"id": "Zm9sZGVyLTE", "title": "Work", "items": "nope"}]""",
        )
        assertEquals(listOf("Work"), plan.folders.map { it.title })
        assertEquals(cxfImportSkips(), plan.skips)
    }

    @Test
    fun `an unreadable collections member counts once however it is spelled`() {
        val unread = cxfImportSkips(CxfImportSkipReason.Collection to 1)
        val cases = listOf(
            Triple("a string", accountWithCollections(""""garbage""""), unread),
            Triple("null", accountWithCollections("null"), unread),
            Triple("an object", accountWithCollections("{}"), unread),
            Triple("a number", accountWithCollections("42"), unread),
            Triple("an absent member", accountWithoutCollections(), unread),
            Triple("an empty array", accountWithCollections("[]"), cxfImportSkips()),
        )
        cases.forEach { (name, accountJson, expected) ->
            assertEquals(expected, skips(documentWithAccounts(accountJson)), name)
        }
    }

    @Test
    fun `subCollections that is present but not an array costs one node`() {
        val notAnArray = collectionPlan(
            """[{"id": "Zm9sZGVyLTE", "title": "Work", "items": [], "subCollections": "nope"}]""",
        )
        assertEquals(listOf("Work"), notAnArray.folders.map { it.title })
        assertEquals(1, notAnArray.skips[CxfImportSkipReason.Collection])
        // `subCollections` is optional and MUST NOT be present when empty, so an
        // explicit null is how an exporter spells "no children" — never a loss.
        val explicitNull = collectionPlan(
            """[{"id": "Zm9sZGVyLTE", "title": "Work", "items": [], "subCollections": null}]""",
        )
        assertEquals(listOf("Work"), explicitNull.folders.map { it.title })
        assertEquals(cxfImportSkips(), explicitNull.skips)
    }

    @Test
    fun `every counter adds up`() {
        val plan = CxfImportServiceImpl(FakeSshKeyImportService()).parseSuccessPlan(
            payload = documentWithAccounts(
                """"not-an-account"""",
                accountWithCollections(
                    collectionsJson = """[{"id": "YmFk", "title": 42, "items": []}]""",
                    itemsJson = """[{"id": "aXRlbTAx", "title": "Empty", "credentials": []}]""",
                ),
            ),
            now = now,
        )
        assertEquals(1, plan.skips[CxfImportSkipReason.Item])
        assertEquals(1, plan.skips[CxfImportSkipReason.Collection])
        assertEquals(1, plan.skips[CxfImportSkipReason.Account])
        assertEquals(3, plan.skips.totalCount)
    }

    private fun collectionPlan(collectionsJson: String): CxfImportPlan =
        CxfImportServiceImpl(FakeSshKeyImportService()).parseSuccessPlan(
            payload = documentWithAccounts(accountWithCollections(collectionsJson)),
            now = now,
        )

    // endregion
}

private val passkeyCases = listOf(
    SkipCase(
        name = "an undecodable credential id",
        itemsJson = itemWith(passkey(credentialId = "###"), basicAuth()),
        expected = cxfImportSkips(CxfImportSkipReason.Passkey to 1),
    ),
    SkipCase(
        name = "an undecodable user handle",
        itemsJson = itemWith(passkey(userHandle = "###"), basicAuth()),
        expected = cxfImportSkips(CxfImportSkipReason.Passkey to 1),
    ),
    SkipCase(
        name = "an undecodable key",
        itemsJson = itemWith(passkey(key = "###"), basicAuth()),
        expected = cxfImportSkips(CxfImportSkipReason.Passkey to 1),
    ),
    SkipCase(
        name = "two bad passkeys count twice",
        itemsJson = itemWith(passkey(key = "###"), passkey(userHandle = "###"), basicAuth()),
        expected = cxfImportSkips(CxfImportSkipReason.Passkey to 2),
    ),
    SkipCase(
        name = "a padded user handle is accepted",
        itemsJson = itemWith(passkey(userHandle = "AAECAwQFBg=="), basicAuth()),
        expected = cxfImportSkips(),
    ),
)

private val otpCases = listOf(
    SkipCase(
        name = "a blank secret",
        itemsJson = itemWith(totp(secret = ""), basicAuth()),
        expected = cxfImportSkips(CxfImportSkipReason.Otp to 1),
    ),
    SkipCase(
        name = "a whitespace secret",
        itemsJson = itemWith(totp(secret = "   "), basicAuth()),
        expected = cxfImportSkips(CxfImportSkipReason.Otp to 1),
    ),
    SkipCase(
        name = "ten digits is past what the parser accepts",
        itemsJson = itemWith(totp(digits = 10), basicAuth()),
        expected = cxfImportSkips(CxfImportSkipReason.Otp to 1),
    ),
    SkipCase(
        name = "zero digits is past what the parser accepts",
        itemsJson = itemWith(totp(digits = 0), basicAuth()),
        expected = cxfImportSkips(CxfImportSkipReason.Otp to 1),
    ),
    SkipCase(
        name = "nine digits is accepted",
        itemsJson = itemWith(totp(digits = 9), basicAuth()),
        expected = cxfImportSkips(),
    ),
    SkipCase(
        // The parser would substitute 30, so refusing the credential is
        // the only way the loss reaches the review screen.
        name = "a non-positive period is a skip",
        itemsJson = itemWith(totp(period = 0), basicAuth()),
        expected = cxfImportSkips(CxfImportSkipReason.Otp to 1),
    ),
    SkipCase(
        name = "a negative period is refused",
        itemsJson = itemWith(totp(period = -1), basicAuth()),
        expected = cxfImportSkips(CxfImportSkipReason.Otp to 1),
    ),
    SkipCase(
        // Both directions run the same `canonicalTotpSecretOrNull`.
        name = "a secret that is not base32 is a skip",
        itemsJson = itemWith(totp(secret = "not base32!"), basicAuth()),
        expected = cxfImportSkips(CxfImportSkipReason.Otp to 1),
    ),
    SkipCase(
        // §3.3.16 makes ignoring an unnamed algorithm a MUST. A
        // wrong-algorithm TOTP renders a plausible code the relying party
        // rejects, which is worse than a credential the user is told about.
        name = "md5 is refused, never downgraded to sha1",
        itemsJson = itemWith(totp(algorithm = "md5"), basicAuth()),
        expected = cxfImportSkips(CxfImportSkipReason.Otp to 1),
    ),
    SkipCase(
        name = "an algorithm the format does not name is refused",
        itemsJson = itemWith(totp(algorithm = "sha3-256"), basicAuth()),
        expected = cxfImportSkips(CxfImportSkipReason.Otp to 1),
    ),
    SkipCase(
        name = "an empty algorithm is refused",
        itemsJson = itemWith(totp(algorithm = ""), basicAuth()),
        expected = cxfImportSkips(CxfImportSkipReason.Otp to 1),
    ),
    SkipCase(
        name = "a whitespace-only algorithm is refused",
        itemsJson = itemWith(totp(algorithm = "  "), basicAuth()),
        expected = cxfImportSkips(CxfImportSkipReason.Otp to 1),
    ),
    SkipCase(
        name = "a cased steam marker is the steam extension",
        itemsJson = itemWith(totp(algorithm = "STEAM"), basicAuth()),
        expected = cxfImportSkips(),
    ),
    SkipCase(
        name = "a hyphen-grouped secret is canonicalized, not refused",
        itemsJson = itemWith(totp(secret = "JBSW-Y3DP-EHPK-3PXP"), basicAuth()),
        expected = cxfImportSkips(),
    ),
    SkipCase(
        // A six-character remainder is not producible by any byte
        // sequence, yet it still decodes to usable bytes — a working
        // credential rather than a malformed one.
        name = "a secret of an impossible base32 length is accepted",
        itemsJson = itemWith(totp(secret = "ABCDEF"), basicAuth()),
        expected = cxfImportSkips(),
    ),
)

/**
 * The kinds the combination rules allow only one of. A second occurrence is
 * ignored and counted.
 */
private val singleInstanceKinds = listOf(
    "basic-auth" to basicAuth(),
    "totp" to totp(),
    "credit-card" to concealedCredential("credit-card", "number", "4111"),
    "note" to """{"type": "note", "content": {"fieldType": "string", "value": "n"}}""",
    "ssh-key" to sshKey(),
    "person-name" to """{"type": "person-name", "given": {"fieldType": "string", "value": "Alice"}}""",
    "address" to """{"type": "address", "city": {"fieldType": "string", "value": "Springfield"}}""",
    "passport" to concealedCredential("passport", "passportNumber", "P1"),
    "drivers-license" to concealedCredential("drivers-license", "licenseNumber", "D1"),
    "identity-document" to concealedCredential("identity-document", "documentNumber", "I1"),
)

private val unknownCases = listOf(
    SkipCase(
        name = "an unmodeled kind beside a real one",
        itemsJson = itemWith("""{"type": "wifi", "ssid": {"fieldType": "string", "value": "g"}}""", basicAuth()),
        expected = cxfImportSkips(CxfImportSkipReason.UnknownCredential to 1),
    ),
    SkipCase(
        // The credential reason already explains the empty item — the
        // exporter's rule, applied to the importer.
        name = "an item holding only an unmodeled kind counts once",
        itemsJson = itemWith("""{"type": "wifi", "ssid": {"fieldType": "string", "value": "g"}}"""),
        expected = cxfImportSkips(CxfImportSkipReason.UnknownCredential to 1),
    ),
    SkipCase(
        name = "a known kind missing a required member",
        itemsJson = itemWith("""{"type": "note"}""", basicAuth()),
        expected = cxfImportSkips(CxfImportSkipReason.UnknownCredential to 1),
    ),
)

private val itemCases = listOf(
    SkipCase(
        name = "an element that is not an object",
        itemsJson = """"not-an-item"""",
        expected = cxfImportSkips(CxfImportSkipReason.Item to 1),
    ),
    SkipCase(
        name = "an item shell missing its title",
        itemsJson = """{"id": "aXRlbTAx", "credentials": []}""",
        expected = cxfImportSkips(CxfImportSkipReason.Item to 1),
    ),
    SkipCase(
        name = "an empty credentials array",
        itemsJson = """{"id": "aXRlbTAx", "title": "Empty", "credentials": []}""",
        expected = cxfImportSkips(CxfImportSkipReason.Item to 1),
    ),
    SkipCase(
        // The no-counter path: a non-array member yields zero credentials
        // without any of them being reported as undecodable.
        name = "a credentials member that is not an array",
        itemsJson = """{"id": "aXRlbTAx", "title": "Broken", "credentials": {}}""",
        expected = cxfImportSkips(CxfImportSkipReason.Item to 1),
    ),
    SkipCase(
        // Decodes fine and maps to nothing, so the post-mapping login gate is
        // what keeps it from becoming a wholly empty Login.
        name = "a basic-auth with no members",
        itemsJson = itemWith("""{"type": "basic-auth"}"""),
        expected = cxfImportSkips(CxfImportSkipReason.Item to 1),
    ),
    SkipCase(
        name = "a basic-auth whose only member is blank",
        itemsJson = itemWith(basicAuth(username = "   ")),
        expected = cxfImportSkips(CxfImportSkipReason.Item to 1),
    ),
    SkipCase(
        // The identity analogue of the two rows above: every member of the five
        // identity-shaped kinds is optional, so this decodes cleanly and maps to
        // a wholly empty identity, which the identity gate must refuse rather
        // than let it materialise a blank record and suppress this counter.
        name = "an identity-shaped credential with no members",
        itemsJson = itemWith("""{"type": "person-name"}"""),
        expected = cxfImportSkips(CxfImportSkipReason.Item to 1),
    ),
    SkipCase(
        name = "an identity-shaped credential whose only member is blank",
        itemsJson = itemWith(
            """{"type": "address", "city": {"fieldType": "string", "value": "   "}}""",
        ),
        expected = cxfImportSkips(CxfImportSkipReason.Item to 1),
    ),
    SkipCase(
        // An item shell that decodes only after a malformed decorative member is
        // dropped, and then holds nothing: `Item` still fires, for the emptiness
        // rather than for the adornment.
        name = "an item whose adornment is malformed and which holds nothing",
        itemsJson = """{"id": "aXRlbTAx", "title": "T", "favorite": "yes", "credentials": []}""",
        expected = cxfImportSkips(CxfImportSkipReason.Item to 1),
    ),
)

private data class SkipCase(
    val name: String,
    val itemsJson: String,
    val expected: CxfImportSkips,
)

/**
 * Passkeys whose required members decode cleanly but to nothing: without the
 * gate each becomes a vault record that can never produce an assertion.
 *
 * The empty user handle is the one exception. CXF gives a producer no way to say
 * "this credential has no user handle", so one in that bind writes `""`, and
 * refusing it would destroy a valid credential id, PKCS#8 key and rp id over a
 * member nothing on the assertion path reads. The `"###"` rows above still
 * count: undecodable is a failure, empty is an absence.
 */
private val degeneratePasskeyCases = listOf(
    SkipCase(
        name = "an empty credential id",
        itemsJson = itemWith(passkey(credentialId = ""), basicAuth()),
        expected = cxfImportSkips(CxfImportSkipReason.Passkey to 1),
    ),
    SkipCase(
        name = "an empty key",
        itemsJson = itemWith(passkey(key = ""), basicAuth()),
        expected = cxfImportSkips(CxfImportSkipReason.Passkey to 1),
    ),
    SkipCase(
        name = "an empty rp id",
        itemsJson = itemWith(passkey(rpId = ""), basicAuth()),
        expected = cxfImportSkips(CxfImportSkipReason.Passkey to 1),
    ),
    SkipCase(
        name = "a whitespace-only rp id",
        itemsJson = itemWith(passkey(rpId = "   "), basicAuth()),
        expected = cxfImportSkips(CxfImportSkipReason.Passkey to 1),
    ),
    SkipCase(
        name = "an empty user handle is absence, not a failure",
        itemsJson = itemWith(passkey(userHandle = ""), basicAuth()),
        expected = cxfImportSkips(),
    ),
)

private fun itemWith(
    vararg credentialsJson: String,
): String = """
    {
      "id": "aXRlbTAx",
      "title": "Item",
      "credentials": [${credentialsJson.joinToString(separator = ",")}]
    }
""".trimIndent()

private fun basicAuth(
    username: String = "alice",
): String =
    """{"type": "basic-auth", "username": {"fieldType": "string", "value": "$username"}}"""

/**
 * A credential whose only member is one concealed string — enough to decode and
 * to be a distinct instance of its kind.
 */
private fun concealedCredential(
    type: String,
    member: String,
    value: String,
): String = """
    {
      "type": "$type",
      "$member": {"fieldType": "concealed-string", "value": "$value"}
    }
""".trimIndent()

@Suppress("LongParameterList")
private fun passkey(
    credentialId: String = "AAECAwQFBg",
    rpId: String = "example.com",
    userHandle: String = "AAECAwQFBg",
    key: String = CXF_TEST_PASSKEY_KEY_URL,
): String = """
    {
      "type": "passkey",
      "credentialId": "$credentialId",
      "rpId": "$rpId",
      "username": "alice",
      "userDisplayName": "Alice",
      "userHandle": "$userHandle",
      "key": "$key"
    }
""".trimIndent()

private fun totp(
    secret: String = "JBSWY3DPEHPK3PXP",
    period: Int = 30,
    digits: Int = 6,
    algorithm: String = "sha1",
): String = """
    {
      "type": "totp",
      "secret": "$secret",
      "period": $period,
      "digits": $digits,
      "algorithm": "$algorithm"
    }
""".trimIndent()

private fun sshKey(
    privateKey: String = "AAECAwQFBg",
): String = """{"type": "ssh-key", "keyType": "ssh-ed25519", "privateKey": "$privateKey"}"""

private fun customFields(
    label: String,
): String = """
    {
      "type": "custom-fields",
      "fields": [{"fieldType": "string", "value": "v", "label": "$label"}]
    }
""".trimIndent()

private fun accountWithItems(itemsJson: String): String = """
    {
      "id": "YWNjLTE",
      "username": "Alice Example",
      "email": "alice@example.com",
      "collections": [],
      "items": $itemsJson
    }
""".trimIndent()

private fun accountWithoutItems(): String = """
    {
      "id": "YWNjLTE",
      "username": "Alice Example",
      "email": "alice@example.com",
      "collections": []
    }
""".trimIndent()

private fun accountWithCollections(
    collectionsJson: String,
    itemsJson: String = "[]",
): String = """
    {
      "id": "YWNjLTE",
      "username": "Alice Example",
      "email": "alice@example.com",
      "collections": $collectionsJson,
      "items": $itemsJson
    }
""".trimIndent()

private fun accountWithoutCollections(): String = """
    {
      "id": "YWNjLTE",
      "username": "Alice Example",
      "email": "alice@example.com",
      "items": []
    }
""".trimIndent()

/**
 * A conforming collection node: every required member present, optionally
 * linking one item.
 */
private fun goodCollection(
    id: String,
    title: String,
    itemId: String? = null,
): String {
    val items = itemId?.let { """{"item": "$it"}""" }.orEmpty()
    return """{"id": "$id", "title": "$title", "items": [$items]}"""
}

/**
 * A second item, with an id distinct from [itemWith]'s, so a document can carry
 * two items that collections link independently.
 */
private fun secondItemWith(
    vararg credentialsJson: String,
): String = """
    {
      "id": "aXRlbTAy",
      "title": "Item Two",
      "credentials": [${credentialsJson.joinToString(separator = ",")}]
    }
""".trimIndent()
