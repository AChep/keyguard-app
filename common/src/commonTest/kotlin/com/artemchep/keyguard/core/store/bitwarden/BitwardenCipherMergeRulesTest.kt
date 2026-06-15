package com.artemchep.keyguard.core.store.bitwarden

import com.artemchep.keyguard.common.service.patch.ModelDiffUtil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class BitwardenCipherMergeRulesTest {
    @Test
    fun `merge keeps local password rotation and remote totp setup`() {
        val oldRemote = loginCipher(
            password = "mail-password-2023",
            passwordRevisionDate = PASSWORD_REVISION_OLD,
        )
        val currentLocal = oldRemote.copy(
            revisionDate = LOCAL_REVISION,
            login = oldRemote.login?.copy(
                password = "mail-password-2024-from-phone",
                passwordRevisionDate = PASSWORD_REVISION_LOCAL,
            ),
        )
        val currentRemote = oldRemote.copy(
            revisionDate = REMOTE_REVISION,
            login = oldRemote.login?.copy(
                totp = TOTP,
            ),
        )

        val merged = merge(
            oldRemote = oldRemote,
            currentLocal = currentLocal,
            currentRemote = currentRemote,
        )

        assertEquals("mail-password-2024-from-phone", merged.login?.password)
        assertEquals(PASSWORD_REVISION_LOCAL, merged.login?.passwordRevisionDate)
        assertEquals(TOTP, merged.login?.totp)
    }

    @Test
    fun `merge keeps local reprompt enable and remote notes edit`() {
        val oldRemote = loginCipher(
            reprompt = BitwardenCipher.RepromptType.None,
        )
        val currentLocal = oldRemote.copy(
            revisionDate = LOCAL_REVISION,
            reprompt = BitwardenCipher.RepromptType.Password,
        )
        val currentRemote = oldRemote.copy(
            revisionDate = REMOTE_REVISION,
            notes = "Personal mailbox updated on web",
        )

        val merged = merge(
            oldRemote = oldRemote,
            currentLocal = currentLocal,
            currentRemote = currentRemote,
        )

        assertEquals(BitwardenCipher.RepromptType.Password, merged.reprompt)
        assertEquals("Personal mailbox updated on web", merged.notes)
    }

    @Test
    fun `merge prefers remote password conflict but keeps local custom field`() {
        val recoveryCode = hiddenField(
            name = "Recovery code",
            value = "paper wallet 42",
        )
        val oldRemote = loginCipher(
            password = "mail-password-2023",
            passwordRevisionDate = PASSWORD_REVISION_OLD,
        )
        val currentLocal = oldRemote.copy(
            revisionDate = LOCAL_REVISION,
            fields = listOf(recoveryCode),
            login = oldRemote.login?.copy(
                password = "mail-password-edited-offline",
                passwordRevisionDate = PASSWORD_REVISION_LOCAL,
            ),
        )
        val currentRemote = oldRemote.copy(
            revisionDate = REMOTE_REVISION,
            login = oldRemote.login?.copy(
                password = "mail-password-reset-on-web",
                passwordRevisionDate = PASSWORD_REVISION_REMOTE,
            ),
        )

        val merged = merge(
            oldRemote = oldRemote,
            currentLocal = currentLocal,
            currentRemote = currentRemote,
        )

        assertEquals("mail-password-reset-on-web", merged.login?.password)
        assertEquals(PASSWORD_REVISION_REMOTE, merged.login?.passwordRevisionDate)
        assertEquals(listOf(recoveryCode), merged.fields)
    }

    @Test
    fun `merge keeps local password pair when remote only changes password revision date`() {
        val oldRemote = loginCipher(
            password = "mail-password-2023",
            passwordRevisionDate = PASSWORD_REVISION_OLD,
        )
        val currentLocal = oldRemote.copy(
            revisionDate = LOCAL_REVISION,
            login = oldRemote.login?.copy(
                password = "mail-password-2024-from-phone",
                passwordRevisionDate = PASSWORD_REVISION_LOCAL,
            ),
        )
        val currentRemote = oldRemote.copy(
            revisionDate = REMOTE_REVISION,
            login = oldRemote.login?.copy(
                passwordRevisionDate = PASSWORD_REVISION_REMOTE,
            ),
        )

        val merged = merge(
            oldRemote = oldRemote,
            currentLocal = currentLocal,
            currentRemote = currentRemote,
        )

        assertEquals("mail-password-2024-from-phone", merged.login?.password)
        assertEquals(PASSWORD_REVISION_LOCAL, merged.login?.passwordRevisionDate)
    }

    @Test
    fun `merge keeps remote password pair when local only changes password revision date`() {
        val oldRemote = loginCipher(
            password = "mail-password-2023",
            passwordRevisionDate = PASSWORD_REVISION_OLD,
        )
        val currentLocal = oldRemote.copy(
            revisionDate = LOCAL_REVISION,
            login = oldRemote.login?.copy(
                passwordRevisionDate = PASSWORD_REVISION_LOCAL,
            ),
        )
        val currentRemote = oldRemote.copy(
            revisionDate = REMOTE_REVISION,
            login = oldRemote.login?.copy(
                password = "mail-password-reset-on-web",
            ),
        )

        val merged = merge(
            oldRemote = oldRemote,
            currentLocal = currentLocal,
            currentRemote = currentRemote,
        )

        assertEquals("mail-password-reset-on-web", merged.login?.password)
        assertEquals(PASSWORD_REVISION_OLD, merged.login?.passwordRevisionDate)
    }

    @Test
    fun `merge uses newest revision when both devices set same password`() {
        val oldRemote = loginCipher(
            password = "mail-password-2023",
            passwordRevisionDate = PASSWORD_REVISION_OLD,
        )
        val currentLocal = oldRemote.copy(
            revisionDate = LOCAL_REVISION,
            login = oldRemote.login?.copy(
                password = "mail-password-2024",
                passwordRevisionDate = PASSWORD_REVISION_LOCAL_NEWEST,
            ),
        )
        val currentRemote = oldRemote.copy(
            revisionDate = REMOTE_REVISION,
            login = oldRemote.login?.copy(
                password = "mail-password-2024",
                passwordRevisionDate = PASSWORD_REVISION_REMOTE,
            ),
        )

        val merged = merge(
            oldRemote = oldRemote,
            currentLocal = currentLocal,
            currentRemote = currentRemote,
        )

        assertEquals("mail-password-2024", merged.login?.password)
        assertEquals(PASSWORD_REVISION_LOCAL_NEWEST, merged.login?.passwordRevisionDate)
    }

    @Test
    fun `merge combines custom fields added on different devices`() {
        val supportPin = hiddenField(
            name = "Support PIN",
            value = "3471",
        )
        val recoveryEmail = textField(
            name = "Recovery email",
            value = "alice.recovery@example.com",
        )
        val newsletterOptIn = booleanField(
            name = "Newsletter opt-in",
            value = "true",
        )
        val oldRemote = loginCipher(
            fields = listOf(supportPin),
        )
        val currentLocal = oldRemote.copy(
            revisionDate = LOCAL_REVISION,
            fields = listOf(
                supportPin,
                recoveryEmail,
            ),
        )
        val currentRemote = oldRemote.copy(
            revisionDate = REMOTE_REVISION,
            fields = listOf(
                supportPin,
                newsletterOptIn,
            ),
        )

        val merged = merge(
            oldRemote = oldRemote,
            currentLocal = currentLocal,
            currentRemote = currentRemote,
        )

        assertEquals(
            listOf(
                supportPin,
                recoveryEmail,
                newsletterOptIn,
            ),
            merged.fields,
        )
    }

    @Test
    fun `merge combines passkeys created on different devices`() {
        val phonePasskey = passkey(
            credentialId = "phone-credential",
            rpId = "github.com",
            rpName = "GitHub",
            userName = "alice",
            creationDate = PASSKEY_CREATED_LOCAL,
        )
        val browserPasskey = passkey(
            credentialId = "browser-credential",
            rpId = "github.com",
            rpName = "GitHub",
            userName = "alice@example.com",
            creationDate = PASSKEY_CREATED_REMOTE,
        )
        val oldRemote = loginCipher()
        val currentLocal = oldRemote.copy(
            revisionDate = LOCAL_REVISION,
            login = oldRemote.login?.copy(
                fido2Credentials = listOf(phonePasskey),
            ),
        )
        val currentRemote = oldRemote.copy(
            revisionDate = REMOTE_REVISION,
            login = oldRemote.login?.copy(
                fido2Credentials = listOf(browserPasskey),
            ),
        )

        val merged = merge(
            oldRemote = oldRemote,
            currentLocal = currentLocal,
            currentRemote = currentRemote,
        )

        assertEquals(
            listOf(
                phonePasskey,
                browserPasskey,
            ),
            merged.login?.fido2Credentials,
        )
    }

    @Test
    fun `merge keeps local password removal and remote totp setup`() {
        val oldRemote = loginCipher(
            password = "mail-password-2023",
            passwordRevisionDate = PASSWORD_REVISION_OLD,
        )
        val currentLocal = oldRemote.copy(
            revisionDate = LOCAL_REVISION,
            login = oldRemote.login?.copy(
                password = null,
                passwordRevisionDate = null,
            ),
        )
        val currentRemote = oldRemote.copy(
            revisionDate = REMOTE_REVISION,
            login = oldRemote.login?.copy(
                totp = TOTP,
            ),
        )

        val merged = merge(
            oldRemote = oldRemote,
            currentLocal = currentLocal,
            currentRemote = currentRemote,
        )

        assertEquals(null, merged.login?.password)
        assertEquals(null, merged.login?.passwordRevisionDate)
        assertEquals(TOTP, merged.login?.totp)
    }

    @Test
    fun `merge keeps remote totp removal and local password rotation`() {
        val oldRemote = loginCipher(
            password = "mail-password-2023",
            passwordRevisionDate = PASSWORD_REVISION_OLD,
            totp = TOTP,
        )
        val currentLocal = oldRemote.copy(
            revisionDate = LOCAL_REVISION,
            login = oldRemote.login?.copy(
                password = "mail-password-2024-from-phone",
                passwordRevisionDate = PASSWORD_REVISION_LOCAL,
            ),
        )
        val currentRemote = oldRemote.copy(
            revisionDate = REMOTE_REVISION,
            login = oldRemote.login?.copy(
                totp = null,
            ),
        )

        val merged = merge(
            oldRemote = oldRemote,
            currentLocal = currentLocal,
            currentRemote = currentRemote,
        )

        assertEquals("mail-password-2024-from-phone", merged.login?.password)
        assertEquals(PASSWORD_REVISION_LOCAL, merged.login?.passwordRevisionDate)
        assertEquals(null, merged.login?.totp)
    }

    @Test
    fun `merge applies local custom field removal and remote custom field addition`() {
        val supportPin = hiddenField(
            name = "Support PIN",
            value = "3471",
        )
        val recoveryCode = hiddenField(
            name = "Recovery code",
            value = "paper wallet 42",
        )
        val recoveryEmail = textField(
            name = "Recovery email",
            value = "alice.recovery@example.com",
        )
        val oldRemote = loginCipher(
            fields = listOf(
                supportPin,
                recoveryCode,
            ),
        )
        val currentLocal = oldRemote.copy(
            revisionDate = LOCAL_REVISION,
            fields = listOf(supportPin),
        )
        val currentRemote = oldRemote.copy(
            revisionDate = REMOTE_REVISION,
            fields = listOf(
                supportPin,
                recoveryCode,
                recoveryEmail,
            ),
        )

        val merged = merge(
            oldRemote = oldRemote,
            currentLocal = currentLocal,
            currentRemote = currentRemote,
        )

        assertEquals(
            listOf(
                supportPin,
                recoveryEmail,
            ),
            merged.fields,
        )
    }

    @Test
    fun `merge applies remote passkey removal and local passkey addition`() {
        val oldLaptopPasskey = passkey(
            credentialId = "old-laptop-credential",
            rpId = "github.com",
            rpName = "GitHub",
            userName = "alice",
            creationDate = PASSKEY_CREATED_OLD,
        )
        val phonePasskey = passkey(
            credentialId = "phone-credential",
            rpId = "github.com",
            rpName = "GitHub",
            userName = "alice",
            creationDate = PASSKEY_CREATED_LOCAL,
        )
        val oldRemote = loginCipher(
            fido2Credentials = listOf(oldLaptopPasskey),
        )
        val currentLocal = oldRemote.copy(
            revisionDate = LOCAL_REVISION,
            login = oldRemote.login?.copy(
                fido2Credentials = listOf(
                    oldLaptopPasskey,
                    phonePasskey,
                ),
            ),
        )
        val currentRemote = oldRemote.copy(
            revisionDate = REMOTE_REVISION,
            login = oldRemote.login?.copy(
                fido2Credentials = emptyList(),
            ),
        )

        val merged = merge(
            oldRemote = oldRemote,
            currentLocal = currentLocal,
            currentRemote = currentRemote,
        )

        assertEquals(
            listOf(phonePasskey),
            merged.login?.fido2Credentials,
        )
    }

    @Test
    fun `merge preserves both custom field edits when same field changes differently`() {
        val oldRecoveryCode = hiddenField(
            name = "Recovery code",
            value = "paper wallet 42",
        )
        val localRecoveryCode = hiddenField(
            name = "Recovery code",
            value = "phone backup 84",
        )
        val remoteRecoveryCode = hiddenField(
            name = "Recovery code",
            value = "web backup 126",
        )
        val oldRemote = loginCipher(
            fields = listOf(oldRecoveryCode),
        )
        val currentLocal = oldRemote.copy(
            revisionDate = LOCAL_REVISION,
            fields = listOf(localRecoveryCode),
        )
        val currentRemote = oldRemote.copy(
            revisionDate = REMOTE_REVISION,
            fields = listOf(remoteRecoveryCode),
        )

        val merged = merge(
            oldRemote = oldRemote,
            currentLocal = currentLocal,
            currentRemote = currentRemote,
        )

        assertEquals(
            listOf(
                localRecoveryCode,
                remoteRecoveryCode,
            ),
            merged.fields,
        )
    }

    @Test
    fun `merge preserves both passkey edits when same passkey changes differently`() {
        val oldPasskey = passkey(
            credentialId = "shared-credential",
            rpId = "github.com",
            rpName = "GitHub",
            userName = "alice",
            creationDate = PASSKEY_CREATED_OLD,
        )
        val localPasskey = oldPasskey.copy(
            counter = "1",
        )
        val remotePasskey = oldPasskey.copy(
            userDisplayName = "Alice Web",
        )
        val oldRemote = loginCipher(
            fido2Credentials = listOf(oldPasskey),
        )
        val currentLocal = oldRemote.copy(
            revisionDate = LOCAL_REVISION,
            login = oldRemote.login?.copy(
                fido2Credentials = listOf(localPasskey),
            ),
        )
        val currentRemote = oldRemote.copy(
            revisionDate = REMOTE_REVISION,
            login = oldRemote.login?.copy(
                fido2Credentials = listOf(remotePasskey),
            ),
        )

        val merged = merge(
            oldRemote = oldRemote,
            currentLocal = currentLocal,
            currentRemote = currentRemote,
        )

        assertEquals(
            listOf(
                localPasskey,
                remotePasskey,
            ),
            merged.login?.fido2Credentials,
        )
    }

    private fun merge(
        oldRemote: BitwardenCipher,
        currentLocal: BitwardenCipher,
        currentRemote: BitwardenCipher,
    ): BitwardenCipher = with(ModelDiffUtil()) {
        BitwardenCipher
            .getMergeRules()
            .merge(
                base = oldRemote,
                a = currentLocal,
                b = currentRemote,
            ) as BitwardenCipher
    }

    private fun loginCipher(
        password: String? = "mail-password-2023",
        passwordRevisionDate: Instant? = PASSWORD_REVISION_OLD,
        totp: String? = null,
        fields: List<BitwardenCipher.Field> = emptyList(),
        fido2Credentials: List<BitwardenCipher.Login.Fido2Credentials> = emptyList(),
        reprompt: BitwardenCipher.RepromptType = BitwardenCipher.RepromptType.Password,
    ) = BitwardenCipher(
        accountId = "account-1",
        cipherId = "cipher-1",
        revisionDate = BASE_REVISION,
        createdDate = CREATED_AT,
        service = BitwardenService(
            remote = BitwardenService.Remote(
                id = "remote-cipher-1",
                revisionDate = BASE_REVISION,
                deletedDate = null,
            ),
        ),
        keyBase64 = "cipher-key",
        name = "Proton Mail",
        notes = "Personal mailbox",
        favorite = false,
        fields = fields,
        reprompt = reprompt,
        type = BitwardenCipher.Type.Login,
        login = BitwardenCipher.Login(
            username = "alice@example.com",
            password = password,
            passwordRevisionDate = passwordRevisionDate,
            uris = listOf(
                BitwardenCipher.Login.Uri(
                    uri = "https://mail.proton.me",
                    match = BitwardenCipher.Login.Uri.MatchType.Host,
                ),
            ),
            fido2Credentials = fido2Credentials,
            totp = totp,
        ),
    )

    private fun textField(
        name: String,
        value: String,
    ) = BitwardenCipher.Field(
        name = name,
        value = value,
        type = BitwardenCipher.Field.Type.Text,
    )

    private fun hiddenField(
        name: String,
        value: String,
    ) = BitwardenCipher.Field(
        name = name,
        value = value,
        type = BitwardenCipher.Field.Type.Hidden,
    )

    private fun booleanField(
        name: String,
        value: String,
    ) = BitwardenCipher.Field(
        name = name,
        value = value,
        type = BitwardenCipher.Field.Type.Boolean,
    )

    private fun passkey(
        credentialId: String,
        rpId: String,
        rpName: String,
        userName: String,
        creationDate: Instant,
    ) = BitwardenCipher.Login.Fido2Credentials(
        credentialId = credentialId,
        keyType = "public-key",
        keyAlgorithm = "ECDSA",
        keyCurve = "P-256",
        keyValue = "$credentialId-public-key",
        rpId = rpId,
        rpName = rpName,
        counter = "0",
        userHandle = "$credentialId-user-handle",
        userName = userName,
        userDisplayName = "Alice",
        discoverable = "true",
        creationDate = creationDate,
    )

    private companion object {
        val CREATED_AT = Instant.parse("2024-01-01T08:00:00Z")
        val BASE_REVISION = Instant.parse("2024-01-10T08:00:00Z")
        val LOCAL_REVISION = Instant.parse("2024-01-11T09:15:00Z")
        val REMOTE_REVISION = Instant.parse("2024-01-11T10:20:00Z")
        val PASSWORD_REVISION_OLD = Instant.parse("2024-01-10T08:00:00Z")
        val PASSWORD_REVISION_LOCAL = Instant.parse("2024-01-11T09:10:00Z")
        val PASSWORD_REVISION_LOCAL_NEWEST = Instant.parse("2024-01-11T11:00:00Z")
        val PASSWORD_REVISION_REMOTE = Instant.parse("2024-01-11T10:00:00Z")
        val PASSKEY_CREATED_OLD = Instant.parse("2024-01-10T08:30:00Z")
        val PASSKEY_CREATED_LOCAL = Instant.parse("2024-01-11T09:30:00Z")
        val PASSKEY_CREATED_REMOTE = Instant.parse("2024-01-11T10:30:00Z")

        const val TOTP = "JBSWY3DPEHPK3PXP"
    }

}
