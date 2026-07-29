package com.artemchep.keyguard.common.service.credentialexchange

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.service.credentialexchange.impl.CxfExportServiceImpl
import com.artemchep.keyguard.common.service.credentialexchange.impl.CxfImportServiceImpl
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfCredential
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfCredentialType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Conformance coverage for the normative import behavior of CXF v1.0
 * (cxf-v1.0-ps-errata-20260309) — the §2.1.1 unknown-value rules, the §3.3.12
 * passkey constraints, versioning, and the §3.2.2.1 LinkedItem linking
 * semantics — including the deliberately pinned deviations.
 */
class CxfConformanceBehaviorTest {
    private val now = Instant.parse("2024-01-30T14:09:33Z")

    private val service = CxfImportServiceImpl(
        sshKeyImportService = FakeSshKeyImportService(),
    )

    private val exportService = CxfExportServiceImpl(
        sshKeyPkcs8Exporter = FakeSshKeyPkcs8Exporter(FAKE_PKCS8_DER),
    )

    private fun parse(payload: String): CxfImportPlan =
        service.parseSuccessPlan(payload = payload, now = now)

    @Test
    fun `an exported steam token carries the steam extension value`() {
        // Deviation D8 from CXF v1.0 §3.3.16, pinned deliberately: the value
        // SHOULD be a member of OTPHashAlgorithm and "steam" is not one, but
        // the CDDL types the member `OTPHashAlgorithm / tstr`, so the union arm
        // makes it structurally legal. A strict third-party importer MUST
        // ignore it, which is the correct outcome for a provider that cannot
        // compute Steam codes; refusing to emit it would instead lose the
        // credential on a Keyguard-to-Keyguard round trip.
        val account = exportService.buildAccount(
            profile = cxfProfile(),
            ciphers = listOf(cxfLoginSecret(login = DSecret.Login(totp = cxfSteamTotp()))),
            allowedTypes = CxfCredentialType.ALL,
        )
        val totp = assertIs<CxfCredential.Totp>(account?.items?.single()?.credentials?.single())
        assertEquals("steam", totp.algorithm)
        assertEquals(30, totp.period)
        assertEquals(5, totp.digits)
    }

    @Test
    fun `the algorithm is matched case and whitespace insensitively`() {
        // Deviation D9 from CXF v1.0 §3.3.16, pinned deliberately: the spec
        // literals are lowercase, so " SHA256 " is strictly an unknown value
        // that §3.3.16's MUST for importers — "importers MUST ignore TOTP
        // entries with unknown algorithm values", swept in
        // `CxfImportTotpUriMatrixTest` — would have us ignore. It has exactly
        // one possible meaning, and refusing it would destroy a credential without
        // protecting anything, so the whole member is trimmed and case-folded —
        // including the `steam` marker, where an exact-case comparison would
        // turn "Steam" into an otpauth SHA-1 credential carrying a Steam
        // secret, i.e. one that can never produce a valid code.
        val cased = parse(documentWithItems(totpItemJson(algorithm = " SHA256 ")))
        assertEquals(0, cased.skips[CxfImportSkipReason.Otp])
        assertEquals(
            "otpauth://totp/?secret=JBSWY3DPEHPK3PXP&algorithm=SHA256",
            cased.items.single().request.login.totp,
        )
        val steam = parse(documentWithItems(totpItemJson(algorithm = "STEAM")))
        assertEquals(0, steam.skips[CxfImportSkipReason.Otp])
        assertEquals("steam://JBSWY3DPEHPK3PXP", steam.items.single().request.login.totp)
    }

    @Test
    fun `an item without creationAt stamps its passkey with the import time`() {
        // §3.2.3: when creationAt is absent but the importer requires a value,
        // it SHOULD use the current timestamp.
        val plan = parse(
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
                      "username": "alice",
                      "userDisplayName": "Alice",
                      "userHandle": "AAECAwQFBg",
                      "key": "$CXF_TEST_PASSKEY_KEY_URL"
                    }
                  ]
                }
                """,
            ),
        )
        val passkey = plan.items.single().request.fido2Credentials.single()
        assertEquals(now, passkey.creationDate)
    }

    @Test
    fun `a malformed collections member degrades to a counted folderless import`() {
        val plan = parse(
            """
            {
              "version": {"major": 1, "minor": 0},
              "exporterRpId": "com.example.exporter",
              "exporterDisplayName": "Example Exporter",
              "timestamp": 1706623773,
              "accounts": [
                {
                  "id": "YWNjLTE",
                  "username": "u",
                  "email": "e",
                  "collections": "garbage",
                  "items": [
                    {
                      "id": "aXRlbTAx",
                      "title": "Login",
                      "credentials": [
                        {
                          "type": "basic-auth",
                          "username": {"fieldType": "string", "value": "alice"}
                        }
                      ]
                    }
                  ]
                }
              ]
            }
            """.trimIndent(),
        )
        assertTrue(plan.folders.isEmpty())
        // An unreadable `collections` member is one count for unknowably many:
        // there is no way to say how many folders were behind it.
        assertEquals(1, plan.skips.totalCount)
        assertEquals(1, plan.skips[CxfImportSkipReason.Collection])
        val item = plan.items.single()
        assertEquals("alice", item.request.login.username)
        assertNull(item.folderKey)
    }

    @Test
    fun `a blank collection title becomes the default folder name`() {
        val plan = parse(
            documentWithCollections(
                """
                {
                  "id": "Zm9sZGVyLTE",
                  "title": "   ",
                  "items": []
                }
                """,
            ),
        )
        assertEquals("Folder", plan.folders.single().title)
    }

    @Test
    fun `deeply nested subCollections import in pre-order`() {
        val plan = parse(
            documentWithCollections(
                """
                {
                  "id": "Zm9sZGVyLTE",
                  "title": "L1",
                  "items": [],
                  "subCollections": [
                    {
                      "id": "Zm9sZGVyLTI",
                      "title": "L2",
                      "items": [],
                      "subCollections": [
                        {
                          "id": "Zm9sZGVyLTM",
                          "title": "L3",
                          "items": [],
                          "subCollections": [
                            {
                              "id": "Zm9sZGVyLTQ",
                              "title": "L4",
                              "items": []
                            }
                          ]
                        }
                      ]
                    }
                  ]
                }
                """,
            ),
        )
        assertEquals(
            listOf(
                Triple("account-0/0", null, "L1"),
                Triple("account-0/1", "account-0/0", "L2"),
                Triple("account-0/2", "account-0/1", "L3"),
                Triple("account-0/3", "account-0/2", "L4"),
            ),
            plan.folders.map { Triple(it.key, it.parentKey, it.title) },
        )
    }

    @Test
    fun `two accounts import with namespaced folders and foreign links ignored`() {
        // §3.2.2.1 LinkedItem: an absent `account` member means the enclosing
        // account; a present one names the owning account. Item ids are only
        // unique per account, so account-1 linking account-0's "aXRlbTAx"
        // must not capture account-1's own item of the same id.
        val plan = parse(TWO_ACCOUNTS_IMPORT_JSON)
        assertEquals(2, plan.sourceAccountCount)
        assertEquals(
            listOf(
                Triple("account-0/0", null, "Work"),
                Triple("account-1/0", null, "Home"),
            ),
            plan.folders.map { Triple(it.key, it.parentKey, it.title) },
        )
        val alpha = plan.items.single { it.request.title == "Alpha" }
        assertEquals("account-0/0", alpha.folderKey)
        val beta = plan.items.single { it.request.title == "Beta" }
        assertEquals("account-1/0", beta.folderKey)
        // Account-1's own item shares the id of account-0's foreign-linked
        // item and must stay unfoldered.
        val gamma = plan.items.single { it.request.title == "Gamma" }
        assertNull(gamma.folderKey)
    }

    @Test
    fun `a version object with unknown members still parses`() {
        // Minor revisions are additive; a future version object gaining new
        // members must not fail the version gate.
        val result = service.parse(
            payload = """{"version":{"major":1,"minor":3,"errata":20260309},"accounts":[]}""",
            now = now,
        )
        assertIs<CxfImportResult.Success>(result)
    }
}

/**
 * One totp credential with the given algorithm, beside a basic-auth sibling.
 * The sibling is deliberate: it keeps `plan.items.single()` non-empty however
 * the totp is judged, so the algorithm assertion never depends on the login
 * gate.
 */
private fun totpItemJson(algorithm: String): String = """
    {
      "id": "aXRlbTAx",
      "title": "Totp",
      "credentials": [
        {
          "type": "totp",
          "secret": "JBSWY3DPEHPK3PXP",
          "period": 30,
          "digits": 6,
          "algorithm": "$algorithm"
        },
        {
          "type": "basic-auth",
          "username": {"fieldType": "string", "value": "alice"}
        }
      ]
    }
"""

/**
 * Two source accounts: account-0 owns the foldered item "Alpha"; account-1
 * owns "Beta" (foldered) and "Gamma" — the latter reusing account-0's item id,
 * while account-1's collection links that id as a foreign item of account-0.
 */
private const val TWO_ACCOUNTS_IMPORT_JSON = """
{
  "version": {"major": 1, "minor": 0},
  "exporterRpId": "com.example.exporter",
  "exporterDisplayName": "Example Exporter",
  "timestamp": 1706623773,
  "accounts": [
    {
      "id": "YWNjLTE",
      "username": "Alice Example",
      "email": "alice@example.com",
      "collections": [
        {
          "id": "Zm9sZGVyLTE",
          "title": "Work",
          "items": [{"item": "aXRlbTAx"}]
        }
      ],
      "items": [
        {
          "id": "aXRlbTAx",
          "title": "Alpha",
          "credentials": [
            {
              "type": "basic-auth",
              "username": {"fieldType": "string", "value": "alice"}
            }
          ]
        }
      ]
    },
    {
      "id": "YWNjLTI",
      "username": "Bob Example",
      "email": "bob@example.com",
      "collections": [
        {
          "id": "Zm9sZGVyLTI",
          "title": "Home",
          "items": [
            {"item": "aXRlbTAy"},
            {"item": "aXRlbTAx", "account": "YWNjLTE"}
          ]
        }
      ],
      "items": [
        {
          "id": "aXRlbTAy",
          "title": "Beta",
          "credentials": [
            {
              "type": "basic-auth",
              "username": {"fieldType": "string", "value": "bob"}
            }
          ]
        },
        {
          "id": "aXRlbTAx",
          "title": "Gamma",
          "credentials": [
            {
              "type": "basic-auth",
              "username": {"fieldType": "string", "value": "gamma"}
            }
          ]
        }
      ]
    }
  ]
}
"""
