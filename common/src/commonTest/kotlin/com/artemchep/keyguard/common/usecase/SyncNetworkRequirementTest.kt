package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.Password
import com.artemchep.keyguard.core.store.bitwarden.FileLocation
import com.artemchep.keyguard.core.store.bitwarden.OAuthAccount
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncNetworkRequirementTest {
    @Test
    fun `local files and document providers can sync without a network`() {
        listOf(
            "file:///vault.kdbx",
            "content://com.android.externalstorage.documents/document/primary%3Avault.kdbx",
            "content://some.cloud.provider/vault.kdbx",
        ).forEach { uri ->
            val token = keePassToken(FileLocation.Local(uri = uri, displayName = "vault.kdbx"))
            assertFalse(token.syncRequiresNetwork(), uri)
        }
    }

    @Test
    fun `remote database transports require a network`() {
        val oauth = OAuthAccount(providerAccountId = "user", refreshToken = "refresh")
        listOf(
            FileLocation.WebDav(url = "https://example.com/vault.kdbx", displayName = "vault.kdbx"),
            FileLocation.GoogleDrive(account = oauth, fileId = "file", displayName = "vault.kdbx"),
            FileLocation.OneDrive(account = oauth, driveId = "drive", itemId = "item", displayName = "vault.kdbx"),
            FileLocation.Dropbox(
                account = oauth,
                rootNamespaceId = null,
                homeNamespaceId = null,
                selectedNamespaceId = null,
                fileId = "file",
                pathLower = null,
                displayPath = null,
                displayName = "vault.kdbx",
            ),
            FileLocation.Sftp(
                host = "example.com",
                username = "user",
                path = "/vault.kdbx",
                auth = FileLocation.SftpAuth.Password(Password("password")),
                hostKeyPins = emptyList(),
                displayName = "vault.kdbx",
            ),
        ).forEach { location ->
            assertTrue(keePassToken(location).syncRequiresNetwork(), location::class.simpleName)
        }
    }

    @Test
    fun `bitwarden requires a network even before its profile exists`() {
        assertTrue(bitwardenToken().syncRequiresNetwork())
    }
}
