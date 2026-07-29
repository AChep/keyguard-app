package com.artemchep.keyguard.common.service.credentialexchange

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.service.credentialexchange.impl.CxfImportServiceImpl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The round-trip asymmetries that `CxfRoundTripNormalizer` deliberately refuses
 * to encode.
 *
 * Nearly every case here is a **re-spelling**: a value comes back in a different
 * encoding, casing or shape. Expressing those in the shared normalizer would
 * mean reimplementing base64, hex, `YYYY-MM` packing or base32 canonicalisation,
 * so each is pinned here with a **literal** expected value instead, and the
 * shared normalizer's fixtures stay canonical so it can treat these as identity.
 *
 * The last region is the inverse and earns its place by the same rule: two
 * values the normalizer used to erase, whose *survival* now needs a literal to
 * mean anything.
 */
class CxfRoundTripEncodingTest {
    private val harness = CxfRoundTripHarness()

    private fun roundTripPasskey(
        credential: DSecret.Login.Fido2Credentials,
    ): DSecret.Login.Fido2Credentials = harness
        .views(cxfLoginSecret(login = DSecret.Login(fido2Credentials = listOf(credential))))
        .actual
        .passkeys
        .single()

    /**
     * A hand-written document rather than an exported one: Keyguard's export
     * never emits `"userHandle": ""`, so only a foreign producer reaches the case
     * that reads it.
     */
    private fun emptyUserHandleDocument(): String = documentWithItems(
        """
        {
          "id": "aXRlbTAx",
          "title": "Item",
          "credentials": [
            {
              "type": "passkey",
              "credentialId": "AAECAwQFBg",
              "rpId": "example.com",
              "username": "alice",
              "userDisplayName": "Alice",
              "userHandle": "",
              "key": "$CXF_TEST_PASSKEY_KEY_URL"
            }
          ]
        }
        """.trimIndent(),
    )

    // region Passkey binary re-spellings

    @Test
    fun `an upper-case uuid credential id comes back canonical`() {
        val passkey = roundTripPasskey(
            cxfFido2Credential(credentialId = "E8D88789-E916-E196-3CBD-81DAFAE71BBC"),
        )
        // 16 bytes on the wire, re-rendered by `Uuid.toString()`.
        assertEquals("e8d88789-e916-e196-3cbd-81dafae71bbc", passkey.credentialId)
    }

    @Test
    fun `a padded base64url credential id does not survive the export`() {
        // Unlike its two siblings, `mapCredentialId` decodes with padding ABSENT,
        // so a padded value is rejected outright and the passkey is skipped.
        val views = harness.views(
            cxfLoginSecret(
                login = DSecret.Login(
                    password = "s3cr3t",
                    fido2Credentials = listOf(cxfFido2Credential(credentialId = "AAECAwQFBg==")),
                ),
            ),
        )
        assertEquals(emptyList(), views.actual.passkeys)
        assertEquals(cxfExportSkips(CxfExportSkipReason.Passkey to 1), views.exportSkips)
    }

    @Test
    fun `a url-safe key comes back as padded standard base64`() {
        val passkey = roundTripPasskey(
            cxfFido2Credential(keyValue = CXF_TEST_PASSKEY_KEY_URL),
        )
        assertEquals(CXF_TEST_PASSKEY_KEY_STANDARD, passkey.keyValue)
    }

    @Test
    fun `a padded user handle loses its padding`() {
        val passkey = roundTripPasskey(cxfFido2Credential(userHandle = "AAECAwQFBg=="))
        assertEquals("AAECAwQFBg", passkey.userHandle)
    }

    @Test
    fun `an absent user handle does not survive the export`() {
        // The wire has no member for "no user handle", so the export refuses the
        // passkey rather than inventing one.
        val views = harness.views(
            cxfLoginSecret(
                login = DSecret.Login(
                    password = "s3cr3t",
                    fido2Credentials = listOf(cxfFido2Credential(userHandle = null)),
                ),
            ),
        )
        assertEquals(emptyList(), views.actual.passkeys)
        assertEquals(cxfExportSkips(CxfExportSkipReason.Passkey to 1), views.exportSkips)
    }

    @Test
    fun `an empty user handle on the wire imports as absent`() {
        // The deliberate asymmetry, from the other end: a producer with no way to
        // spell absence writes `""`, and refusing it would destroy a valid
        // credential id, private key and rp id, so the passkey is kept.
        val plan = CxfImportServiceImpl(FakeSshKeyImportService()).parseSuccessPlan(
            payload = emptyUserHandleDocument(),
            now = harness.now,
        )
        assertEquals(cxfImportSkips(), plan.skips)
        val credential = plan.items.single().request.fido2Credentials.single()
        assertNull(credential.userHandle)
        assertEquals("AAECAwQFBg", credential.credentialId)
        assertEquals(CXF_TEST_PASSKEY_KEY_STANDARD, credential.keyValue)
        assertEquals("example.com", credential.rpId)
    }

    // endregion

    // region Certificate fingerprint re-spelling

    @Test
    fun `a fingerprint comes back upper-case and colon-separated`() {
        val canonical = cxfCertFingerprint()
        val views = harness.views(
            cxfLoginSecret(
                login = DSecret.Login(password = "s3cr3t"),
                uris = listOf(
                    DSecret.Uri(
                        uri = "androidapp://com.example.app",
                        signatures = listOf(
                            // Lower-case and colon-less on the way in.
                            DSecret.Uri.Signature(
                                certFingerprintSha256 = canonical.replace(":", "").lowercase(),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val signature = views.actual.uris.values.flatten().single().signatures.single()
        assertEquals(canonical, signature.certFingerprintSha256)
    }

    // endregion

    // region TOTP secret re-spelling

    @Test
    fun `a spaced lower-case secret comes back canonical`() {
        // One round-trip case for the whole family — the separator, casing and
        // padding rules themselves are a grid in `CxfTotpSecretTest`; what is
        // pinned here is only that the canonicalized spelling survives the
        // export/import pair.
        val views = harness.views(
            cxfLoginSecret(
                login = DSecret.Login(totp = cxfTotpAuth(keyBase32 = "jbsw y3dp")),
            ),
        )
        assertEquals("JBSWY3DP", views.actual.login?.totp?.secretBase32)
    }

    // endregion

    // region Card year-month

    @Test
    fun `a two digit expiry year shifts into the current century`() {
        val views = harness.views(
            cxfSecret(
                type = DSecret.Type.Card,
                card = DSecret.Card(number = "4111", expMonth = "6", expYear = "25"),
            ),
        )
        assertEquals("2025", views.actual.card?.expYear)
        assertEquals("6", views.actual.card?.expMonth)
    }

    @Test
    fun `a zero-padded month comes back unpadded`() {
        val views = harness.views(
            cxfSecret(
                type = DSecret.Type.Card,
                card = DSecret.Card(number = "4111", expMonth = "05", expYear = "2027"),
            ),
        )
        assertEquals("5", views.actual.card?.expMonth)
    }

    @Test
    fun `a three digit year keeps its zero padding on the wire but not after`() {
        // `yearMonthOrNull` emits "0645-06"; `mapImportYearMonth` re-prints the
        // year as an integer, so the leading zero is gone.
        val views = harness.views(
            cxfSecret(
                type = DSecret.Type.Card,
                card = DSecret.Card(number = "4111", expMonth = "6", expYear = "645"),
            ),
        )
        assertEquals("645", views.actual.card?.expYear)
    }

    @Test
    fun `an unusable month loses the year with it`() {
        val views = harness.views(
            cxfSecret(
                type = DSecret.Type.Card,
                card = DSecret.Card(number = "4111", expMonth = "13", expYear = "2027"),
            ),
        )
        assertNull(views.actual.card?.expMonth)
        assertNull(views.actual.card?.expYear)
    }

    // endregion

    // region Boolean field values

    private fun roundTripField(field: DSecret.Field): DSecret.Field? = harness
        .views(cxfLoginSecret(login = DSecret.Login(password = "s3cr3t"), fields = listOf(field)))
        .actual
        .fields
        .values
        .flatten()
        .singleOrNull()

    @Test
    fun `a differently cased boolean is canonicalized`() {
        val field = roundTripField(
            DSecret.Field(name = "Flag", value = " TRUE ", type = DSecret.Field.Type.Boolean),
        )
        assertEquals(DSecret.Field.Type.Boolean, field?.type)
        assertEquals("true", field?.value)
    }

    @Test
    fun `a boolean the vault cannot store degrades to text`() {
        // The exporter does not validate the value, so "yes" reaches the wire as
        // a boolean; the importer keeps the text and drops the type instead of
        // failing the whole import.
        listOf("yes", "1", "0").forEach { value ->
            val field = roundTripField(
                DSecret.Field(name = "Flag", value = value, type = DSecret.Field.Type.Boolean),
            )
            assertEquals(DSecret.Field.Type.Text, field?.type, value)
            assertEquals(value, field?.value, value)
        }
    }

    // endregion

    // region Address lines

    @Test
    fun `an address line containing a newline shifts the later lines`() {
        // The three lines are joined with newlines, so an embedded one is
        // indistinguishable from a separator on the way back.
        val views = harness.views(
            cxfSecret(
                type = DSecret.Type.Identity,
                identity = DSecret.Identity(address1 = "a\nb", address2 = "c"),
            ),
        )
        assertEquals("a", views.actual.identity?.address1)
        assertEquals("b", views.actual.identity?.address2)
        assertEquals("c", views.actual.identity?.address3)
    }

    @Test
    fun `four effective address lines collapse into three`() {
        val views = harness.views(
            cxfSecret(
                type = DSecret.Type.Identity,
                identity = DSecret.Identity(address1 = "a\nb", address2 = "c", address3 = "d"),
            ),
        )
        assertEquals("a", views.actual.identity?.address1)
        assertEquals("b", views.actual.identity?.address2)
        // Everything past the second line is re-joined with a comma.
        assertEquals("c, d", views.actual.identity?.address3)
    }

    // endregion

    // region Values the normalizer used to erase

    @Test
    fun `a whitespace only password comes back verbatim`() {
        // A password of only whitespace is a password, and both mappers gate on
        // `isNotEmpty`. The normalizer used to `ifBlank { null }` it away, which
        // is what made this destructive round trip look green: the username kept
        // the credential travelling, so the item imported with an empty tally and
        // the secret simply ceased to exist.
        val views = harness.views(
            cxfLoginSecret(login = DSecret.Login(username = "alice", password = "   ")),
        )
        assertEquals("   ", views.actual.login?.password)
        assertEquals("alice", views.actual.login?.username)
    }

    @Test
    fun `a card validity start comes back as a labelled field`() {
        // The shape change: two card members out, one field back. `AddCipher`
        // builds `BitwardenCipher.Card` without `fromMonth`/`fromYear`, so writing
        // the wire `validFrom` back into them lost it at the vault write — and
        // made an otherwise blank card look non-empty, hiding the item from the
        // skip tally. The `YYYY-MM` value is the exporter's own packing, verbatim.
        val views = harness.views(
            cxfSecret(
                type = DSecret.Type.Card,
                card = DSecret.Card(fromMonth = "1", fromYear = "2024"),
            ),
        )
        assertNull(views.actual.card?.fromMonth)
        assertNull(views.actual.card?.fromYear)
        assertEquals(
            listOf("Valid from" to "2024-01"),
            views.actual.fields
                .getValue(DSecret.Type.Card)
                .map { it.name to it.value },
        )
        assertEquals(cxfImportSkips(), views.importSkips)
    }

    // endregion
}
