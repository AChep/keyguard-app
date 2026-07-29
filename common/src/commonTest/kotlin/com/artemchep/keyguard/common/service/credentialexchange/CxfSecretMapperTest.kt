package com.artemchep.keyguard.common.service.credentialexchange

import com.artemchep.keyguard.common.model.CryptoHashAlgorithm
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.KeyPair
import com.artemchep.keyguard.common.model.TotpToken
import com.artemchep.keyguard.common.service.credentialexchange.impl.CxfSecretMapper
import com.artemchep.keyguard.common.service.credentialexchange.impl.mapBasicAuth
import com.artemchep.keyguard.common.service.credentialexchange.impl.mapCreditCard
import com.artemchep.keyguard.common.service.credentialexchange.impl.mapCustomFields
import com.artemchep.keyguard.common.service.credentialexchange.impl.mapIdentityCredentials
import com.artemchep.keyguard.common.service.credentialexchange.impl.mapNote
import com.artemchep.keyguard.common.service.credentialexchange.impl.mapTotp
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfCredential
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfCredentialType
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfEditableField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CxfSecretMapperTest {
    private val fixedDer = byteArrayOf(1, 2, 3, 4, 5, 6)

    private val mapper = CxfSecretMapper(
        sshKeyPkcs8Exporter = FakeSshKeyPkcs8Exporter(fixedDer),
    )

    //
    // Passkey
    //

    @Test
    fun `passkey username falls back to display name`() {
        val passkey = mapper.mapPasskey(cxfFido2Credential(userName = null, userDisplayName = "Bob"))
        assertEquals("Bob", passkey?.username)
        assertEquals("Bob", passkey?.userDisplayName)
    }

    @Test
    fun `passkey display name falls back to username`() {
        val passkey = mapper.mapPasskey(cxfFido2Credential(userName = "carol", userDisplayName = null))
        assertEquals("carol", passkey?.username)
        assertEquals("carol", passkey?.userDisplayName)
    }

    @Test
    fun `passkey names default to empty string when both are absent`() {
        val passkey = mapper.mapPasskey(cxfFido2Credential(userName = null, userDisplayName = null))
        assertEquals("", passkey?.username)
        assertEquals("", passkey?.userDisplayName)
    }

    //
    // TOTP
    //

    @Test
    fun `totp maps a TotpAuth token`() {
        val totp = mapTotp(cxfTotpAuth(period = 60L, digits = 8).token)
        assertEquals("JBSWY3DPEHPK3PXP", totp?.secret)
        assertEquals(60, totp?.period)
        assertEquals(8, totp?.digits)
        assertEquals(CxfCredential.Totp.ALGORITHM_SHA1, totp?.algorithm)
    }

    @Test
    fun `totp with a period beyond Int range is skipped instead of overflowing`() {
        assertNull(mapTotp(cxfTotpAuth(period = Int.MAX_VALUE + 1L).token))
    }

    @Test
    fun `totp takes username and issuer from the otpauth token`() {
        val totp = mapTotp(
            token = cxfTotpAuth(username = "alice", issuer = "ACME").token,
            fallbackUsername = "ignored",
        )
        assertEquals("alice", totp?.username)
        assertEquals("ACME", totp?.issuer)
    }

    @Test
    fun `totp username falls back to the login username`() {
        val totp = mapTotp(
            token = cxfTotpAuth().token,
            fallbackUsername = "alice@example.com",
        )
        assertEquals("alice@example.com", totp?.username)
        // The issuer deliberately has no fallback.
        assertNull(totp?.issuer)
    }

    @Test
    fun `totp omits a blank fallback username`() {
        val totp = mapTotp(
            token = cxfTotpAuth().token,
            fallbackUsername = "  ",
        )
        assertNull(totp?.username)
    }

    @Test
    fun `totp skips counter-based and mobile otp variants`() {
        val hotp = TotpToken.HotpAuth(
            algorithm = CryptoHashAlgorithm.SHA_1,
            keyBase32 = "JBSWY3DPEHPK3PXP",
            raw = "otpauth://hotp/test?secret=JBSWY3DPEHPK3PXP&counter=1",
            digits = 6,
            counter = 1L,
        )
        assertNull(mapTotp(hotp))

        val motp = TotpToken.MobileAuth(
            issuer = "Example",
            username = "alice",
            secret = "JBSWY3DPEHPK3PXP",
            pin = "1234",
            raw = "motp://Example:alice?secret=JBSWY3DPEHPK3PXP&pin=1234",
        )
        assertNull(mapTotp(motp))
    }

    @Test
    fun `steam exports as the steam extension value`() {
        val totp = mapTotp(cxfSteamTotp().token, fallbackUsername = "alice")
        assertEquals("JBSWY3DPEHPK3PXP", totp?.secret)
        assertEquals(30, totp?.period)
        assertEquals(5, totp?.digits)
        assertEquals(CxfCredential.Totp.ALGORITHM_STEAM, totp?.algorithm)
        // SteamAuth holds neither, so the enclosing login is the only source
        // of a username and there is no issuer at all.
        assertEquals("alice", totp?.username)
        assertNull(totp?.issuer)
    }

    @Test
    fun `steam with a non-base32 secret is still a skip`() {
        val steam = TotpToken.SteamAuth(
            algorithm = CryptoHashAlgorithm.SHA_1,
            keyBase32 = "not base32!",
            raw = "steam://not base32!",
        )
        assertNull(mapTotp(steam))
    }

    @Test
    fun `a digit count the parser cannot represent is skipped`() {
        // Reachable only by direct construction, but it is what makes `digits`
        // a shared predicate rather than a pass-through.
        listOf(0, 10).forEach { digits ->
            assertNull(
                mapTotp(cxfTotpAuth(digits = digits).token),
                "digits: $digits",
            )
        }
    }

    //
    // Basic-auth
    //

    @Test
    fun `basic auth omits a blank username`() {
        val basicAuth = mapBasicAuth(DSecret.Login(username = "   ", password = "hunter2"))
        assertNull(basicAuth?.username)
        assertEquals("hunter2", basicAuth?.password?.value)
    }

    @Test
    fun `basic auth keeps a whitespace only password`() {
        // A password of only whitespace is still the secret that logs the user
        // in. Dropping it here would lose it with no counted skip, since the
        // credential still travels on the strength of the username.
        val basicAuth = mapBasicAuth(DSecret.Login(username = "alice@example.com", password = "  "))
        assertEquals(CxfEditableField.FIELD_TYPE_STRING, basicAuth?.username?.fieldType)
        assertEquals("alice@example.com", basicAuth?.username?.value)
        assertEquals(CxfEditableField.FIELD_TYPE_CONCEALED_STRING, basicAuth?.password?.fieldType)
        assertEquals("  ", basicAuth?.password?.value)
    }

    @Test
    fun `basic auth is null when both fields are blank`() {
        assertNull(mapBasicAuth(DSecret.Login(username = "", password = null)))
    }

    //
    // Credit card
    //

    @Test
    fun `credit card maps fields and year-month expiry`() {
        val card = mapCreditCard(
            DSecret.Card(
                cardholderName = "John Doe",
                brand = "Visa",
                number = "4111111111111111",
                code = "123",
                expMonth = "12",
                expYear = "2025",
                fromMonth = "1",
                fromYear = "2024",
            ),
        )
        assertEquals("4111111111111111", card?.number?.value)
        assertEquals(CxfEditableField.FIELD_TYPE_CONCEALED_STRING, card?.number?.fieldType)
        assertEquals("John Doe", card?.fullName?.value)
        assertEquals("Visa", card?.cardType?.value)
        assertEquals("123", card?.verificationNumber?.value)
        assertEquals(CxfEditableField.FIELD_TYPE_CONCEALED_STRING, card?.verificationNumber?.fieldType)
        assertEquals("2025-12", card?.expiryDate?.value)
        assertEquals(CxfEditableField.FIELD_TYPE_YEAR_MONTH, card?.expiryDate?.fieldType)
        assertEquals("2024-01", card?.validFrom?.value)
    }

    @Test
    fun `credit card is null when empty and drops invalid expiry`() {
        assertNull(mapCreditCard(DSecret.Card()))
        val card = mapCreditCard(DSecret.Card(number = "4111", expMonth = "13", expYear = "2025"))
        assertEquals("4111", card?.number?.value)
        assertNull(card?.expiryDate)
    }

    @Test
    fun `credit card shifts a two-digit expiry year into the current century`() {
        val card = mapCreditCard(DSecret.Card(number = "4111", expMonth = "6", expYear = "25"))
        assertEquals("2025-06", card?.expiryDate?.value)
    }

    //
    // Identity
    //

    @Test
    fun `identity splits into person-name address and custom-fields`() {
        val credentials = mapIdentityCredentials(
            DSecret.Identity(
                title = "Dr.",
                firstName = "John",
                middleName = "Michael",
                lastName = "Doe",
                address1 = "123 Main St",
                address2 = "Apt 456",
                city = "Anytown",
                state = "CA",
                postalCode = "12345",
                country = "US",
                phone = "+1234567890",
                company = "ACME",
                email = "john@example.com",
                ssn = "123-45-6789",
                username = "johndoe",
                passportNumber = "P123456789",
                licenseNumber = "DL123456",
            ),
            allowedTypes = CxfCredentialType.ALL,
        )

        val personName = credentials.filterIsInstance<CxfCredential.PersonName>().single()
        assertEquals("Dr.", personName.title?.value)
        assertEquals("John", personName.given?.value)
        assertEquals("Michael", personName.given2?.value)
        assertEquals("Doe", personName.surname?.value)

        val address = credentials.filterIsInstance<CxfCredential.Address>().single()
        assertEquals("123 Main St\nApt 456", address.streetAddress?.value)
        assertEquals("Anytown", address.city?.value)
        assertEquals("CA", address.territory?.value)
        assertEquals(CxfEditableField.FIELD_TYPE_SUBDIVISION_CODE, address.territory?.fieldType)
        assertEquals("US", address.country?.value)
        assertEquals(CxfEditableField.FIELD_TYPE_COUNTRY_CODE, address.country?.fieldType)
        assertEquals("+1234567890", address.tel?.value)
        assertEquals("12345", address.postalCode?.value)

        val custom = credentials.filterIsInstance<CxfCredential.CustomFields>().single()
        val labels = custom.fields.map { it.label }
        assertEquals(
            listOf("Company", "Email", "Username", "Social Security Number", "Passport Number", "License Number"),
            labels,
        )
        val email = custom.fields.single { it.label == "Email" }
        assertEquals(CxfEditableField.FIELD_TYPE_EMAIL, email.fieldType)
        val ssn = custom.fields.single { it.label == "Social Security Number" }
        assertEquals(CxfEditableField.FIELD_TYPE_CONCEALED_STRING, ssn.fieldType)
    }

    @Test
    fun `identity gating drops credentials not in allowed types`() {
        val credentials = mapIdentityCredentials(
            DSecret.Identity(firstName = "Jane", city = "Town", email = "j@e.com"),
            allowedTypes = setOf(CxfCredentialType.Address),
        )
        assertEquals(1, credentials.size)
        assertIs<CxfCredential.Address>(credentials.single())
    }

    //
    // Note + custom fields
    //

    @Test
    fun `note maps non-blank content and drops blank`() {
        assertEquals("Hello", mapNote("Hello")?.content?.value)
        assertNull(mapNote("   "))
    }

    @Test
    fun `custom fields map by type and drop linked`() {
        val fields = listOf(
            DSecret.Field(name = "Text", value = "A", type = DSecret.Field.Type.Text),
            DSecret.Field(name = "Hidden", value = "B", type = DSecret.Field.Type.Hidden),
            DSecret.Field(name = "Flag", value = "true", type = DSecret.Field.Type.Boolean),
            DSecret.Field(name = "Link", value = null, type = DSecret.Field.Type.Linked),
        )
        val custom = mapCustomFields(fields)
        assertEquals(3, custom?.fields?.size)
        assertEquals(CxfEditableField.FIELD_TYPE_STRING, custom?.fields?.get(0)?.fieldType)
        assertEquals(CxfEditableField.FIELD_TYPE_CONCEALED_STRING, custom?.fields?.get(1)?.fieldType)
        assertEquals(CxfEditableField.FIELD_TYPE_BOOLEAN, custom?.fields?.get(2)?.fieldType)
        assertNull(mapCustomFields(emptyList()))
    }

    //
    // Item assembly + selection rule
    //

    @Test
    fun `item drops all credentials except the requested type`() {
        val secret = cxfLoginSecret(
            login = DSecret.Login(
                username = "alice@example.com",
                password = "s3cr3t",
                fido2Credentials = listOf(cxfFido2Credential()),
                totp = cxfTotpAuth(),
            ),
        )
        val credentials = mapper.buildItem(secret, setOf(CxfCredentialType.Passkey)).item?.credentials.orEmpty()
        assertEquals(1, credentials.size)
        assertIs<CxfCredential.Passkey>(credentials.single())
    }

    @Test
    fun `ALL allowed types exports every credential kind`() {
        val secret = cxfLoginSecret(
            notes = "a note",
            login = DSecret.Login(
                username = "alice@example.com",
                password = "s3cr3t",
                fido2Credentials = listOf(cxfFido2Credential()),
                totp = cxfTotpAuth(),
            ),
        )
        // Spelled out kind by kind, in `collectCredentials`' own order: a count
        // alone cannot tell four notes from a real mix.
        assertEquals(
            listOf(
                CxfCredential.Passkey::class,
                CxfCredential.BasicAuth::class,
                CxfCredential.Totp::class,
                CxfCredential.Note::class,
            ),
            mapper.buildItem(secret, CxfCredentialType.ALL).item?.credentials?.map { it::class },
        )
    }

    @Test
    fun `ssh key uses the validated exporter type and forwards the public key`() {
        val exporter = FakeSshKeyPkcs8Exporter(
            der = fixedDer,
            type = KeyPair.Type.RSA,
        )
        val sshMapper = CxfSecretMapper(sshKeyPkcs8Exporter = exporter)
        val secret = cxfSecret(
            type = DSecret.Type.SshKey,
            sshKey = DSecret.SshKey(
                privateKey = "-----BEGIN OPENSSH PRIVATE KEY-----\nx\n-----END OPENSSH PRIVATE KEY-----",
                publicKey = "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQ work-laptop",
                fingerprint = "SHA256:abc",
            ),
        )
        val result = sshMapper.buildItem(secret, CxfCredentialType.ALL)
        val ssh = result.item?.credentials?.filterIsInstance<CxfCredential.SshKey>()?.single()
        assertEquals("ssh-rsa", ssh?.keyType)
        // base64url of the fixed DER [1, 2, 3, 4, 5, 6].
        assertEquals("AQIDBAUG", ssh?.privateKey)
        assertEquals(secret.sshKey?.publicKey, exporter.lastPublicKey)
        assertEquals(0, result.skips[CxfExportSkipReason.SshKey])
    }

    @Test
    fun `ssh key is skipped when the private key is missing`() {
        val secret = cxfSecret(
            type = DSecret.Type.SshKey,
            sshKey = DSecret.SshKey(privateKey = null, publicKey = "ssh-ed25519 AAAA"),
        )
        val result = mapper.buildItem(secret, CxfCredentialType.ALL)
        assertNull(result.item)
        assertEquals(1, result.skips[CxfExportSkipReason.SshKey])
    }

    /**
     * The otp half is deliberately HOTP, not Steam: Steam exports as the `steam`
     * extension value (pinned deviation D8), so a Steam fixture here would not be
     * a skip at all. HOTP has no CXF representation.
     */
    @Test
    fun `skipped counts surface non-representable otp and bad passkeys`() {
        val secret = cxfLoginSecret(
            login = DSecret.Login(
                fido2Credentials = listOf(
                    cxfFido2Credential(),
                    cxfFido2Credential(userHandle = "###"),
                ),
                totp = cxfHotpAuth(),
            ),
        )
        val result = mapper.buildItem(secret, CxfCredentialType.ALL)
        assertEquals(1, result.skips[CxfExportSkipReason.Passkey])
        assertEquals(1, result.skips[CxfExportSkipReason.Otp])
        assertEquals(1, result.item?.credentials?.size)
        assertTrue(result.item?.credentials?.single() is CxfCredential.Passkey)
    }
}
