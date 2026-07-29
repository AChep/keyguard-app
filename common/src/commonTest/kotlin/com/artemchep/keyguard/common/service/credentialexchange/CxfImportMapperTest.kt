package com.artemchep.keyguard.common.service.credentialexchange

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.KeyPair
import com.artemchep.keyguard.common.model.TotpToken
import com.artemchep.keyguard.common.service.crypto.SshKeyImportResult
import com.artemchep.keyguard.common.service.credentialexchange.impl.CxfIdentityCredentials
import com.artemchep.keyguard.common.service.credentialexchange.impl.CxfImportSecretMapper
import com.artemchep.keyguard.common.service.credentialexchange.impl.CxfImportUserHandle
import com.artemchep.keyguard.common.service.credentialexchange.impl.MAX_ENCODED_PASSKEY_CREDENTIAL_ID_CHARS
import com.artemchep.keyguard.common.service.credentialexchange.impl.groupCxfCredentials
import com.artemchep.keyguard.common.service.credentialexchange.impl.mapImportCredentialId
import com.artemchep.keyguard.common.service.credentialexchange.impl.mapImportCreditCard
import com.artemchep.keyguard.common.service.credentialexchange.impl.mapImportIdentity
import com.artemchep.keyguard.common.service.credentialexchange.impl.mapImportKey
import com.artemchep.keyguard.common.service.credentialexchange.impl.mapImportPasskey
import com.artemchep.keyguard.common.service.credentialexchange.impl.mapImportSshKey
import com.artemchep.keyguard.common.service.credentialexchange.impl.mapImportTotpUri
import com.artemchep.keyguard.common.service.credentialexchange.impl.mapImportUserHandle
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfCredential
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfEditableField
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfItem
import com.artemchep.keyguard.common.service.webauthn.PasskeyBase64
import com.artemchep.keyguard.crypto.NativePasskeyCrypto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class CxfImportMapperTest {
    private val now = Instant.parse("2024-01-30T14:09:33Z")

    //
    // Passkey field inversions
    //

    @Test
    fun `credential id that is not base64url is rejected`() {
        assertNull(mapImportCredentialId("не-base64!"))
    }

    @Test
    fun `an oversized credential id or rp id is a counted skip, not a wedged passkey`() {
        // A megabyte-scale member imports as a "successful" passkey that can
        // never assert and that Bitwarden sync will choke on, so it has to be
        // refused here — and `buildMappedLogin` counts every null as a skip.
        val atLimit = "A".repeat(MAX_ENCODED_PASSKEY_CREDENTIAL_ID_CHARS)
        assertNotNull(mapImportCredentialId(atLimit))
        assertNull(mapImportCredentialId("A".repeat(MAX_ENCODED_PASSKEY_CREDENTIAL_ID_CHARS + 4)))

        assertNull(
            mapImportPasskey(
                passkey = cxfImportPasskey(rpId = "a".repeat(1_025)),
                creationDate = now,
                passkeyCrypto = NativePasskeyCrypto,
            ),
        )
    }

    @Test
    fun `a passkey backend that throws is a counted skip on import too`() {
        // The mirror of the export guard: `PasskeyCrypto` is injected, and a
        // backend that cannot initialize throws out of `inspect`. An escape
        // here loses the whole document instead of the one credential that
        // could not be read.
        val mapper = CxfImportSecretMapper(
            passkeyCrypto = ThrowingPasskeyCrypto(),
            sshKeyImportService = FakeSshKeyImportService(),
        )
        val result = mapper.mapItem(
            item = cxfImportItem(credentials = listOf(cxfImportPasskey())),
            now = now,
        )
        assertTrue(result.requests.isEmpty())
        assertEquals(1, result.skips[CxfImportSkipReason.Passkey])
    }

    @Test
    fun `valid P-256 key is validated and re-encoded as standard base64`() {
        assertEquals(
            CXF_TEST_PASSKEY_KEY_STANDARD,
            mapImportKey(CXF_TEST_PASSKEY_KEY_URL, NativePasskeyCrypto)?.keyValue,
        )
    }

    @Test
    fun `arbitrary base64url bytes are rejected as a passkey key`() {
        assertNull(mapImportKey("AAECAwQFBg", NativePasskeyCrypto))
    }

    @Test
    fun `key that is not base64url is rejected`() {
        assertNull(mapImportKey("###", NativePasskeyCrypto))
    }

    @Test
    fun `user handle normalizes a padded value into the unpadded form`() {
        assertEquals(
            CxfImportUserHandle.Present("AAECAwQFBg"),
            mapImportUserHandle("AAECAwQFBg=="),
        )
    }

    @Test
    fun `an undecodable user handle is a failure but an empty one is an absence`() {
        assertEquals(CxfImportUserHandle.Undecodable, mapImportUserHandle("###"))
        assertEquals(CxfImportUserHandle.Absent, mapImportUserHandle(""))
        // `""` is the only spelling that reaches the absent branch: the strict
        // alphabet refuses a padding-only value rather than decoding it to
        // nothing, so a producer cannot spell absence any other way.
        assertEquals(CxfImportUserHandle.Undecodable, mapImportUserHandle("=="))
    }

    @Test
    fun `the imported user handle enforces the WebAuthn byte limit`() {
        val atLimit = PasskeyBase64.encodeToString(ByteArray(64) { it.toByte() })
        val overLimit = PasskeyBase64.encodeToString(ByteArray(65) { it.toByte() })

        assertEquals(CxfImportUserHandle.Present(atLimit), mapImportUserHandle(atLimit))
        assertEquals(CxfImportUserHandle.Undecodable, mapImportUserHandle(overLimit))
    }

    @Test
    fun `passkey with an undecodable member is a counted skip`() {
        val passkey = cxfImportPasskey(
            key = "###",
        )
        assertNull(
            mapImportPasskey(
                passkey = passkey,
                creationDate = now,
                passkeyCrypto = NativePasskeyCrypto,
            ),
        )
    }

    @Test
    fun `passkey with an empty user handle is kept, with no user handle`() {
        // Deliberately asymmetric with the export direction: `""` on the wire is
        // how a producer with no way to spell absence says "no user handle", and
        // reading it as a failure would throw away a credential id, a private key
        // and an rp id that are all present and valid.
        val credential = mapImportPasskey(
            passkey = cxfImportPasskey(userHandle = ""),
            creationDate = now,
            passkeyCrypto = NativePasskeyCrypto,
        )
        assertNotNull(credential)
        assertNull(credential.userHandle)
        assertEquals("AAECAwQFBg", credential.credentialId)
        assertEquals(CXF_TEST_PASSKEY_KEY_STANDARD, credential.keyValue)
        assertEquals("example.com", credential.rpId)
    }

    @Test
    fun `a kept passkey with no user handle is not marked discoverable`() {
        // `discoverable` is the flag that lets Keyguard answer a request with no
        // `allowCredentials`, and the assertion it then produces omits
        // `userHandle` — which WebAuthn L3 §7.2 step 6 makes the relying party
        // abort on. A handle-less credential must not advertise itself for the
        // one ceremony it cannot complete. A handle-carrying passkey keeps the
        // WebAuthn default; see `passkey maps with the webauthn defaults
        // assumed`.
        val credential = mapImportPasskey(
            passkey = cxfImportPasskey(userHandle = ""),
            creationDate = now,
            passkeyCrypto = NativePasskeyCrypto,
        )
        assertEquals(false, assertNotNull(credential).discoverable)
    }

    @Test
    fun `passkey maps with the webauthn defaults assumed`() {
        val credential = mapImportPasskey(
            passkey = cxfImportPasskey(),
            creationDate = now,
            passkeyCrypto = NativePasskeyCrypto,
        )
        assertNotNull(credential)
        assertEquals("public-key", credential.keyType)
        assertEquals("ECDSA", credential.keyAlgorithm)
        assertEquals("P-256", credential.keyCurve)
        assertEquals(0, credential.counter)
        assertEquals(true, credential.discoverable)
        assertEquals(CXF_TEST_PASSKEY_KEY_STANDARD, credential.keyValue)
        assertEquals("alice", credential.userName)
        assertEquals(now, credential.creationDate)
    }

    //
    // TOTP
    //

    @Test
    fun `totp with custom parameters keeps them in the uri`() {
        val uri = mapImportTotpUri(
            CxfCredential.Totp(
                secret = "JBSWY3DPEHPK3PXP",
                period = 60,
                digits = 8,
                algorithm = "SHA256",
                username = null,
                issuer = null,
            ),
        )
        assertEquals(
            "otpauth://totp/?secret=JBSWY3DPEHPK3PXP&algorithm=SHA256&digits=8&period=60",
            uri,
        )
        val token = TotpToken.parse(uri!!).getOrNull()
        val totp = token as TotpToken.TotpAuth
        assertEquals(8, totp.digits)
        assertEquals(60L, totp.period)
    }

    @Test
    fun `totp with the exact steam algorithm becomes a steam token`() {
        // The exact-string grid lives in `CxfImportTotpUriMatrixTest`; what is
        // unique here is that the produced uri parses back as a Steam token
        // rather than an otpauth one.
        val uri = mapImportTotpUri(
            CxfCredential.Totp(
                secret = "JBSWY3DPEHPK3PXP",
                period = 30,
                digits = 5,
                algorithm = "steam",
            ),
        )
        assertEquals("steam://JBSWY3DPEHPK3PXP", uri)
        assertTrue(TotpToken.parse(uri!!).getOrNull() is TotpToken.SteamAuth)
    }

    //
    // Card
    //

    @Test
    fun `credit card splits the expiry and parks the pin and validity in fields`() {
        val imported = mapImportCreditCard(
            CxfCredential.CreditCard(
                number = concealed("4111111111111111"),
                fullName = plain("Alice Example"),
                cardType = plain("Visa"),
                verificationNumber = concealed("123"),
                pin = concealed("0000"),
                expiryDate = CxfEditableField(
                    fieldType = CxfEditableField.FIELD_TYPE_YEAR_MONTH,
                    value = "2027-05",
                ),
                validFrom = CxfEditableField(
                    fieldType = CxfEditableField.FIELD_TYPE_YEAR_MONTH,
                    value = "not-a-date",
                ),
            ),
        )
        val card = imported.card
        assertEquals("2027", card.expYear)
        assertEquals("5", card.expMonth)
        assertEquals("Visa", card.brand)
        // The two members with no vault slot: `AddCipher` stores neither a pin
        // nor `fromMonth`/`fromYear`, so both travel as fields — and the
        // validity keeps the source spelling verbatim, so an unusable
        // year-month is still carried as text instead of being dropped.
        assertNull(card.fromYear)
        assertNull(card.fromMonth)
        assertEquals(
            listOf(
                Triple("PIN", "0000", DSecret.Field.Type.Hidden),
                Triple("Valid from", "not-a-date", DSecret.Field.Type.Text),
            ),
            imported.fields.map { Triple(it.name, it.value, it.type) },
        )
    }

    @Test
    fun `a card carrying only a validity start is not an empty card`() {
        // Every `credit-card` member is optional, so this is a conforming
        // credential. Before the validity moved into a field the mapper wrote it
        // into `CreateRequest.Card.fromMonth`, which made the card look
        // non-empty to `isEmpty` while `AddCipher` dropped the value — a blank
        // Card cipher that also suppressed the item counter. Now the data is
        // what makes the card worth creating.
        val imported = mapImportCreditCard(
            CxfCredential.CreditCard(
                validFrom = CxfEditableField(
                    fieldType = CxfEditableField.FIELD_TYPE_YEAR_MONTH,
                    value = "2024-01",
                ),
            ),
        )
        assertFalse(imported.isEmpty)
        assertEquals("2024-01", imported.fields.single().value)
    }

    @Test
    fun `a card whose only member is an unusable validity start is empty`() {
        // The complement: nothing survives the field mapping, so there is
        // nothing to create a cipher for and the item counter must fire.
        val imported = mapImportCreditCard(
            CxfCredential.CreditCard(
                validFrom = CxfEditableField(
                    fieldType = CxfEditableField.FIELD_TYPE_YEAR_MONTH,
                    value = "   ",
                ),
            ),
        )
        assertTrue(imported.isEmpty)
    }

    //
    // Identity merge
    //

    private fun mergedIdentityFixture() = mapImportIdentity(
        credentials = CxfIdentityCredentials(
            personName = CxfCredential.PersonName(
                title = plain("Dr"),
                given = plain("Alice"),
                given2 = plain("Betty"),
                surname = plain("Example"),
            ),
            address = CxfCredential.Address(
                streetAddress = plain("1 Main St\nApt 2"),
                city = plain("Springfield"),
                territory = plain("OR"),
                postalCode = plain("97477"),
                country = plain("US"),
                tel = plain("555-0100"),
            ),
            passport = CxfCredential.Passport(
                passportNumber = concealed("P123456"),
                nationalIdentificationNumber = concealed("078-05-1120"),
                sex = plain("F"),
            ),
            driversLicense = CxfCredential.DriversLicense(
                licenseNumber = concealed("DL123456"),
                territory = plain("CA"),
            ),
        ),
        customFields = listOf(
            CxfEditableField(
                fieldType = CxfEditableField.FIELD_TYPE_EMAIL,
                value = "alice@example.com",
                label = "Email",
            ),
            CxfEditableField(
                fieldType = CxfEditableField.FIELD_TYPE_STRING,
                value = "Acme",
                label = "Company",
            ),
            CxfEditableField(
                fieldType = CxfEditableField.FIELD_TYPE_STRING,
                value = "unrelated",
                label = "Other",
            ),
        ),
    )

    @Test
    fun `identity re-merges the split credentials into one identity`() {
        val imported = mergedIdentityFixture()
        val identity = imported.identity
        assertEquals("Dr", identity.title)
        assertEquals("Alice", identity.firstName)
        assertEquals("Betty", identity.middleName)
        assertEquals("Example", identity.lastName)
        assertEquals("1 Main St", identity.address1)
        assertEquals("Apt 2", identity.address2)
        assertNull(identity.address3)
        assertEquals("Springfield", identity.city)
        assertEquals("OR", identity.state)
        assertEquals("97477", identity.postalCode)
        assertEquals("US", identity.country)
        assertEquals("555-0100", identity.phone)
        assertEquals("P123456", identity.passportNumber)
        assertEquals("078-05-1120", identity.ssn)
        assertEquals("DL123456", identity.licenseNumber)
        // The labelled custom fields are absorbed into their free identity slots.
        assertEquals("alice@example.com", identity.email)
        assertEquals("Acme", identity.company)
        // The license territory lost the state slot to the address and is
        // preserved as a field, together with the passport sex member.
        assertTrue(imported.fields.any { it.name == "License territory" && it.value == "CA" })
        assertTrue(imported.fields.any { it.name == "Sex" && it.value == "F" })
        // Not a recognized identity label -> passes through.
        assertEquals("Other", imported.remainingCustomFields.single().label)
    }

    @Test
    fun `identity splits a document full name when no person name exists`() {
        val imported = mapImportIdentity(
            credentials = CxfIdentityCredentials(
                passport = CxfCredential.Passport(
                    fullName = plain("Alice Betty Example"),
                ),
            ),
            customFields = emptyList(),
        )
        assertEquals("Alice", imported.identity.firstName)
        assertEquals("Betty Example", imported.identity.lastName)
    }

    @Test
    fun `identity custom field does not overwrite a taken slot`() {
        val imported = mapImportIdentity(
            credentials = CxfIdentityCredentials(
                personName = CxfCredential.PersonName(
                    credentials = plain("PhD"),
                ),
            ),
            customFields = listOf(
                CxfEditableField(
                    fieldType = CxfEditableField.FIELD_TYPE_STRING,
                    value = "Acme",
                    label = "Company",
                ),
            ),
        )
        // The person-name credentials member claimed the company slot first.
        assertEquals("PhD", imported.identity.company)
        assertEquals("Acme", imported.remainingCustomFields.single().value)
    }

    //
    // SSH keys
    //

    @Test
    fun `ssh key wraps the der into a pkcs8 pem before importing`() {
        val service = FakeSshKeyImportService()
        val imported = mapImportSshKey(
            credential = CxfCredential.SshKey(
                keyType = "ssh-ed25519",
                privateKey = "AAECAwQFBg",
                keyComment = "work laptop",
            ),
            sshKeyImportService = service,
        )
        assertNotNull(imported)
        val expectedPem = "-----BEGIN PRIVATE KEY-----\n" +
            "AAECAwQFBg==\n" +
            "-----END PRIVATE KEY-----\n"
        assertEquals(expectedPem, service.lastRequest?.content)
        val commentField = imported.fields.single()
        assertEquals("Key comment", commentField.name)
        assertEquals("work laptop", commentField.value)
    }

    @Test
    fun `ssh key accepts every CXF RSA algorithm identifier`() {
        listOf("ssh-rsa", "rsa-sha2-256", "rsa-sha2-512").forEach { keyType ->
            val service = FakeSshKeyImportService(
                result = SshKeyImportResult.Success(
                    fakeSshKeyPair(
                        type = KeyPair.Type.RSA,
                        publicKeyOpenSsh = "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQ",
                    ),
                ),
            )

            assertNotNull(
                mapImportSshKey(
                    credential = CxfCredential.SshKey(
                        keyType = keyType,
                        privateKey = "AAECAwQFBg",
                    ),
                    sshKeyImportService = service,
                ),
                keyType,
            )
        }
    }

    @Test
    fun `ssh key rejects a declared algorithm that disagrees with the private key`() {
        val service = FakeSshKeyImportService()

        assertNull(
            mapImportSshKey(
                credential = CxfCredential.SshKey(
                    keyType = "ssh-rsa",
                    privateKey = "AAECAwQFBg",
                ),
                sshKeyImportService = service,
            ),
        )
        assertEquals(1, service.callCount)
    }

    @Test
    fun `ssh key rejects an unsupported algorithm before decoding the private key`() {
        val service = FakeSshKeyImportService()

        assertNull(
            mapImportSshKey(
                credential = CxfCredential.SshKey(
                    keyType = "ecdsa-sha2-nistp256",
                    privateKey = "AAECAwQFBg",
                ),
                sshKeyImportService = service,
            ),
        )
        assertEquals(0, service.callCount)
    }

    @Test
    fun `ssh key that fails to convert is a counted skip`() {
        val service = FakeSshKeyImportService(
            result = sshKeyImportFailure(),
        )
        val imported = mapImportSshKey(
            credential = CxfCredential.SshKey(
                keyType = "ssh-ed25519",
                privateKey = "AAECAwQFBg",
            ),
            sshKeyImportService = service,
        )
        assertNull(imported)
    }

    //
    // Grouping / combination
    //

    @Test
    fun `grouping keeps the first of a single-instance kind and counts the rest`() {
        val grouped = groupCxfCredentials(
            listOf(
                CxfCredential.BasicAuth(username = plain("first")),
                CxfCredential.BasicAuth(username = plain("second")),
                cxfImportPasskey(),
                cxfImportPasskey(credentialId = "AQECAwQFBg"),
            ),
        )
        assertEquals("first", grouped.basicAuth?.username?.value)
        assertEquals(2, grouped.passkeys.size)
        assertEquals(1, grouped.duplicateCount)
    }

    @Test
    fun `grouping prefers a populated occurrence over a content-free earlier one`() {
        // Every member of these kinds is optional, so `{"type":"basic-auth"}` is
        // conforming and carries nothing. Keeping it purely because it came first
        // would lose the populated sibling's password and card number under a
        // DuplicateCredential count, whose contract says nothing of value was
        // lost.
        val grouped = groupCxfCredentials(
            listOf(
                CxfCredential.BasicAuth(),
                CxfCredential.BasicAuth(username = plain("alice"), password = concealed("hunter2")),
                CxfCredential.CreditCard(),
                CxfCredential.CreditCard(number = concealed("4111111111111111")),
                CxfCredential.PersonName(),
                CxfCredential.PersonName(given = plain("Alice")),
            ),
        )
        assertEquals("alice", grouped.basicAuth?.username?.value)
        assertEquals("hunter2", grouped.basicAuth?.password?.value)
        assertEquals("4111111111111111", grouped.creditCard?.number?.value)
        assertEquals("Alice", grouped.identity.personName?.given?.value)
        // Still exactly one duplicate per kind — the empty one is the extra.
        assertEquals(3, grouped.duplicateCount)
    }

    @Test
    fun `grouping keeps the first when neither occurrence is content-free`() {
        // Arrival order remains the tie-break; only a content-free incumbent
        // steps aside.
        val grouped = groupCxfCredentials(
            listOf(
                CxfCredential.BasicAuth(username = plain("first")),
                CxfCredential.BasicAuth(username = plain("second"), password = concealed("s")),
                CxfCredential.BasicAuth(),
            ),
        )
        assertEquals("first", grouped.basicAuth?.username?.value)
        assertEquals(2, grouped.duplicateCount)
    }

    @Test
    fun `a content-free identity credential produces no identity request`() {
        // The identity needs the post-mapping gate `buildCard` and
        // `buildMappedLogin` have: `CxfIdentityCredentials.isEmpty` only asks
        // whether one of the five identity-shaped credential OBJECTS was present,
        // so without it a member-less one materialises a wholly blank Identity in
        // the vault and keeps the item alive, suppressing the Item counter.
        val mapper = CxfImportSecretMapper(FakeSshKeyImportService())
        listOf(
            CxfCredential.PersonName(),
            CxfCredential.Address(),
            CxfCredential.Passport(),
            CxfCredential.DriversLicense(),
            CxfCredential.IdentityDocument(),
            // Present but blank: the natural output of a naive exporter
            // serializing an empty form.
            CxfCredential.PersonName(given = plain("   ")),
        ).forEach { credential ->
            val result = mapper.mapItem(
                item = cxfImportItem(credentials = listOf(credential)),
                now = now,
            )
            assertTrue(result.requests.isEmpty(), credential.toString())
            assertEquals(0, result.skips.totalCount, credential.toString())
        }
    }

    @Test
    fun `a content-free identity credential does not resurrect an item beside a failed passkey`() {
        // Without the gate the phantom identity is what keeps the item alive, so
        // the vault gains a blank record for an item that lost its only real
        // credential.
        val mapper = CxfImportSecretMapper(FakeSshKeyImportService())
        val result = mapper.mapItem(
            item = cxfImportItem(
                credentials = listOf(
                    cxfImportPasskey(rpId = "   "),
                    CxfCredential.PersonName(),
                ),
            ),
            now = now,
        )
        assertTrue(result.requests.isEmpty())
        assertEquals(1, result.skips[CxfImportSkipReason.Passkey])
    }

    @Test
    fun `an identity credential keeps its leftover custom fields when it maps to nothing`() {
        val mapper = CxfImportSecretMapper(FakeSshKeyImportService())
        val result = mapper.mapItem(
            item = cxfImportItem(
                credentials = listOf(
                    CxfCredential.PersonName(),
                    CxfCredential.CustomFields(
                        fields = listOf(
                            CxfEditableField(
                                fieldType = "string",
                                value = "v",
                                label = "Nickname",
                            ),
                        ),
                    ),
                ),
            ),
            now = now,
        )
        val request = result.requests.single()
        assertEquals(DSecret.Type.SecureNote, request.type)
        assertEquals(listOf("Nickname"), request.fields.map { it.name })
    }

    @Test
    fun `an item with no representable credential yields nothing and counts nothing at this layer`() {
        // The item-level decision belongs to `CxfImportServiceImpl.parseItems`,
        // which is the only layer that can also see the credentials the decoder
        // rejected before the mapper ever ran. The mapper reports credential
        // reasons only.
        val mapper = CxfImportSecretMapper(FakeSshKeyImportService())
        val result = mapper.mapItem(
            item = cxfImportItem(
                credentials = emptyList(),
            ),
            now = now,
        )
        assertTrue(result.requests.isEmpty())
        assertTrue(result.skips.isEmpty)
    }
}

private fun plain(value: String) = CxfEditableField(
    fieldType = CxfEditableField.FIELD_TYPE_STRING,
    value = value,
)

private fun concealed(value: String) = CxfEditableField(
    fieldType = CxfEditableField.FIELD_TYPE_CONCEALED_STRING,
    value = value,
)

fun cxfImportPasskey(
    credentialId: String = "AAECAwQFBg",
    rpId: String = "example.com",
    username: String = "alice",
    userDisplayName: String = "Alice",
    userHandle: String = "AAECAwQFBg",
    key: String = CXF_TEST_PASSKEY_KEY_URL,
) = CxfCredential.Passkey(
    credentialId = credentialId,
    rpId = rpId,
    username = username,
    userDisplayName = userDisplayName,
    userHandle = userHandle,
    key = key,
)

fun cxfImportItem(
    id: String = "aXRlbS0x",
    title: String = "Example",
    credentials: List<CxfCredential>,
) = CxfItem(
    id = id,
    title = title,
    credentials = credentials,
)
