package com.artemchep.keyguard.integration.bitwarden

import com.artemchep.keyguard.common.util.to6DigitsNanosOfSecond
import com.artemchep.keyguard.provider.bitwarden.api.builder.api
import com.artemchep.keyguard.provider.bitwarden.api.builder.delete
import com.artemchep.keyguard.provider.bitwarden.api.builder.get
import com.artemchep.keyguard.provider.bitwarden.api.builder.post
import com.artemchep.keyguard.provider.bitwarden.api.builder.postFileV2
import com.artemchep.keyguard.provider.bitwarden.api.builder.put
import com.artemchep.keyguard.provider.bitwarden.api.builder.removePassword
import com.artemchep.keyguard.provider.bitwarden.api.builder.uploadSendFile
import com.artemchep.keyguard.provider.bitwarden.crypto.makeSendCryptoKey
import com.artemchep.keyguard.provider.bitwarden.crypto.makeSendCryptoKeyMaterial
import com.artemchep.keyguard.provider.bitwarden.entity.SendTypeEntity
import com.artemchep.keyguard.provider.bitwarden.entity.request.SendFileRequest
import com.artemchep.keyguard.provider.bitwarden.entity.request.SendRequest
import com.artemchep.keyguard.provider.bitwarden.entity.request.SendTextRequest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/** Drives Keyguard's Send API against a real server, and opens the Sends as a recipient does. */
class KeyguardSendsE2eTest {
    @Test
    fun `text send with password round trips`() = runE2eTest {
        val account = accounts.register()
        val session = keyguardLogin(account)
        val token = session.login.accessToken
        val api = session.env.api.sends
        val keys = sendKeys()
        val passwordHash = sendPasswordHash("open sesame", keys.material)
        val request = sendRequest(
            account = account,
            keys = keys,
            type = SendTypeEntity.Text,
            text = SendTextRequest(text = accounts.encrypt(keys.key, "Hello"), hidden = false),
            password = passwordHash,
        )
        val send = api.post(httpClient, session.env, token, request)
        assertEquals(SendTypeEntity.Text, send.type)

        val wrong = assertFails { sends.accessToken(send.accessId, passwordHashB64 = "d3Jvbmc=") }
        assertContains(wrong.message.orEmpty(), "password_hash_b64_invalid")
        val text = sends.access(sends.accessToken(send.accessId, passwordHash)).sendText()
        assertEquals("Hello", accounts.decrypt(keys.key, text).decodeToString())

        api.focus(send.id).removePassword(httpClient, session.env, token)
        val openText = sends.access(sends.accessToken(send.accessId)).sendText()
        assertEquals("Hello", accounts.decrypt(keys.key, openText).decodeToString())

        val renamedRequest = request.copy(name = accounts.encrypt(keys.key, "Renamed"), password = null)
        api.focus(send.id).put(httpClient, session.env, token, renamedRequest)
        val renamed = api.focus(send.id).get(httpClient, session.env, token)
        assertEquals("Renamed", accounts.decrypt(keys.key, assertNotNull(renamed.name)).decodeToString())
        assertContains(keyguardSync(session).sends.orEmpty().map { it.id }, send.id)

        api.focus(send.id).delete(httpClient, session.env, token)
        assertTrue(keyguardSync(session).sends.orEmpty().none { it.id == send.id })
    }

    @Test
    fun `file send uploads and downloads`() = runE2eTest {
        val account = accounts.register()
        val session = keyguardLogin(account)
        val token = session.login.accessToken
        val api = session.env.api.sends
        val keys = sendKeys()
        val content = cryptoGenerator.seed(length = FILE_SIZE)
        val frame = EncryptedFileFrame.encrypt(keys.key, content)
        val request = sendRequest(
            account = account,
            keys = keys,
            type = SendTypeEntity.File,
            file = SendFileRequest(fileName = accounts.encrypt(keys.key, FILE_NAME)),
            fileLength = frame.size.toLong(),
        )
        val reservation = api.postFileV2(httpClient, session.env, token, request)
        val send = reservation.requiredSendResponse
        // Vaultwarden rejects an upload whose part name differs from the Send's encrypted file name.
        val encryptedFileName = assertNotNull(send.file?.fileName)
        withUploadFile(frame) { path ->
            uploadSendFile(
                httpClient = httpClient,
                env = session.env,
                token = token,
                target = reservation.uploadTarget,
                fileName = encryptedFileName,
                filePath = path,
                fileLength = frame.size.toLong(),
            )
        }

        val accessToken = sends.accessToken(send.accessId)
        val file = sends.access(accessToken).getValue("file").jsonObject
        assertEquals(send.file?.id, file.string("id"))
        assertEquals(FILE_NAME, accounts.decrypt(keys.key, file.string("fileName")).decodeToString())
        val downloaded = sends.accessFile(accessToken, file.string("id"))
        assertContentEquals(content, EncryptedFileFrame.decrypt(keys.key, downloaded))

        val fetched = api.focus(send.id).get(httpClient, session.env, token)
        assertEquals(FILE_NAME, accounts.decrypt(keys.key, assertNotNull(fetched.file?.fileName)).decodeToString())
        assertEquals(frame.size.toString(), fetched.file?.size)
    }

    companion object {
        private const val FILE_SIZE = 4096
        private const val FILE_NAME = "notes.txt"

        /** The 16 bytes of key material a Send carries, and the 64-byte key derived from it. */
        private class SendKeys(
            val material: ByteArray,
            val key: ByteArray,
        )

        private fun VaultwardenFixture.sendKeys(): SendKeys {
            val material = cryptoGenerator.makeSendCryptoKeyMaterial()
            return SendKeys(material = material, key = cryptoGenerator.makeSendCryptoKey(material))
        }

        /** A Send request the way Keyguard builds one: the material under the user key, the rest under [keys]. */
        private fun VaultwardenFixture.sendRequest(
            account: TestAccount,
            keys: SendKeys,
            type: SendTypeEntity,
            text: SendTextRequest? = null,
            file: SendFileRequest? = null,
            fileLength: Long? = null,
            password: String? = null,
        ): SendRequest = SendRequest(
            key = accounts.encrypt(account.userKey, keys.material),
            type = type,
            name = accounts.encrypt(keys.key, "Keyguard E2E"),
            notes = null,
            password = password,
            disabled = false,
            hideEmail = false,
            deletionDate = (Clock.System.now() + 1.days).to6DigitsNanosOfSecond(),
            expirationDate = null,
            maxAccessCount = null,
            text = text,
            file = file,
            fileLength = fileLength,
        )

        /** The still encrypted text of an accessed text Send. */
        private fun JsonObject.sendText(): String = getValue("text").jsonObject.string("text")
    }
}
