package com.artemchep.keyguard.integration.bitwarden

import com.artemchep.keyguard.crypto.CryptoGeneratorJvm
import com.artemchep.keyguard.provider.bitwarden.ServerTwoFactorToken
import com.artemchep.keyguard.provider.bitwarden.crypto.BitwardenCrKey
import com.artemchep.keyguard.provider.bitwarden.model.TwoFactorProviderType
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Proves the helpers for two-factor, organizations, Sends, files and the sync engine. */
class VaultwardenHarnessE2eTest {
    @Test
    fun `totp login returns a remember token that keyguard can use`() = runE2eTest {
        val account = accounts.register()
        val plainLogin = accounts.login(account)
        assertNull(plainLogin.twoFactorToken)
        val secret = twoFactor.enableTotp(account, plainLogin.accessToken)

        val missing = assertFails { accounts.login(account) }
        assertContains(missing.message.orEmpty(), "Two factor required")
        val wrong = assertFails {
            accounts.login(account, RawTwoFactor(RawTwoFactor.AUTHENTICATOR, twoFactor.totpCode(secret, 5)))
        }
        assertContains(wrong.message.orEmpty(), "Invalid TOTP code")

        val login = accounts.login(
            account = account,
            twoFactor = RawTwoFactor(
                provider = RawTwoFactor.AUTHENTICATOR,
                token = twoFactor.totpCode(secret, stepOffset = 1),
                remember = true,
            ),
            deviceIdentifier = deviceId(),
        )
        val rememberToken = assertNotNull(login.twoFactorToken)
        val session = keyguardLogin(
            account = account,
            twoFactorToken = ServerTwoFactorToken(rememberToken, TwoFactorProviderType.Remember),
        )
        assertContentEquals(account.masterKey, session.secrets.masterKey)
    }

    @Test
    fun `confirmed member decrypts the organization collection`() = runE2eTest {
        val owner = accounts.register()
        val ownerToken = accounts.login(owner).accessToken
        val org = organizations.createOrganization(owner, ownerToken, name = "Keyguard E2E")
        val member = accounts.register()

        organizations.invite(ownerToken, org, member.email)
        val invited = organizations.membership(ownerToken, org, member.email)
        assertEquals(Membership.ACCEPTED, invited.status)
        organizations.confirm(ownerToken, org, invited.id, invited.userId)
        assertEquals(Membership.CONFIRMED, organizations.membership(ownerToken, org, member.email).status)

        val session = keyguardLogin(member)
        val sync = keyguardSync(session)
        val collection = sync.collections.orEmpty().single { it.organizationId == org.id }
        assertEquals(org.collectionId, collection.id)
        val name = decryptWith(crypto(session, sync), BitwardenCrKey.OrganizationToken(org.id), collection.name)
        assertEquals(VaultwardenOrganizations.DEFAULT_COLLECTION_NAME, name.decodeToString())
    }

    @Test
    fun `password protected text send opens with its password hash`() = runE2eTest {
        val account = accounts.register()
        val accessToken = accounts.login(account).accessToken
        val send = createTextSend(account, accessToken, text = "Hello", password = "open sesame")

        val unknown = assertFails { sends.accessToken(accessId = "AAAAAAAAAAAAAAAAAAAAAA") }
        assertContains(unknown.message.orEmpty(), "send_id_invalid")
        val missing = assertFails { sends.accessToken(send.accessId) }
        assertContains(missing.message.orEmpty(), "password_hash_b64_required")
        val wrong = assertFails { sends.accessToken(send.accessId, passwordHashB64 = "d3Jvbmc=") }
        assertContains(wrong.message.orEmpty(), "password_hash_b64_invalid")

        val token = sends.accessToken(send.accessId, passwordHashB64 = send.passwordHashB64)
        val text = sends.access(token).getValue("text").jsonObject.string("text")
        assertEquals("Hello", accounts.decrypt(send.key, text).decodeToString())
    }

    @Test
    fun `file send downloads the uploaded frame`() = runE2eTest {
        val account = accounts.register()
        val accessToken = accounts.login(account).accessToken
        val content = "Keyguard E2E file".encodeToByteArray()
        val send = createFileSend(account, accessToken, fileName = "notes.txt", content = content)

        val token = sends.accessToken(send.accessId)
        val fileId = sends.access(token).getValue("file").jsonObject.string("id")
        val frame = sends.accessFile(token, fileId)
        assertContentEquals(content, EncryptedFileFrame.decrypt(send.key, frame))
    }

    @Test
    fun `encrypted file frame round trips and rejects tampering`() {
        val key = CryptoGeneratorJvm().seed(length = 64)
        val content = ByteArray(100) { it.toByte() }

        val frame = EncryptedFileFrame.encrypt(key, content)
        assertEquals(2, frame[0].toInt())
        assertEquals(1 + 16 + 32 + 112, frame.size)
        assertContentEquals(content, EncryptedFileFrame.decrypt(key, frame))

        val tampered = frame.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertFails { EncryptedFileFrame.decrypt(key, tampered) }
        assertFailsWith<IllegalArgumentException> { EncryptedFileFrame.decrypt(key.copyOf(32), frame) }
    }

    @Test
    fun `sync engine pulls a seeded cipher then only checks the revision date`() = runE2eTest {
        val account = accounts.register()
        val cipherId = accounts.createLoginCipher(
            accessToken = accounts.login(account).accessToken,
            account = account,
            name = "Example",
            username = "alice",
            password = "hunter2",
        )
        val session = keyguardLogin(account)

        KeyguardSyncEngine.create(this, session).use { engine ->
            clearRecordedRequests()
            engine.sync()
            val cipher = engine.ciphers().single()
            assertEquals(cipherId, cipher.service.remote?.id)
            assertEquals("Example", cipher.name)
            assertContains(recordedRequests.toList(), "GET /api/accounts/revision-date")
            assertContains(recordedRequests.toList(), "GET /api/sync")
            assertEquals(session.token.id, engine.storedToken().id)

            clearRecordedRequests()
            engine.sync()
            assertEquals(listOf("GET /api/accounts/revision-date"), recordedRequests.toList())
        }
    }
}
