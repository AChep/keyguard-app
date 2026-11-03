package com.artemchep.keyguard.integration.bitwarden

import com.artemchep.keyguard.common.exception.HttpException
import com.artemchep.keyguard.provider.bitwarden.api.builder.api
import com.artemchep.keyguard.provider.bitwarden.api.builder.delete
import com.artemchep.keyguard.provider.bitwarden.api.builder.get
import com.artemchep.keyguard.provider.bitwarden.api.builder.post
import com.artemchep.keyguard.provider.bitwarden.api.builder.renew
import com.artemchep.keyguard.provider.bitwarden.entity.CipherEntity
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Drives Keyguard's cipher attachment API against a real server. */
class KeyguardAttachmentsE2eTest {
    @Test
    fun `attachment upload download and delete`() = runE2eTest {
        val account = accounts.register()
        val session = keyguardLogin(account)
        val token = session.login.accessToken
        val cipher = session.env.api.ciphers.post(
            httpClient = httpClient,
            env = session.env,
            token = token,
            body = loginCipherRequest(key = account.userKey, encryptedFor = accounts.userId(token), name = "Example"),
        )
        val attachments = session.env.api.ciphers.focus(cipher.id).attachments
        val payload = cryptoGenerator.seed(length = PAYLOAD_SIZE)
        val upload = uploadAttachment(token, cipher.id, account.userKey, FILE_NAME, payload)

        val attachment = attachments.focus(upload.id).get(httpClient, session.env, token)
        assertEquals(FILE_NAME, accounts.decrypt(account.userKey, attachment.fileName).decodeToString())
        assertEquals(upload.frame.size.toString(), attachment.size)
        // The download URL carries a short-lived token of its own; no bearer token.
        val download = httpClient.get(assertNotNull(attachment.url))
        assertEquals(HttpStatusCode.OK, download.status)
        assertContentEquals(payload, EncryptedFileFrame.decrypt(upload.key, download.bodyAsBytes()))

        val synced = syncedCipher(session, cipher.id).attachments.orEmpty().single()
        assertEquals(upload.id, synced.id)
        assertContentEquals(upload.key, accounts.decrypt(account.userKey, synced.key))

        // Vaultwarden has no renew route.
        val renewFailure = assertFailsWith<HttpException> {
            attachments.focus(upload.id).renew(httpClient, session.env, token)
        }
        assertEquals(HttpStatusCode.NotFound, renewFailure.statusCode)

        attachments.delete(httpClient, session.env, token, upload.id)
        assertTrue(syncedCipher(session, cipher.id).attachments.isNullOrEmpty())
    }

    companion object {
        private const val PAYLOAD_SIZE = 100 * 1024
        private const val FILE_NAME = "payload.bin"

        private suspend fun VaultwardenFixture.syncedCipher(
            session: KeyguardSession,
            cipherId: String,
        ): CipherEntity = keyguardSync(session).ciphers.orEmpty().single { it.id == cipherId }
    }
}
