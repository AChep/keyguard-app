package com.artemchep.keyguard.common.service.credentialexchange

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.FolderHierarchyMode
import kotlin.test.Test

/**
 * Whatever the Keyguard exporter emits, the Keyguard importer reconstructs.
 *
 * Each case projects the source item and the requests it round-tripped into
 * onto one [CxfRoundTripView] and compares them whole, so a member nobody
 * thought to assert cannot quietly disappear: it either survives, or it is
 * absent from the view by an explicit, documented erasure in
 * `CxfRoundTripNormalizer`.
 */
class CxfRoundTripViewTest {
    private val harness = CxfRoundTripHarness()

    @Test
    fun `a login round-trips`() {
        harness.assertRoundTrips(
            cxfLoginSecret(
                login = DSecret.Login(username = "alice@example.com", password = "s3cr3t"),
            ),
        )
    }

    @Test
    fun `a login with a passkey and a totp round-trips`() {
        harness.assertRoundTrips(
            cxfLoginSecret(
                login = DSecret.Login(
                    username = "alice@example.com",
                    password = "s3cr3t",
                    fido2Credentials = listOf(cxfFido2Credential()),
                    totp = cxfTotpAuth(),
                ),
            ),
        )
    }

    @Test
    fun `a login with several passkeys keeps all of them`() {
        harness.assertRoundTrips(
            cxfLoginSecret(
                login = DSecret.Login(
                    password = "s3cr3t",
                    fido2Credentials = listOf(
                        cxfFido2Credential(credentialId = "AAECAwQFBg"),
                        cxfFido2Credential(credentialId = "BgUEAwIBAA", rpId = "other.example"),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `a login with uris and an android app round-trips`() {
        harness.assertRoundTrips(
            cxfLoginSecret(
                login = DSecret.Login(password = "s3cr3t"),
                uris = listOf(
                    DSecret.Uri(uri = "https://example.com"),
                    DSecret.Uri(uri = "example.org"),
                    DSecret.Uri(
                        uri = "androidapp://com.example.app",
                        signatures = listOf(
                            DSecret.Uri.Signature(certFingerprintSha256 = cxfCertFingerprint()),
                        ),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `interleaved uris come back partitioned`() {
        // The scope splits urls from android apps, so the original interleaving
        // is not recoverable. The normalizer declares that reordering.
        harness.assertRoundTrips(
            cxfLoginSecret(
                login = DSecret.Login(password = "s3cr3t"),
                uris = listOf(
                    DSecret.Uri(uri = "androidapp://com.example.first"),
                    DSecret.Uri(uri = "https://example.com"),
                    DSecret.Uri(uri = "androidapp://com.example.second"),
                ),
            ),
        )
    }

    @Test
    fun `uris the scope cannot carry are dropped`() {
        harness.assertRoundTrips(
            cxfLoginSecret(
                login = DSecret.Login(password = "s3cr3t"),
                uris = listOf(
                    DSecret.Uri(uri = "   "),
                    DSecret.Uri(uri = "cmd://echo hi"),
                    DSecret.Uri(
                        uri = "https://regex.example",
                        match = DSecret.Uri.MatchType.RegularExpression,
                    ),
                    DSecret.Uri(uri = "androidapp://"),
                    DSecret.Uri(uri = "https://example.com"),
                ),
            ),
        )
    }

    @Test
    fun `a card round-trips`() {
        harness.assertRoundTrips(
            cxfSecret(
                type = DSecret.Type.Card,
                card = DSecret.Card(
                    cardholderName = "Alice Example",
                    brand = "Visa",
                    number = "4111111111111111",
                    code = "123",
                    expMonth = "5",
                    expYear = "2027",
                    fromMonth = "1",
                    fromYear = "2024",
                ),
            ),
        )
    }

    @Test
    fun `an identity round-trips through the split and re-merge`() {
        harness.assertRoundTrips(
            cxfSecret(
                type = DSecret.Type.Identity,
                identity = DSecret.Identity(
                    title = "Dr",
                    firstName = "Alice",
                    middleName = "Betty",
                    lastName = "Example",
                    address1 = "1 Main St",
                    address2 = "Apt 2",
                    city = "Springfield",
                    state = "OR",
                    postalCode = "97477",
                    country = "US",
                    phone = "555-0100",
                    company = "Acme",
                    email = "alice@example.com",
                    username = "acme",
                    ssn = "078-05-1120",
                    passportNumber = "P123456",
                    licenseNumber = "DL123456",
                ),
            ),
        )
    }

    @Test
    fun `an identity with a gap in its address lines closes up`() {
        // The lines are joined with newlines and re-split positionally, so an
        // address2-only identity comes back as address1.
        harness.assertRoundTrips(
            cxfSecret(
                type = DSecret.Type.Identity,
                identity = DSecret.Identity(address2 = "Apt 2", city = "Springfield"),
            ),
        )
    }

    @Test
    fun `a user field named like an identity slot is absorbed`() {
        // "Company" is one of the six labels the identity's overflow travels
        // under, so a user field of that name lands in the empty slot instead
        // of coming back as a field.
        harness.assertRoundTrips(
            cxfSecret(
                type = DSecret.Type.Identity,
                identity = DSecret.Identity(firstName = "Alice"),
                fields = listOf(
                    DSecret.Field(name = "Company", value = "Acme", type = DSecret.Field.Type.Text),
                ),
            ),
        )
    }

    @Test
    fun `a secure note round-trips`() {
        harness.assertRoundTrips(
            cxfSecret(type = DSecret.Type.SecureNote, notes = "remember this"),
        )
    }

    @Test
    fun `custom fields round-trip`() {
        harness.assertRoundTrips(
            cxfLoginSecret(
                login = DSecret.Login(password = "s3cr3t"),
                fields = listOf(
                    DSecret.Field(name = "Text", value = "v", type = DSecret.Field.Type.Text),
                    DSecret.Field(name = "Hidden", value = "h", type = DSecret.Field.Type.Hidden),
                    DSecret.Field(name = "Boolean", value = "true", type = DSecret.Field.Type.Boolean),
                ),
            ),
        )
    }

    @Test
    fun `fields the format cannot carry are dropped`() {
        harness.assertRoundTrips(
            cxfLoginSecret(
                login = DSecret.Login(password = "s3cr3t"),
                fields = listOf(
                    DSecret.Field(name = "Kept", value = "v", type = DSecret.Field.Type.Text),
                    DSecret.Field(name = "Blank", value = "  ", type = DSecret.Field.Type.Text),
                    DSecret.Field(name = "NoValue", value = null, type = DSecret.Field.Type.Text),
                    DSecret.Field(
                        name = "Linked",
                        value = "v",
                        linkedId = DSecret.Field.LinkedId.Login_Username,
                        type = DSecret.Field.Type.Linked,
                    ),
                    DSecret.Field(name = "  ", value = "unnamed", type = DSecret.Field.Type.Text),
                ),
            ),
        )
    }

    @Test
    fun `an ssh key round-trips through the conversion seam`() {
        harness.assertRoundTrips(
            cxfSecret(
                type = DSecret.Type.SshKey,
                sshKey = DSecret.SshKey(
                    privateKey = "-----BEGIN OPENSSH PRIVATE KEY-----\nreal\n" +
                        "-----END OPENSSH PRIVATE KEY-----\n",
                    publicKey = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIReal work@example.com",
                    fingerprint = "SHA256:realfingerprint",
                ),
            ),
        )
    }

    @Test
    fun `an item carrying every payload comes back as several requests`() {
        // One vault item, four create requests — the exporter reads every
        // sub-object regardless of `secret.type`, and the importer rebuilds one
        // request per payload.
        harness.assertRoundTrips(
            cxfSecret(
                type = DSecret.Type.Login,
                login = DSecret.Login(username = "alice", password = "s3cr3t"),
                card = DSecret.Card(number = "4111", expMonth = "5", expYear = "2027"),
                identity = DSecret.Identity(firstName = "Alice", city = "Springfield"),
                sshKey = DSecret.SshKey(
                    privateKey = "pem",
                    publicKey = "ssh-ed25519 AAAA",
                ),
                notes = "a note on every request",
            ),
        )
    }

    @Test
    fun `members the wire cannot carry are lost`() {
        // reprompt, organizationId, collectionIds, attachments, passwordHistory
        // and the gpg key all vanish; the view has no place for any of them.
        //
        // The gpg key, the attachment and the retained password are content of
        // the item, so the item travels *and* the review screen names what
        // stayed behind: one of each, the counts `withUnexportableMembers`
        // stamps.
        harness.assertRoundTrips(
            cxfLoginSecret(
                login = DSecret.Login(username = "alice", password = "s3cr3t"),
            ).withUnexportableMembers(),
            expectedExportSkips = cxfExportSkips(
                CxfExportSkipReason.GpgKey to 1,
                CxfExportSkipReason.Attachment to 1,
                CxfExportSkipReason.PasswordHistory to 1,
            ),
        )
    }

    @Test
    fun `an archived item vanishes entirely and is counted`() {
        // Unlike the group above, an archived item does not travel and arrive
        // diminished — it never leaves, and the review screen says so.
        harness.assertRoundTrips(
            cxfLoginSecret(
                login = DSecret.Login(username = "alice", password = "s3cr3t"),
            ).copy(archivedDate = harness.now),
            expectedExportSkips = cxfExportSkips(CxfExportSkipReason.Archived to 1),
        )
    }

    @Test
    fun `a trashed item vanishes entirely`() {
        harness.assertRoundTrips(
            cxfLoginSecret(
                login = DSecret.Login(password = "s3cr3t"),
                deletedDate = harness.now,
            ),
        )
    }

    @Test
    fun `an item with nothing representable vanishes and is counted`() {
        harness.assertRoundTrips(
            cxfSecret(type = DSecret.Type.GpgKey),
            expectedExportSkips = cxfExportSkips(CxfExportSkipReason.Item to 1),
        )
    }

    @Test
    fun `a folder round-trips as its title`() {
        harness.assertRoundTrips(
            cxfLoginSecret(
                login = DSecret.Login(password = "s3cr3t"),
                folderId = "f1",
            ),
            folders = listOf(
                cxfFolder(id = "f1", name = "Work", hierarchyMode = FolderHierarchyMode.ParentId),
            ),
        )
    }

    @Test
    fun `a blank title comes back absent`() {
        // The commit step later substitutes a translated placeholder; at plan
        // level the title is simply gone.
        harness.assertRoundTrips(
            cxfLoginSecret(name = "   ", login = DSecret.Login(password = "s3cr3t")),
        )
    }

    @Test
    fun `a passkey with a non-zero counter is excluded and counted`() {
        harness.assertRoundTrips(
            cxfLoginSecret(
                login = DSecret.Login(
                    password = "s3cr3t",
                    fido2Credentials = listOf(cxfFido2Credential(counter = 7)),
                ),
            ),
            expectedExportSkips = cxfExportSkips(CxfExportSkipReason.Passkey to 1),
        )
    }

    @Test
    fun `unsupported passkey key metadata is excluded and counted`() {
        harness.assertRoundTrips(
            cxfLoginSecret(
                login = DSecret.Login(
                    password = "s3cr3t",
                    fido2Credentials = listOf(
                        cxfFido2Credential(
                            keyAlgorithm = "RSA",
                            keyCurve = "P-384",
                            keyType = "not-public-key",
                            rpName = "Example Inc",
                            discoverable = false,
                        ),
                    ),
                ),
            ),
            expectedExportSkips = cxfExportSkips(CxfExportSkipReason.Passkey to 1),
        )
    }

    @Test
    fun `a passkey with no username borrows the display name`() {
        harness.assertRoundTrips(
            cxfLoginSecret(
                login = DSecret.Login(
                    password = "s3cr3t",
                    fido2Credentials = listOf(
                        cxfFido2Credential(userName = null, userDisplayName = "Alice"),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `a login with only a passkey fabricates a uri and a username`() {
        // With no scope the rpId becomes a uri, and with no basic-auth the
        // passkey's username becomes the login's.
        harness.assertRoundTrips(
            cxfLoginSecret(
                login = DSecret.Login(fido2Credentials = listOf(cxfFido2Credential())),
            ),
        )
    }

    @Test
    fun `an otp the format cannot represent is dropped and counted`() {
        harness.assertRoundTrips(
            cxfLoginSecret(
                login = DSecret.Login(password = "s3cr3t", totp = cxfHotpAuth()),
            ),
            expectedExportSkips = cxfExportSkips(CxfExportSkipReason.Otp to 1),
        )
    }

    @Test
    fun `a steam token round-trips as the steam extension`() {
        harness.assertRoundTrips(
            cxfLoginSecret(
                login = DSecret.Login(password = "s3cr3t", totp = cxfSteamTotp()),
            ),
        )
    }

    @Test
    fun `a steam token with a login username round-trips`() {
        // The exporter fills the wire `username` in from the enclosing login,
        // and `steam://` cannot carry it back — the erasure is declared in
        // `CxfRoundTripNormalizer`, so the view comparison still matches.
        harness.assertRoundTrips(
            cxfLoginSecret(
                login = DSecret.Login(
                    username = "alice",
                    password = "s3cr3t",
                    totp = cxfSteamTotp(),
                ),
            ),
        )
    }

    @Test
    fun `a totp with custom parameters round-trips`() {
        harness.assertRoundTrips(
            cxfLoginSecret(
                login = DSecret.Login(
                    username = "alice",
                    totp = cxfTotpAuth(digits = 8, period = 60L, issuer = "ACME"),
                ),
            ),
        )
    }
}
