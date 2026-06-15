package com.artemchep.keyguard.android

import android.os.Bundle
import androidx.credentials.provider.BeginGetPasswordOption
import androidx.credentials.provider.BeginGetPublicKeyCredentialOption
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CredentialProviderPlatformConfigTest {
    @Test
    fun `phone config points to phone provider activities`() {
        assertEquals(
            PasswordGetActivity::class.java,
            PhoneCredentialProviderPlatformConfig.getPasswordActivityClass,
        )
        assertEquals(
            PasskeyGetActivity::class.java,
            PhoneCredentialProviderPlatformConfig.getPasskeyActivityClass,
        )
        assertEquals(
            CredentialGetUnlockActivity::class.java,
            PhoneCredentialProviderPlatformConfig.getUnlockCredentialActivityClass,
        )
        assertEquals(
            PasskeyCreateActivity::class.java,
            PhoneCredentialProviderPlatformConfig.createCredentialActivityClass,
        )
    }

    @Test
    fun `find credential returns matching credential`() {
        val args = PasskeyProviderGetActivityArgs(
            accountId = "account-1",
            cipherId = "cipher-1",
            credId = "credential-2",
            cipherName = "Cipher",
            credRpId = "example.com",
            credUserDisplayName = "alice@example.com",
            requiresUserVerification = false,
            userVerified = false,
        )
        val credential = findCredentialOrNull(
            ciphers = listOf(
                createSecret(
                    id = "cipher-1",
                    accountId = "account-1",
                    credentialIds = listOf("credential-1", "credential-2"),
                ),
            ),
            args = args,
        )

        assertNotNull(credential)
        assertEquals("credential-2", credential?.credentialId)
    }

    @Test
    fun `find credential returns null when no cipher matches`() {
        val args = PasskeyProviderGetActivityArgs(
            accountId = "account-1",
            cipherId = "cipher-1",
            credId = "credential-2",
            cipherName = "Cipher",
            credRpId = "example.com",
            credUserDisplayName = "alice@example.com",
            requiresUserVerification = false,
            userVerified = false,
        )
        val credential = findCredentialOrNull(
            ciphers = listOf(
                createSecret(
                    id = "cipher-2",
                    accountId = "account-2",
                    credentialIds = listOf("credential-1", "credential-2"),
                ),
            ),
            args = args,
        )

        assertNull(credential)
    }

    @Test
    fun `find password cipher returns matching login with password`() {
        val args = PasswordProviderGetActivityArgs(
            accountId = "account-1",
            cipherId = "cipher-1",
            id = "alice@example.com",
            requiresUserVerification = false,
            userVerified = false,
        )

        val credential = findPasswordCipherOrNull(
            ciphers = listOf(
                createSecret(
                    id = "cipher-1",
                    accountId = "account-1",
                    username = "alice@example.com",
                    password = "secret",
                ),
            ),
            args = args,
        )

        assertNotNull(credential)
        assertEquals("cipher-1", credential?.id)
    }

    @Test
    fun `find password cipher returns null when login is missing password`() {
        val args = PasswordProviderGetActivityArgs(
            accountId = "account-1",
            cipherId = "cipher-1",
            id = "alice@example.com",
            requiresUserVerification = false,
            userVerified = false,
        )

        val credential = findPasswordCipherOrNull(
            ciphers = listOf(
                createSecret(
                    id = "cipher-1",
                    accountId = "account-1",
                    username = "alice@example.com",
                    password = null,
                ),
            ),
            args = args,
        )

        assertNull(credential)
    }

    @Test
    fun `find credential provider password ciphers excludes archived deleted and incomplete logins`() {
        val matchedCipher = createSecret(
            id = "cipher-1",
            accountId = "account-1",
            username = "alice@example.com",
            password = "secret",
        )
        val ciphers = listOf(
            matchedCipher,
            createSecret(
                id = "cipher-archived",
                accountId = "account-1",
                username = "alice@example.com",
                password = "secret",
                archived = true,
            ),
            createSecret(
                id = "cipher-deleted",
                accountId = "account-1",
                username = "alice@example.com",
                password = "secret",
                deleted = true,
            ),
            createSecret(
                id = "cipher-no-password",
                accountId = "account-1",
                username = "alice@example.com",
                password = null,
            ),
            createSecret(
                id = "cipher-no-username",
                accountId = "account-1",
                username = null,
                password = "secret",
            ),
        )

        val result = kotlinx.coroutines.runBlocking {
            findCredentialProviderPasswordCiphers(
                callingAppInfo = null,
                option = BeginGetPasswordOption(
                    allowedUserIds = emptySet(),
                    candidateQueryData = Bundle(),
                    id = "password",
                ),
                ciphers = ciphers,
                provideTrustedOrigin = { null },
                getSuggestions = { _, items ->
                    items
                },
            )
        }

        assertEquals(listOf(matchedCipher), result)
    }

    @Test
    fun `find credential provider password ciphers respects allowed user ids`() {
        val aliceCipher = createSecret(
            id = "cipher-alice",
            accountId = "account-1",
            username = "alice@example.com",
            password = "secret",
        )
        val bobCipher = createSecret(
            id = "cipher-bob",
            accountId = "account-1",
            username = "bob@example.com",
            password = "secret",
        )

        val result = kotlinx.coroutines.runBlocking {
            findCredentialProviderPasswordCiphers(
                callingAppInfo = null,
                option = BeginGetPasswordOption(
                    allowedUserIds = setOf("bob@example.com"),
                    candidateQueryData = Bundle(),
                    id = "password",
                ),
                ciphers = listOf(aliceCipher, bobCipher),
                provideTrustedOrigin = { null },
                getSuggestions = { target, items ->
                    assertEquals("bob@example.com", target.username)
                    assertEquals(listOf(bobCipher), items)
                    items
                },
            )
        }

        assertEquals(listOf(bobCipher), result)
    }

    @Test
    fun `find credential provider password ciphers skips suggestions without target context`() {
        val aliceCipher = createSecret(
            id = "cipher-alice",
            accountId = "account-1",
            username = "alice@example.com",
            password = "secret",
        )
        val bobCipher = createSecret(
            id = "cipher-bob",
            accountId = "account-1",
            username = "bob@example.com",
            password = "secret",
        )
        var suggestionsCalled = false

        val result = kotlinx.coroutines.runBlocking {
            findCredentialProviderPasswordCiphers(
                callingAppInfo = null,
                option = BeginGetPasswordOption(
                    allowedUserIds = emptySet(),
                    candidateQueryData = Bundle(),
                    id = "password",
                ),
                ciphers = listOf(aliceCipher, bobCipher),
                provideTrustedOrigin = { null },
                getSuggestions = { _, items ->
                    suggestionsCalled = true
                    items
                },
            )
        }

        assertEquals(listOf(aliceCipher, bobCipher), result)
        assertFalse(suggestionsCalled)
    }

    @Test
    fun `filter options keeps password requests when passkeys are disabled`() {
        val options = listOf(
            BeginGetPasswordOption(
                allowedUserIds = emptySet(),
                candidateQueryData = Bundle(),
                id = "password",
            ),
            BeginGetPublicKeyCredentialOption(
                candidateQueryData = Bundle(),
                id = "passkey",
                requestJson = """{"challenge":"YQ","rpId":"example.com"}""",
            ),
        )

        val result = filterCredentialProviderBeginGetOptions(
            options = options,
            passkeysEnabled = false,
            passwordsEnabled = true,
        )

        assertEquals(1, result.size)
        assertEquals(options.first(), result.single())
    }

    private fun createSecret(
        id: String,
        accountId: String,
        credentialIds: List<String> = emptyList(),
        username: String? = null,
        password: String? = null,
        archived: Boolean = false,
        deleted: Boolean = false,
    ) = DSecret(
        id = id,
        accountId = accountId,
        folderId = null,
        organizationId = null,
        collectionIds = emptySet(),
        revisionDate = TEST_INSTANT,
        createdDate = TEST_INSTANT,
        archivedDate = TEST_INSTANT.takeIf { archived },
        deletedDate = TEST_INSTANT.takeIf { deleted },
        service = BitwardenService(),
        name = id,
        notes = "",
        favorite = false,
        reprompt = false,
        synced = true,
        type = DSecret.Type.Login,
        login = DSecret.Login(
            username = username,
            password = password,
            fido2Credentials = credentialIds.map(::createCredential),
        ),
        card = null,
        identity = null,
    )

    private fun createCredential(
        credentialId: String,
    ) = DSecret.Login.Fido2Credentials(
        credentialId = credentialId,
        keyType = "public-key",
        keyAlgorithm = "ECDSA",
        keyCurve = "P-256",
        keyValue = "private-key",
        rpId = "example.com",
        rpName = "Example",
        counter = 1,
        userHandle = "handle",
        userName = "alice@example.com",
        userDisplayName = "Alice Example",
        discoverable = true,
        creationDate = TEST_INSTANT,
    )

    private companion object {
        private val TEST_INSTANT = Instant.parse("2024-01-01T00:00:00Z")
    }
}
