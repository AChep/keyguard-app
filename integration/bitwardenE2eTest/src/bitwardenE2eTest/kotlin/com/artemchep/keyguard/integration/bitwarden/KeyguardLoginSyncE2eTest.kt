package com.artemchep.keyguard.integration.bitwarden

import com.artemchep.keyguard.provider.bitwarden.api.builder.api
import com.artemchep.keyguard.provider.bitwarden.api.builder.post
import com.artemchep.keyguard.provider.bitwarden.entity.request.FolderRequest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Drives Keyguard's own Bitwarden client against a real server. */
class KeyguardLoginSyncE2eTest {
    @Test
    fun `keyguard logs in syncs and creates a folder`() = runE2eTest {
        val account = accounts.register()
        val cipherId = accounts.createLoginCipher(
            accessToken = accounts.login(account).accessToken,
            account = account,
            name = "Example",
            username = "alice",
            password = "hunter2",
        )

        val session = keyguardLogin(account)
        val secrets = session.secrets
        val accessToken = session.login.accessToken
        assertTrue(accessToken.isNotBlank())
        assertContentEquals(account.masterKey, secrets.masterKey)

        val sync = keyguardSync(session)
        assertEquals(account.email, sync.profile.email)
        val userKey = accounts.decrypt(secrets.encryptionKey + secrets.macKey, sync.profile.key)
        assertContentEquals(account.userKey, userKey)
        val cipher = sync.ciphers.orEmpty().single { it.id == cipherId }
        assertEquals("Example", accounts.decrypt(userKey, requireNotNull(cipher.name)).decodeToString())

        val folder = env.api.folders.post(
            httpClient = httpClient,
            env = env,
            token = accessToken,
            body = FolderRequest(name = accounts.encrypt(account, "Keyguard E2E")),
        )
        val syncedFolder = keyguardSync(session)
            .folders
            .orEmpty()
            .single { it.id == folder.id }
        assertEquals("Keyguard E2E", accounts.decrypt(userKey, syncedFolder.name).decodeToString())
    }
}
