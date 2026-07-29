package com.artemchep.keyguard.common.service.credentialexchange

import com.artemchep.keyguard.common.model.CryptoHashAlgorithm
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.TotpToken
import com.artemchep.keyguard.common.service.credentialexchange.impl.CxfExportServiceImpl
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfCredentialType
import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

private const val HOTP_RAW = "otpauth://hotp/test?secret=JBSWY3DPEHPK3PXP&counter=1"

/**
 * The review screen's honesty guarantee: anything that leaves the vault
 * without reaching the wire has to be counted. A silent drop is worse than a
 * loud one — the user confirms a transfer believing their vault went across.
 */
class CxfExportSkipAccountingTest {
    private val exportService = CxfExportServiceImpl(
        sshKeyPkcs8Exporter = FakeSshKeyPkcs8Exporter(byteArrayOf(1, 2, 3, 4, 5, 6)),
    )

    private fun exportResult(ciphers: List<DSecret>): CxfAccountResult =
        exportService.buildAccountResult(
            profile = cxfProfile(),
            ciphers = ciphers,
            allowedTypes = CxfCredentialType.ALL,
        )

    @Test
    fun `a passkey backend that throws costs one counted skip, not the account`() {
        // `PasskeyCrypto` is injected; a backend that cannot initialize throws
        // out of `inspect`. Unguarded that unwinds to the service boundary,
        // which charges the whole account — so a 500-item vault would export
        // nothing because one stored key was unreadable.
        val result = CxfExportServiceImpl(
            passkeyCrypto = ThrowingPasskeyCrypto(),
            sshKeyPkcs8Exporter = FakeSshKeyPkcs8Exporter(byteArrayOf(1, 2, 3, 4, 5, 6)),
        ).buildAccountResult(
            profile = cxfProfile(),
            ciphers = listOf(
                cxfLoginSecret(
                    id = "passkey-1",
                    login = DSecret.Login(fido2Credentials = listOf(cxfFido2Credential())),
                ),
                cxfLoginSecret(
                    id = "login-1",
                    login = DSecret.Login(password = "s3cr3t"),
                ),
            ),
            allowedTypes = CxfCredentialType.ALL,
        )
        assertEquals(1, result.skips[CxfExportSkipReason.Passkey])
        assertEquals(0, result.skips[CxfExportSkipReason.Account])
        // The rest of the account still travels — that is the whole point.
        assertEquals(1, assertNotNull(result.account).items.size)
    }

    @Test
    fun `a skip is attributed to the item it was lost from`() {
        // The review screen expands a warning row into the items behind it, and
        // the attribution happens once per cipher at the account fold rather
        // than at each of the sites that raised a reason — so what this really
        // pins is that the fold hands each sub-tally the right name.
        val result = exportResult(
            listOf(
                cxfSecret(id = "a", name = "Netflix")
                    .copy(attachments = listOf(cxfAttachment(), cxfAttachment())),
                cxfSecret(id = "b", name = "GitHub")
                    .copy(attachments = listOf(cxfAttachment())),
            ),
        )
        assertEquals(3, result.skips[CxfExportSkipReason.Attachment])
        assertEquals(
            mapOf("Netflix" to 2, "GitHub" to 1),
            result.skips.titlesOf(CxfExportSkipReason.Attachment),
        )
    }

    @Test
    fun `an empty item is counted rather than vanishing`() {
        val result = exportResult(
            listOf(
                cxfSecret(
                    id = "empty-1",
                    name = "Nothing here",
                    type = DSecret.Type.SecureNote,
                    notes = "",
                    tags = emptyList(),
                ),
            ),
        )
        assertNull(result.account)
        assertEquals(1, result.skips[CxfExportSkipReason.Item])
    }

    @Test
    fun `an item lost to a counted credential is not counted twice`() {
        // The only credential is an HOTP token, which is already reported as a
        // skipped one-time password; reporting the item too would double-count
        // a single loss.
        val result = exportResult(
            listOf(
                cxfLoginSecret(
                    id = "hotp-1",
                    login = DSecret.Login(
                        totp = DSecret.Login.Totp(
                            raw = HOTP_RAW,
                            token = TotpToken.HotpAuth(
                                algorithm = CryptoHashAlgorithm.SHA_1,
                                keyBase32 = "JBSWY3DPEHPK3PXP",
                                raw = HOTP_RAW,
                                digits = 6,
                                counter = 1L,
                            ),
                        ),
                    ),
                ),
            ),
        )
        assertNull(result.account)
        assertEquals(1, result.skips[CxfExportSkipReason.Otp])
        assertEquals(0, result.skips[CxfExportSkipReason.Item])
        assertEquals(1, result.skips.totalCount)
    }

    @Test
    fun `an exportable item reports nothing skipped`() {
        val result = exportResult(
            listOf(
                cxfLoginSecret(
                    login = DSecret.Login(
                        username = "alice@example.com",
                        password = "s3cr3t",
                    ),
                ),
            ),
        )
        assertTrue(result.account != null)
        assertEquals(0, result.skips.totalCount)
    }

    // region the item reason is only for losses nothing else explains

    /**
     * [CxfExportSkipReason.Item] fires exactly when an item produced nothing
     * **and** no credential reason already accounts for it — otherwise one loss
     * would be reported twice. Every case asserts the *whole* [CxfExportSkips]
     * tally, so a cause cannot leak into a neighbouring reason unnoticed.
     */
    private data class SkipCase(
        val name: String,
        val secret: DSecret,
        val expected: CxfExportSkips,
    )

    private val undecodablePasskey = cxfFido2Credential(userHandle = "###")

    private val skipCases = listOf(
        SkipCase(
            name = "a bad passkey among two keeps the item alive",
            secret = cxfLoginSecret(
                login = DSecret.Login(
                    password = "s3cr3t",
                    fido2Credentials = listOf(cxfFido2Credential(), undecodablePasskey),
                ),
            ),
            expected = cxfExportSkips(CxfExportSkipReason.Passkey to 1),
        ),
        SkipCase(
            name = "an item lost to its only bad passkey is not counted twice",
            secret = cxfLoginSecret(
                login = DSecret.Login(fido2Credentials = listOf(undecodablePasskey)),
            ),
            expected = cxfExportSkips(CxfExportSkipReason.Passkey to 1),
        ),
        SkipCase(
            // A degenerate field is a credential-level loss like any other, so
            // the item it emptied must not be counted a second time.
            name = "an item lost to its only empty-credential-id passkey is not counted twice",
            secret = cxfLoginSecret(
                login = DSecret.Login(fido2Credentials = listOf(cxfFido2Credential(credentialId = ""))),
            ),
            expected = cxfExportSkips(CxfExportSkipReason.Passkey to 1),
        ),
        SkipCase(
            // The passkey Keyguard can hold but CXF cannot carry.
            name = "a passkey with no user handle at all is counted",
            secret = cxfLoginSecret(
                login = DSecret.Login(
                    password = "s3cr3t",
                    fido2Credentials = listOf(cxfFido2Credential(userHandle = null)),
                ),
            ),
            expected = cxfExportSkips(CxfExportSkipReason.Passkey to 1),
        ),
        SkipCase(
            name = "an item lost to its only unconvertible ssh key is not counted twice",
            secret = cxfSecret(
                type = DSecret.Type.SshKey,
                sshKey = DSecret.SshKey(privateKey = "pem", publicKey = null),
            ),
            expected = cxfExportSkips(CxfExportSkipReason.SshKey to 1),
        ),
        SkipCase(
            name = "an item with no CXF representation at all is counted",
            secret = cxfSecret(type = DSecret.Type.GpgKey),
            expected = cxfExportSkips(CxfExportSkipReason.Item to 1),
        ),
        SkipCase(
            name = "a trashed item carrying everything counts nothing",
            secret = cxfSecret(
                type = DSecret.Type.Login,
                login = DSecret.Login(password = "s3cr3t", fido2Credentials = listOf(undecodablePasskey)),
                card = DSecret.Card(number = "4111"),
                notes = "n",
                sshKey = DSecret.SshKey(privateKey = "pem", publicKey = null),
                deletedDate = Instant.parse("2024-02-01T00:00:00Z"),
            ),
            expected = cxfExportSkips(),
        ),
    )

    @Test
    fun `the skipped item counter only fires for unexplained losses`() {
        skipCases.forEach { case ->
            val result = exportResult(listOf(case.secret))
            assertEquals(case.expected, result.skips, case.name)
        }
    }

    @Test
    fun `a degenerate passkey does not take the rest of its item with it`() {
        // The two rows above assert the tally; this asserts the other half —
        // the password still crosses, so refusing the passkey costs the user
        // one credential rather than the whole entry.
        val result = exportResult(
            listOf(
                cxfLoginSecret(
                    login = DSecret.Login(
                        password = "s3cr3t",
                        fido2Credentials = listOf(cxfFido2Credential(userHandle = null)),
                    ),
                ),
            ),
        )
        assertEquals(1, result.account?.items?.size)
        assertEquals(1, result.skips[CxfExportSkipReason.Passkey])
    }

    // endregion

    // region members the format has no place for

    /**
     * A GPG key, an attachment and a retained previous password are content of
     * an item that the wire cannot carry at all, so each is counted on its own
     * — otherwise an item that travels *diminished* reports nothing, since
     * [CxfExportSkipReason.Item] only fires when an item yields nothing.
     */
    private val memberSkipCases = listOf(
        SkipCase(
            // Nothing else in the item, so this is also the no-double-report
            // rule: the gpg-key row already says where the item went.
            name = "a gpg-key-only item is counted as its key, not as an item",
            secret = cxfSecret(type = DSecret.Type.GpgKey).copy(gpgKey = cxfGpgKey()),
            expected = cxfExportSkips(CxfExportSkipReason.GpgKey to 1),
        ),
        SkipCase(
            name = "a gpg member holding no armored block is no loss",
            secret = cxfSecret(type = DSecret.Type.SecureNote, notes = "n")
                .copy(gpgKey = DSecret.GpgKey(fingerprint = "ABCD")),
            expected = cxfExportSkips(),
        ),
        SkipCase(
            name = "attachments are counted per file",
            secret = cxfSecret(type = DSecret.Type.SecureNote, notes = "n")
                .copy(attachments = listOf(cxfAttachment(id = "att-1"), cxfAttachment(id = "att-2"))),
            expected = cxfExportSkips(CxfExportSkipReason.Attachment to 2),
        ),
        SkipCase(
            name = "an attachment-only item is counted as its attachment, not as an item",
            secret = cxfSecret().copy(attachments = listOf(cxfAttachment())),
            expected = cxfExportSkips(CxfExportSkipReason.Attachment to 1),
        ),
        SkipCase(
            name = "retained passwords are counted per password",
            secret = cxfLoginSecret(login = DSecret.Login(password = "s3cr3t"))
                .copy(
                    passwordHistory = listOf(
                        cxfPasswordHistory(password = "old-1"),
                        cxfPasswordHistory(password = "old-2"),
                    ),
                ),
            expected = cxfExportSkips(CxfExportSkipReason.PasswordHistory to 2),
        ),
        SkipCase(
            name = "an archived item's members are dropped with it, not reported",
            secret = cxfLoginSecret(login = DSecret.Login(password = "s3cr3t"))
                .withUnexportableMembers()
                .copy(archivedDate = Instant.parse("2024-02-01T00:00:00Z")),
            expected = cxfExportSkips(CxfExportSkipReason.Archived to 1),
        ),
        SkipCase(
            name = "a trashed item's members count nothing",
            secret = cxfLoginSecret(login = DSecret.Login(password = "s3cr3t"))
                .withUnexportableMembers()
                .copy(deletedDate = Instant.parse("2024-02-01T00:00:00Z")),
            expected = cxfExportSkips(),
        ),
    )

    @Test
    fun `a member the format has no place for is counted per member`() {
        memberSkipCases.forEach { case ->
            val result = exportResult(listOf(case.secret))
            assertEquals(case.expected, result.skips, case.name)
        }
    }

    @Test
    fun `a gpg key does not stop the rest of its item from travelling`() {
        // The regression this reason exists for: `mapNote` emits a note, the item
        // goes on the wire, `privateKeyArmored` cannot, and only the gpg-key row
        // can say so. Reachable from KeePass, whose codec attaches a detected gpg
        // key to a SecureNote-typed cipher — one that by construction has notes.
        val result = exportResult(
            listOf(
                cxfSecret(type = DSecret.Type.SecureNote, notes = "keys for work")
                    .copy(gpgKey = cxfGpgKey()),
            ),
        )
        assertEquals(1, result.account?.items?.size)
        assertEquals(cxfExportSkips(CxfExportSkipReason.GpgKey to 1), result.skips)
    }

    @Test
    fun `a member the format has no place for is counted whatever the filter asked for`() {
        // The credential-level asymmetry below does not reach these: no
        // credential type maps to a gpg key or an attachment, so no requested-type
        // set can be read as "the importer did not ask for this", and narrowing
        // the request cannot make the loss go away.
        val result = exportService.buildAccountResult(
            profile = cxfProfile(),
            ciphers = listOf(
                cxfSecret(type = DSecret.Type.SecureNote, notes = "n")
                    .copy(gpgKey = cxfGpgKey(), attachments = listOf(cxfAttachment())),
            ),
            allowedTypes = setOf(CxfCredentialType.CreditCard),
        )
        assertNull(result.account)
        assertEquals(
            cxfExportSkips(
                CxfExportSkipReason.GpgKey to 1,
                CxfExportSkipReason.Attachment to 1,
            ),
            result.skips,
        )
    }

    // endregion

    // region The filtered-out-kind accounting asymmetry

    /**
     * When a credential's kind is excluded by `allowedTypes`, the mapper never
     * runs — so a credential that *would* have failed is not counted. The user
     * asked not to transfer that kind, so staying quiet is intended.
     */
    @Test
    fun `a failing credential of a filtered out kind is not counted`() {
        val cases = listOf(
            Triple(
                "a bad passkey with passkeys excluded",
                cxfLoginSecret(
                    login = DSecret.Login(
                        password = "s3cr3t",
                        fido2Credentials = listOf(undecodablePasskey),
                    ),
                ),
                setOf(CxfCredentialType.BasicAuth),
            ),
            Triple(
                "an HOTP token with totp excluded",
                cxfLoginSecret(login = DSecret.Login(password = "s3cr3t", totp = hotpToken())),
                setOf(CxfCredentialType.BasicAuth),
            ),
            Triple(
                "an unconvertible ssh key with ssh keys excluded",
                cxfSecret(
                    type = DSecret.Type.SshKey,
                    notes = "n",
                    sshKey = DSecret.SshKey(privateKey = "pem", publicKey = null),
                ),
                setOf(CxfCredentialType.Note),
            ),
        )
        cases.forEach { (name, secret, allowedTypes) ->
            val result = exportService.buildAccountResult(
                profile = cxfProfile(),
                ciphers = listOf(secret),
                allowedTypes = allowedTypes,
            )
            assertTrue(result.account != null, name)
            assertEquals(0, result.skips.totalCount, name)
        }
    }

    @Test
    fun `an item emptied purely by the filter is not a skipped item`() {
        // The counterpart, decided the same way as the credential level above:
        // nothing was lost, the user simply asked for a kind this item does not
        // hold. Counting it would report items the format supports perfectly
        // well as losses, burying the real ones.
        val result = exportService.buildAccountResult(
            profile = cxfProfile(),
            ciphers = listOf(cxfSecret(type = DSecret.Type.SecureNote, notes = "n")),
            allowedTypes = setOf(CxfCredentialType.CreditCard),
        )
        assertNull(result.account)
        assertEquals(0, result.skips.totalCount)
    }

    @Test
    fun `an item nothing can represent is a skipped item even under a filter`() {
        // The other half: representability is decided against the whole format,
        // not against the requested subset, so narrowing the request must not
        // silence a genuine loss.
        val result = exportService.buildAccountResult(
            profile = cxfProfile(),
            ciphers = listOf(
                cxfSecret(type = DSecret.Type.GpgKey),
                cxfLoginSecret(id = "ok-1", login = DSecret.Login(password = "s3cr3t")),
            ),
            allowedTypes = setOf(CxfCredentialType.BasicAuth),
        )
        assertEquals(1, result.account?.items?.size)
        assertEquals(cxfExportSkips(CxfExportSkipReason.Item to 1), result.skips)
    }

    @Test
    fun `a filtered out kind that would have failed is still not a skipped item`() {
        // An unconvertible ssh key in an ssh-key-only item, with ssh keys
        // excluded: under the full format this item DOES have a representation
        // attempt, so the empty outcome is the filter's doing, not the format's.
        val result = exportService.buildAccountResult(
            profile = cxfProfile(),
            ciphers = listOf(
                cxfSecret(
                    type = DSecret.Type.SshKey,
                    sshKey = DSecret.SshKey(privateKey = "pem", publicKey = null),
                ),
            ),
            allowedTypes = setOf(CxfCredentialType.BasicAuth),
        )
        assertNull(result.account)
        assertEquals(0, result.skips.totalCount)
    }

    // endregion

    @Test
    fun `counters aggregate across ciphers`() {
        val result = exportResult(
            listOf(
                cxfLoginSecret(
                    id = "passkey-1",
                    login = DSecret.Login(fido2Credentials = listOf(undecodablePasskey)),
                ),
                cxfLoginSecret(id = "otp-1", login = DSecret.Login(totp = hotpToken())),
                cxfSecret(
                    id = "ssh-1",
                    type = DSecret.Type.SshKey,
                    sshKey = DSecret.SshKey(privateKey = "pem", publicKey = null),
                ),
                cxfSecret(id = "gpg-1", type = DSecret.Type.GpgKey),
                cxfLoginSecret(id = "ok-1", login = DSecret.Login(password = "s3cr3t")),
            ),
        )
        assertTrue(result.account != null)
        assertEquals(
            cxfExportSkips(
                CxfExportSkipReason.Passkey to 1,
                CxfExportSkipReason.Otp to 1,
                CxfExportSkipReason.SshKey to 1,
                CxfExportSkipReason.Item to 1,
            ),
            result.skips,
        )
        assertEquals(4, result.skips.totalCount)
    }

    // region the injected ssh export seam, which promises no totality

    private fun serviceWithSshError(error: Throwable) = CxfExportServiceImpl(
        sshKeyPkcs8Exporter = FakeSshKeyPkcs8Exporter(error = error),
    )

    private val sshKeySecret = cxfSecret(
        id = "ssh-1",
        type = DSecret.Type.SshKey,
        sshKey = DSecret.SshKey(privateKey = "pem", publicKey = "ssh-ed25519 AAAA"),
    )

    @Test
    fun `an ssh exporter that throws costs one counted skip`() {
        val result = serviceWithSshError(IllegalStateException("native backend gone"))
            .buildAccountResult(
                profile = cxfProfile(),
                ciphers = listOf(sshKeySecret),
                allowedTypes = CxfCredentialType.ALL,
            )
        assertNull(result.account)
        assertEquals(1, result.skips[CxfExportSkipReason.SshKey])
        // Not also an item: the credential reason already explains where the
        // item went, and neither is it an account-level failure.
        assertEquals(1, result.skips.totalCount)
    }

    @Test
    fun `one bad ssh key costs one credential, not the export`() {
        // A throwing native backend costs its own credential only; the sibling
        // items, and every other account, still export.
        val result = serviceWithSshError(IllegalStateException("native backend gone"))
            .buildAccountResult(
                profile = cxfProfile(),
                ciphers = listOf(
                    cxfLoginSecret(id = "pw-1", login = DSecret.Login(password = "s3cr3t")),
                    sshKeySecret,
                    cxfLoginSecret(
                        id = "pk-1",
                        login = DSecret.Login(fido2Credentials = listOf(cxfFido2Credential())),
                    ),
                ),
                allowedTypes = CxfCredentialType.ALL,
            )
        assertEquals(2, result.account?.items?.size)
        assertEquals(1, result.skips[CxfExportSkipReason.SshKey])
        assertEquals(0, result.skips[CxfExportSkipReason.Account])
    }

    @Test
    fun `a cancellation from the ssh seam is not swallowed`() {
        // Asserted through the service, because the boundary guard above the
        // seam is the layer that would otherwise eat it. Pins both guards
        // against a "simplification" to `.getOrNull()`.
        assertFailsWith<CancellationException> {
            serviceWithSshError(CancellationException("cancelled")).buildAccountResult(
                profile = cxfProfile(),
                ciphers = listOf(sshKeySecret),
                allowedTypes = CxfCredentialType.ALL,
            )
        }
    }

    @Test
    fun `a fatal error from the ssh seam is not swallowed`() {
        assertFailsWith<OutOfMemoryError> {
            serviceWithSshError(OutOfMemoryError("heap")).buildAccountResult(
                profile = cxfProfile(),
                ciphers = listOf(sshKeySecret),
                allowedTypes = CxfCredentialType.ALL,
            )
        }
    }

    // endregion

    @Test
    fun `folders alone do not produce an account`() {
        // `buildCollections` is never reached when no item survived, but the
        // counters still have to come back so the review screen can explain why.
        val result = exportService.buildAccountResult(
            profile = cxfProfile(),
            ciphers = listOf(cxfSecret(type = DSecret.Type.GpgKey)),
            allowedTypes = CxfCredentialType.ALL,
            folders = listOf(cxfFolder(id = "f1", name = "Work")),
        )
        assertNull(result.account)
        assertEquals(1, result.skips[CxfExportSkipReason.Item])
    }
}

private fun hotpToken(): DSecret.Login.Totp = DSecret.Login.Totp(
    raw = HOTP_RAW,
    token = TotpToken.HotpAuth(
        algorithm = CryptoHashAlgorithm.SHA_1,
        keyBase32 = "JBSWY3DPEHPK3PXP",
        raw = HOTP_RAW,
        digits = 6,
        counter = 1L,
    ),
)
