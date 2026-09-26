package com.artemchep.keyguard.common.service.passkey

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class WebAuthnAdaptersTest {
    @Test
    fun `credential sources exclude archived and deleted ciphers`() {
        val credential = credential("credential-id", "example.com")
        val available = listOf(
            cipher(credential, archived = true),
            cipher(credential, deleted = true),
            cipher(credential),
        ).availablePasskeyCredentials()
        assertEquals(listOf(credential), available)
        assertEquals(credential.credentialId, available.single().toWebAuthnCredential().credentialId)
    }

    @Test
    fun `registration storage mapping preserves credential fields`() {
        val source = credential("credential-id", "example.com")
        val stored = source.toWebAuthnCredential().toAddCredentialCipherRequest()
        assertEquals(source.credentialId, stored.credentialId)
        assertEquals(source.keyValue, stored.keyValue)
        assertEquals(source.rpId, stored.rpId)
        assertEquals(source.rpName, stored.rpName)
        assertEquals(source.userHandle, stored.userHandle)
        assertEquals(source.counter, stored.counter)
        assertEquals(source.discoverable, stored.discoverable)
    }

    private fun cipher(
        credential: DSecret.Login.Fido2Credentials,
        archived: Boolean = false,
        deleted: Boolean = false,
    ) = DSecret(
        id = "cipher-1",
        accountId = "account-1",
        folderId = null,
        organizationId = null,
        collectionIds = emptySet(),
        revisionDate = TEST_INSTANT,
        createdDate = TEST_INSTANT,
        archivedDate = TEST_INSTANT.takeIf { archived },
        deletedDate = TEST_INSTANT.takeIf { deleted },
        service = BitwardenService(),
        name = "Cipher",
        notes = "",
        favorite = false,
        reprompt = false,
        synced = true,
        type = DSecret.Type.Login,
        login = DSecret.Login(
            fido2Credentials = listOf(credential),
        ),
        card = null,
        identity = null,
    )

    private fun credential(
        credentialId: String,
        rpId: String,
    ) = DSecret.Login.Fido2Credentials(
        credentialId = credentialId,
        keyType = "public-key",
        keyAlgorithm = "ECDSA",
        keyCurve = "P-256",
        keyValue = "private-key",
        rpId = rpId,
        rpName = "Example",
        counter = 0,
        userHandle = "dXNlcg",
        userName = "alice@example.com",
        userDisplayName = "Alice",
        discoverable = true,
        creationDate = TEST_INSTANT,
    )

    private companion object {
        val TEST_INSTANT = Instant.fromEpochMilliseconds(0)
    }
}
