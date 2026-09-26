package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.Password
import com.artemchep.keyguard.common.service.backup.BackupConfig
import com.artemchep.keyguard.common.service.backup.BackupStoreConfig
import com.artemchep.keyguard.common.usecase.impl.GetLocalNetworkAccessHintImpl
import com.artemchep.keyguard.core.store.bitwarden.BitwardenToken
import com.artemchep.keyguard.core.store.bitwarden.FileLocation
import com.artemchep.keyguard.core.store.bitwarden.KeePassToken
import com.artemchep.keyguard.core.store.bitwarden.OAuthAccount
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class GetLocalNetworkAccessHintTest {
    @Test
    fun `default Bitwarden environment is cloud-only`() {
        assertFalse(bitwardenToken().mayAccessLocalNetwork())
        assertFalse(
            bitwardenToken(
                env = BitwardenToken.Environment(
                    headers = listOf(
                        BitwardenToken.Environment.Header("X-Test", "value"),
                    ),
                ),
            ).mayAccessLocalNetwork(),
        )
    }

    @Test
    fun `any explicit Bitwarden endpoint is local-network-capable`() {
        val environments = listOf(
            BitwardenToken.Environment(baseUrl = "https://vault.example"),
            BitwardenToken.Environment(webVaultUrl = "https://web.example"),
            BitwardenToken.Environment(apiUrl = "https://api.example"),
            BitwardenToken.Environment(identityUrl = "https://identity.example"),
            BitwardenToken.Environment(iconsUrl = "https://icons.example"),
        )

        environments.forEach { environment ->
            assertTrue(bitwardenToken(environment).mayAccessLocalNetwork())
        }
    }

    @Test
    fun `explicit endpoints are classified without hostname guessing`() {
        val endpoints = listOf(
            "http://192.168.1.10",
            "http://100.64.0.10",
            "http://[fe80::1]",
            "https://vault.local",
            "https://vault.private-dns.example",
        )

        endpoints.forEach { endpoint ->
            assertTrue(bitwardenToken(BitwardenToken.Environment(baseUrl = endpoint)).mayAccessLocalNetwork())
        }
    }

    @Test
    fun `WebDAV and SFTP KeePass locations are local-network-capable`() {
        assertTrue(webDavLocation().mayAccessLocalNetwork())
        assertTrue(sftpLocation().mayAccessLocalNetwork())
        assertTrue(keePassToken(webDavLocation()).mayAccessLocalNetwork())
        assertTrue(keePassToken(sftpLocation()).mayAccessLocalNetwork())
    }

    @Test
    fun `local and provider-backed KeePass locations do not need a LAN hint`() {
        val oauthAccount = OAuthAccount(
            providerAccountId = "provider-account",
            refreshToken = "refresh-token",
        )
        val locations = listOf(
            FileLocation.Local(
                uri = "content://database",
                displayName = "Database",
            ),
            FileLocation.GoogleDrive(
                account = oauthAccount,
                fileId = "file",
                displayName = "Database",
            ),
            FileLocation.OneDrive(
                account = oauthAccount,
                driveId = "drive",
                itemId = "item",
                displayName = "Database",
            ),
            FileLocation.Dropbox(
                account = oauthAccount,
                rootNamespaceId = null,
                homeNamespaceId = null,
                selectedNamespaceId = null,
                fileId = "file",
                pathLower = null,
                displayPath = null,
                displayName = "Database",
            ),
        )

        locations.forEach { location ->
            assertFalse(location.mayAccessLocalNetwork())
            assertFalse(keePassToken(location).mayAccessLocalNetwork())
        }
    }

    @Test
    fun `WebDAV backup offers permission only when enabled and configured`() {
        val configuredStore = BackupStoreConfig.WebDav(url = "https://backup.example")

        assertTrue(
            BackupConfig(
                enabled = true,
                store = configuredStore,
            ).mayAccessLocalNetwork(),
        )
        assertFalse(
            BackupConfig(
                enabled = false,
                store = configuredStore,
            ).mayAccessLocalNetwork(),
        )
        assertFalse(
            BackupConfig(
                enabled = true,
                store = BackupStoreConfig.WebDav(),
            ).mayAccessLocalNetwork(),
        )
        assertFalse(
            BackupConfig(
                enabled = true,
                store = BackupStoreConfig.Local(path = "/backups"),
            ).mayAccessLocalNetwork(),
        )
    }

    @Test
    fun `use case combines accounts and backup configuration distinctly`() = runTest {
        val tokens = MutableStateFlow(listOf(bitwardenToken()))
        val backupConfig = MutableStateFlow(BackupConfig())
        val useCase = GetLocalNetworkAccessHintImpl(
            tokensFlow = tokens,
            backupConfigFlow = backupConfig,
        )
        val values = async {
            useCase().take(3).toList()
        }
        runCurrent()

        tokens.value = listOf(
            bitwardenToken(
                env = BitwardenToken.Environment(baseUrl = "https://vault.example"),
            ),
        )
        runCurrent()
        backupConfig.value = BackupConfig(
            enabled = true,
            store = BackupStoreConfig.WebDav(url = "https://backup.example"),
        )
        tokens.value = emptyList()
        runCurrent()
        backupConfig.value = BackupConfig()
        runCurrent()

        assertEquals(listOf(false, true, false), values.await())
    }
}

internal fun bitwardenToken(
    env: BitwardenToken.Environment = BitwardenToken.Environment(),
) = BitwardenToken(
    id = "bitwarden",
    key = BitwardenToken.Key(
        masterKeyBase64 = "master",
        passwordKeyBase64 = "password",
        encryptionKeyBase64 = "encryption",
        macKeyBase64 = "mac",
    ),
    user = BitwardenToken.User(email = "user@example.com"),
    env = env,
)

internal fun keePassToken(location: FileLocation) = KeePassToken(
    id = "keepass",
    key = KeePassToken.Key(passwordBase64 = "password"),
    database = KeePassToken.Database(
        fileName = "database.kdbx",
        location = location,
    ),
)

private fun webDavLocation() = FileLocation.WebDav(
    url = "https://dav.example/database.kdbx",
    displayName = "Database",
)

private fun sftpLocation() = FileLocation.Sftp(
    host = "sftp.example",
    username = "user",
    path = "/database.kdbx",
    auth = FileLocation.SftpAuth.Password(Password("secret")),
    hostKeyPins = emptyList(),
    displayName = "Database",
)
