package com.artemchep.keyguard.common.service.credentialexchange

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.service.credentialexchange.impl.CxfExportServiceImpl
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfCredential
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfCredentialType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * What a single vault item emits, and which credential kinds the `allowedTypes`
 * filter actually reaches.
 *
 * The fact this file pins: `DSecret.type` is never read on the export path.
 * Emission is driven entirely by which nullable sub-objects an item carries, so
 * `notes` and `fields` produce credentials for every item type, and one item
 * holding a login, a card, an identity and an SSH key emits all four.
 */
class CxfExportEmissionMatrixTest {
    private val now = Instant.parse("2024-01-30T14:09:33Z")

    private val service = CxfExportServiceImpl(
        sshKeyPkcs8Exporter = FakeSshKeyPkcs8Exporter(byteArrayOf(1, 2, 3, 4, 5, 6)),
    )

    /**
     * The whole export result for a single vault item. With one cipher in play
     * the account-level tally is that item's tally, so this is how a test reads
     * the skips a single item raised.
     */
    private fun exportOf(
        secret: DSecret,
        allowedTypes: Set<CxfCredentialType> = CxfCredentialType.ALL,
    ): CxfAccountResult = service.buildAccountResult(
        profile = cxfProfile(),
        ciphers = listOf(secret),
        allowedTypes = allowedTypes,
    )

    private fun kinds(
        secret: DSecret,
        allowedTypes: Set<CxfCredentialType> = CxfCredentialType.ALL,
    ): List<String> = service
        .buildAccountResult(
            profile = cxfProfile(),
            ciphers = listOf(secret),
            allowedTypes = allowedTypes,
        )
        .account
        ?.items
        ?.singleOrNull()
        ?.credentials
        ?.map { it.discriminator }
        .orEmpty()

    // region Emission is driven by payload, not by `type`

    private data class EmissionCase(
        val name: String,
        val secret: DSecret,
        val expected: List<String>,
    )

    private val emissionCases = listOf(
        EmissionCase(
            name = "a login emits basic-auth",
            secret = cxfLoginSecret(login = DSecret.Login(username = "alice", password = "s3cr3t")),
            expected = listOf("basic-auth"),
        ),
        EmissionCase(
            name = "a secure note emits note",
            secret = cxfSecret(type = DSecret.Type.SecureNote, notes = "n"),
            expected = listOf("note"),
        ),
        EmissionCase(
            name = "a Card-typed item with no card emits only its note",
            secret = cxfSecret(type = DSecret.Type.Card, notes = "n"),
            expected = listOf("note"),
        ),
        EmissionCase(
            name = "a GpgKey-typed item still emits its note",
            secret = cxfSecret(type = DSecret.Type.GpgKey, notes = "n"),
            expected = listOf("note"),
        ),
        EmissionCase(
            name = "a GpgKey-typed item still emits its custom fields",
            secret = cxfSecret(
                type = DSecret.Type.GpgKey,
                fields = listOf(DSecret.Field(name = "f", value = "v", type = DSecret.Field.Type.Text)),
            ),
            expected = listOf("custom-fields"),
        ),
        EmissionCase(
            name = "a SecureNote-typed item carrying a card emits credit-card",
            secret = cxfSecret(
                type = DSecret.Type.SecureNote,
                card = DSecret.Card(number = "4111"),
            ),
            expected = listOf("credit-card"),
        ),
        EmissionCase(
            name = "a None-typed item carrying a login emits basic-auth",
            secret = cxfSecret(
                type = DSecret.Type.None,
                login = DSecret.Login(password = "s3cr3t"),
            ),
            expected = listOf("basic-auth"),
        ),
    )

    @Test
    fun `emission ignores the item type entirely`() {
        emissionCases.forEach { case ->
            assertEquals(case.expected, kinds(case.secret), case.name)
        }
    }

    /**
     * A single item carrying every payload at once. Nothing in the vault stops
     * this — `DSecret` has independent nullable sub-objects — and the exporter
     * reads all of them.
     */
    private val everythingSecret = cxfSecret(
        type = DSecret.Type.Login,
        login = DSecret.Login(username = "alice", password = "s3cr3t"),
        card = DSecret.Card(number = "4111"),
        identity = DSecret.Identity(firstName = "Alice", city = "Springfield", company = "Acme"),
        sshKey = DSecret.SshKey(privateKey = "pem", publicKey = "ssh-ed25519 AAAA"),
        notes = "a note",
        fields = listOf(DSecret.Field(name = "f", value = "v", type = DSecret.Field.Type.Text)),
    )

    @Test
    fun `one item emits every payload it carries in a fixed order`() {
        // The order is `collectCredentials`' own sequence and is observable on
        // the wire, so it is pinned rather than sorted away. Note the *two*
        // custom-fields credentials: the identity's overflow bag comes first
        // (right after the address it overflowed from), the user's own fields
        // after the note, and the ssh key last of all.
        assertEquals(
            listOf(
                "basic-auth",
                "credit-card",
                "person-name",
                "address",
                "custom-fields",
                "note",
                "custom-fields",
                "ssh-key",
            ),
            kinds(everythingSecret),
        )
    }

    @Test
    fun `an archived item is withheld and counted`() {
        // Archived means "kept, not in use" and the format has no archive
        // member, so an exported one would arrive active in the receiving app.
        val archived = cxfLoginSecret(login = DSecret.Login(password = "s3cr3t"))
            .copy(archivedDate = Instant.parse("2024-02-01T00:00:00Z"))
        assertEquals(emptyList(), kinds(archived))
        assertEquals(
            cxfExportSkips(CxfExportSkipReason.Archived to 1),
            exportOf(archived).skips,
        )
    }

    @Test
    fun `an archived item holding nothing exportable is not counted`() {
        // Nothing was withheld that could have travelled, so there is no loss to
        // report — the same silence an unarchived GPG-key item would get from
        // the filter rule, rather than a second row blaming the archive.
        val archived = cxfSecret(type = DSecret.Type.GpgKey)
            .copy(archivedDate = Instant.parse("2024-02-01T00:00:00Z"))
        assertEquals(emptyList(), kinds(archived))
        assertEquals(cxfExportSkips(), exportOf(archived).skips)
    }

    @Test
    fun `an archived item the filter would have emptied is not counted`() {
        // The requester never asked for what this item holds, so withholding it
        // costs them nothing and the row would be noise.
        val archived = cxfLoginSecret(login = DSecret.Login(password = "s3cr3t"))
            .copy(archivedDate = Instant.parse("2024-02-01T00:00:00Z"))
        val result = exportOf(archived, allowedTypes = setOf(CxfCredentialType.Note))
        assertNull(result.account)
        assertEquals(cxfExportSkips(), result.skips)
    }

    @Test
    fun `a trashed archived item counts as neither`() {
        // Trash short-circuits first: a deleted item is not a withheld one.
        val secret = cxfLoginSecret(login = DSecret.Login(password = "s3cr3t"))
            .copy(
                archivedDate = Instant.parse("2024-02-01T00:00:00Z"),
                deletedDate = Instant.parse("2024-02-02T00:00:00Z"),
            )
        assertEquals(cxfExportSkips(), exportOf(secret).skips)
    }

    @Test
    fun `a trashed item never has its credentials collected`() {
        // A trashed item must short-circuit before any mapper runs, so an
        // exporter that raises when reached is the proof.
        val trashedService = CxfExportServiceImpl(
            sshKeyPkcs8Exporter = ThrowingSshKeyPkcs8Exporter(),
        )
        val result = trashedService.buildAccountResult(
            profile = cxfProfile(),
            ciphers = listOf(
                everythingSecret.copy(deletedDate = Instant.parse("2024-02-01T00:00:00Z")),
            ),
            allowedTypes = CxfCredentialType.ALL,
        )
        assertNull(result.account)
        // `mapSshKey` absorbs the exporter's `error(...)` into one counted
        // SSH-key skip, so `assertNull` alone would pass even if the trashed
        // item had been collected. This count is the tripwire; keep it.
        assertEquals(0, result.skips.totalCount)
    }

    // endregion

    // region The allowedTypes gate

    private data class GateCase(
        val name: String,
        val allowedTypes: Set<CxfCredentialType>,
        val expected: List<String>,
    )

    private val gateCases = listOf(
        GateCase("basic-auth alone", setOf(CxfCredentialType.BasicAuth), listOf("basic-auth")),
        GateCase("credit-card alone", setOf(CxfCredentialType.CreditCard), listOf("credit-card")),
        GateCase("person-name alone", setOf(CxfCredentialType.PersonName), listOf("person-name")),
        GateCase("address alone", setOf(CxfCredentialType.Address), listOf("address")),
        GateCase("note alone", setOf(CxfCredentialType.Note), listOf("note")),
        GateCase("ssh-key alone", setOf(CxfCredentialType.SshKey), listOf("ssh-key")),
        GateCase(
            // One gate, two sources: the identity's overflow and the user's fields.
            name = "custom-fields alone yields both bags",
            allowedTypes = setOf(CxfCredentialType.CustomFields),
            expected = listOf("custom-fields", "custom-fields"),
        ),
        GateCase(
            name = "the three import-only kinds export nothing",
            allowedTypes = setOf(
                CxfCredentialType.Passport,
                CxfCredentialType.DriversLicense,
                CxfCredentialType.IdentityDocument,
            ),
            expected = emptyList(),
        ),
        GateCase(
            // CXP §3.2: unknown requested type values MUST be ignored, so a
            // request whose types are all unrecognized filters down to an empty
            // set — which must export nothing, never fall back to the full
            // vault.
            name = "an empty set exports nothing",
            allowedTypes = emptySet(),
            expected = emptyList(),
        ),
    )

    @Test
    fun `the gate is an exact per-kind filter`() {
        gateCases.forEach { case ->
            assertEquals(
                case.expected,
                kinds(everythingSecret, allowedTypes = case.allowedTypes),
                case.name,
            )
        }
    }

    @Test
    fun `the unexportable half of the enum is inert`() {
        // ALL is 17 kinds, EXPORTABLE is 9, IMPORTABLE is 12 — and on the export
        // path all three produce the same document, because the extra kinds gate
        // nothing. This is what lets the Android registry advertise EXPORTABLE
        // without narrowing what a full export contains.
        val viaAll = kinds(everythingSecret, allowedTypes = CxfCredentialType.ALL)
        assertEquals(viaAll, kinds(everythingSecret, allowedTypes = CxfCredentialType.EXPORTABLE))
        assertEquals(viaAll, kinds(everythingSecret, allowedTypes = CxfCredentialType.IMPORTABLE))
        assertTrue(viaAll.isNotEmpty())
    }

    // endregion
}

/**
 * The `type` discriminator the credential serializes as, taken from the model's
 * own `@SerialName`s so the expectation is the wire string rather than a Kotlin
 * class name.
 */
private val CxfCredential.discriminator: String
    get() = when (this) {
        is CxfCredential.Passkey -> "passkey"
        is CxfCredential.BasicAuth -> "basic-auth"
        is CxfCredential.Totp -> "totp"
        is CxfCredential.CreditCard -> "credit-card"
        is CxfCredential.Address -> "address"
        is CxfCredential.PersonName -> "person-name"
        is CxfCredential.Passport -> "passport"
        is CxfCredential.DriversLicense -> "drivers-license"
        is CxfCredential.IdentityDocument -> "identity-document"
        is CxfCredential.Note -> "note"
        is CxfCredential.SshKey -> "ssh-key"
        is CxfCredential.CustomFields -> "custom-fields"
    }
