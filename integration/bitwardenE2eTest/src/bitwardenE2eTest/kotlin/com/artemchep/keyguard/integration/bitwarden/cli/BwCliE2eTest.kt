package com.artemchep.keyguard.integration.bitwarden.cli

import com.artemchep.keyguard.common.exception.ApiException
import com.artemchep.keyguard.integration.bitwarden.EncryptedFileFrame
import com.artemchep.keyguard.integration.bitwarden.TestAccount
import com.artemchep.keyguard.integration.bitwarden.TestKdf
import com.artemchep.keyguard.integration.bitwarden.VaultwardenAccounts
import com.artemchep.keyguard.integration.bitwarden.VaultwardenFixture
import com.artemchep.keyguard.integration.bitwarden.loginCipherRequest
import com.artemchep.keyguard.integration.bitwarden.runE2eTest
import com.artemchep.keyguard.integration.bitwarden.string
import com.artemchep.keyguard.integration.bitwarden.withUploadFile
import com.artemchep.keyguard.provider.bitwarden.ServerTwoFactorToken
import com.artemchep.keyguard.provider.bitwarden.api.builder.api
import com.artemchep.keyguard.provider.bitwarden.api.builder.create
import com.artemchep.keyguard.provider.bitwarden.api.builder.post
import com.artemchep.keyguard.provider.bitwarden.crypto.BitwardenCrKey
import com.artemchep.keyguard.provider.bitwarden.crypto.makeCipherCryptoKeyMaterial
import com.artemchep.keyguard.provider.bitwarden.crypto.makeSendCryptoKey
import com.artemchep.keyguard.provider.bitwarden.entity.CipherEntity
import com.artemchep.keyguard.provider.bitwarden.entity.CipherTypeEntity
import com.artemchep.keyguard.provider.bitwarden.entity.request.CipherCreateRequest
import com.artemchep.keyguard.provider.bitwarden.model.TwoFactorProviderType
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Runs the `bw` commands against a real server, then checks the result through Keyguard. */
class BwCliE2eTest {
    @Test
    fun `register creates an account that keyguard logs in to`() = runE2eTest {
        val email = VaultwardenAccounts.uniqueEmail()
        val result = execute(BwCommand.Register(BwAccount(baseUrl, email, PASSWORD)))

        val (_, login) = keyguardPasswordLogin(email, PASSWORD)
        assertEquals(email, accounts.sync(login.accessToken).getValue("profile").jsonObject.string("email"))
        assertTrue(result.json.getValue("ok").jsonPrimitive.boolean)
        assertEquals(baseUrl, result.json.string("server"))
        assertEquals(email, result.json.string("email"))
        assertEquals(PASSWORD, result.json.string("password"))
        assertEquals(accounts.userId(login.accessToken), result.json.string("userId"))
        assertEquals("pbkdf2", result.json.getValue("kdf").jsonObject.string("type"))
        assertTrue(result.lines.any { "Custom (self-hosted)" in it }, result.lines.toString())
    }

    @Test
    fun `unlock recovers the secrets of a registered account`() = runE2eTest {
        listOf(TestKdf.Pbkdf2(), TestKdf.Argon2id(iterations = 2, memoryMib = 16, parallelism = 1)).forEach { kdf ->
            val registered = accounts.register(kdf = kdf)
            val unlocked = unlock(registered.email, registered.password).account

            assertEquals(kdf, unlocked.kdf)
            assertContentEquals(registered.userKey, unlocked.userKey)
            assertContentEquals(registered.privateKey, unlocked.privateKey)
            assertContentEquals(registered.masterKey, unlocked.masterKey)
            assertEquals(registered.masterPasswordHash, unlocked.masterPasswordHash)
        }
    }

    @Test
    fun `seed fills the vault`() = runE2eTest {
        val account = accounts.register()
        val command = BwCommand.Seed(
            account = bw(account),
            folders = 1,
            logins = 2,
            notes = 1,
            attachment = true,
            send = BwCommand.SendType.Text,
        )
        val result = execute(command)

        val sync = keyguardSync(keyguardLogin(account))
        fun decrypt(cipherText: String?) = accounts.decrypt(account.userKey, assertNotNull(cipherText)).decodeToString()
        val folder = sync.folders.orEmpty().single()
        assertEquals("Folder 1", decrypt(folder.name))
        val ciphers = sync.ciphers.orEmpty()
        assertEquals(listOf("Example Site 1", "Example Site 2", "Note 1"), ciphers.map { decrypt(it.name) }.sorted())
        val logins = ciphers.filter { it.type == CipherTypeEntity.Login }
        assertTrue(logins.all { it.folderId == folder.id })
        val note = ciphers.single { it.type == CipherTypeEntity.SecureNote }
        assertEquals(result.json.items("notes").single().string("id"), note.id)

        val seededLogin = result.json.items("logins").first()
        val login = ciphers.single { it.id == seededLogin.string("id") }
        assertEquals("Example Site 1", decrypt(login.name))
        assertEquals(seededLogin.string("username"), decrypt(login.login?.username))
        assertEquals(seededLogin.string("password"), decrypt(login.login?.password))
        assertEquals("https://site1.example", decrypt(login.login?.uris.orEmpty().single().uri))
        assertEquals(seededLogin.string("totp"), decrypt(login.login?.totp))

        val attachment = login.attachments.orEmpty().single()
        assertEquals(result.json.getValue("attachment").jsonObject.string("attachmentId"), attachment.id)
        assertEquals("attachment-1.txt", decrypt(attachment.fileName))
        val send = result.json.getValue("send").jsonObject
        assertContains(sync.sends.orEmpty().map { it.id }, send.string("id"))
        assertEquals("text", send.string("type"))
    }

    @Test
    fun `totp turns on two-step login`() = runE2eTest {
        val account = accounts.register()
        val result = execute(BwCommand.Totp(bw(account)))
        val secret = result.json.string("secret")
        val otpauth = "otpauth://totp/Keyguard:${account.email}?secret=$secret&issuer=Keyguard"
        assertEquals(otpauth, result.json.string("otpauth"))

        val failure = assertFailsWith<ApiException> { keyguardLogin(account) }
        assertIs<ApiException.Type.TwoFaRequired>(failure.type)
        // Turning TOTP on used the current step.
        val code = twoFactor.totpCode(secret, stepOffset = 1)
        val token = ServerTwoFactorToken(code, TwoFactorProviderType.Authenticator)
        assertTrue(keyguardSync(keyguardLogin(account, token)).profile.twoFactorEnabled)

        // The code may come from the step before, if a step ends in between.
        val current = code(BwCommand.Code(secret)).json.string("code")
        assertContains(setOf(twoFactor.totpCode(secret), twoFactor.totpCode(secret, stepOffset = -1)), current)
    }

    @Test
    fun `org shares a login with a confirmed member`() = runE2eTest {
        val owner = accounts.register()
        val member = accounts.register()
        val result = execute(BwCommand.Org(bw(owner), name = "Acme", member = member.email))
        val orgId = result.json.string("organizationId")
        val membership = result.json.getValue("member").jsonObject
        assertEquals(member.email, membership.string("email"))
        assertEquals("confirmed", membership.string("status"))

        val session = keyguardLogin(member)
        val sync = keyguardSync(session)
        assertEquals("Acme", sync.profile.organizations.orEmpty().single { it.id == orgId }.name)
        assertEquals(result.json.string("collectionId"), sync.collections.orEmpty().single().id)
        val cipher = sync.ciphers.orEmpty().single { it.id == result.json.string("cipherId") }
        val orgKey = BitwardenCrKey.OrganizationToken(orgId)
        val name = decryptWith(crypto(session, sync), orgKey, assertNotNull(cipher.name)).decodeToString()
        assertEquals("Shared Login", name)

        val unregistered = VaultwardenAccounts.uniqueEmail()
        val failure = assertFails { execute(BwCommand.Org(bw(owner), name = "Acme", member = unregistered)) }
        assertContains(failure.message.orEmpty(), "Register $unregistered first")
    }

    @Test
    fun `send link opens the send and attach uploads a file`() = runE2eTest {
        val account = accounts.register()
        val result = execute(BwCommand.Send(bw(account), text = "Hello"))
        val accessId = result.json.string("accessId")
        val url = result.json.string("url")
        val prefix = "$baseUrl/#/send/$accessId/"
        assertTrue(url.startsWith(prefix), url)
        val key = cryptoGenerator.makeSendCryptoKey(Base64.getUrlDecoder().decode(url.removePrefix(prefix)))
        val text = sends.access(sends.accessToken(accessId)).getValue("text").jsonObject.string("text")
        assertEquals("Hello", accounts.decrypt(key, text).decodeToString())

        val cipherId = accounts.createLoginCipher(
            accessToken = accounts.login(account).accessToken,
            account = account,
            name = "Example",
            username = "alice",
            password = "hunter2",
        )
        val content = "Keyguard attachment".encodeToByteArray()
        val attached = withUploadFile(content) { path -> execute(BwCommand.Attach(bw(account), cipherId, path)) }
        assertEquals(content.size, attached.json.getValue("size").jsonPrimitive.int)
        val cipher = keyguardSync(keyguardLogin(account)).ciphers.orEmpty().single { it.id == cipherId }
        assertAttachment(cipher, account.userKey, attached, content)
    }

    @Test
    fun `attach to a cipher with its own key remains decryptable`() = runE2eTest {
        val account = accounts.register()
        val session = keyguardLogin(account)
        val itemKey = cryptoGenerator.makeCipherCryptoKeyMaterial()
        // The account key wraps the item key; the item key encrypts its fields and attachments.
        val request = loginCipherRequest(
            key = itemKey,
            encryptedFor = accounts.userId(session.login.accessToken),
            name = "Keyed item",
        ).copy(key = accounts.encrypt(account.userKey, itemKey))
        val created = session.env.api.ciphers.post(httpClient, session.env, session.login.accessToken, request)
        val content = "Keyguard attachment".encodeToByteArray()

        val attached = withUploadFile(content) { path ->
            execute(BwCommand.Attach(bw(account), created.id, path))
        }

        val cipher = keyguardSync(session).ciphers.orEmpty().single { it.id == created.id }
        val syncedItemKey = accounts.decrypt(account.userKey, assertNotNull(cipher.key))
        assertContentEquals(itemKey, syncedItemKey)
        assertEquals("Keyed item", accounts.decrypt(syncedItemKey, assertNotNull(cipher.name)).decodeToString())
        assertAttachment(cipher, syncedItemKey, attached, content)
    }

    @Test
    fun `attach to organization ciphers uses the organization and item keys`() = runE2eTest {
        val owner = accounts.register()
        val session = keyguardLogin(owner)
        val token = session.login.accessToken
        val org = organizations.createOrganization(owner, token, name = "Attachments")
        val content = "Shared attachment".encodeToByteArray()

        listOf(null, cryptoGenerator.makeCipherCryptoKeyMaterial()).forEach { itemKey ->
            val effectiveKey = itemKey ?: org.key
            val created = session.env.api.ciphers.create(
                httpClient = httpClient,
                env = session.env,
                token = token,
                body = CipherCreateRequest(
                    cipher = loginCipherRequest(
                        key = effectiveKey,
                        encryptedFor = accounts.userId(token),
                        name = "Shared item",
                        organizationId = org.id,
                    ).copy(key = itemKey?.let { accounts.encrypt(org.key, it) }),
                    collectionIds = setOf(org.collectionId),
                ),
            )

            val attached = withUploadFile(content) { path ->
                execute(BwCommand.Attach(bw(owner), created.id, path))
            }

            val cipher = keyguardSync(session).ciphers.orEmpty().single { it.id == created.id }
            assertEquals(org.id, cipher.organizationId)
            assertEquals("Shared item", accounts.decrypt(effectiveKey, assertNotNull(cipher.name)).decodeToString())
            assertAttachment(cipher, effectiveKey, attached, content)
        }
    }

    private fun VaultwardenFixture.bw(account: TestAccount) = BwAccount(baseUrl, account.email, account.password)

    /** The only attachment of [cipher] is the [attached] one, and [key] decrypts it to [content]. */
    private suspend fun VaultwardenFixture.assertAttachment(
        cipher: CipherEntity,
        key: ByteArray,
        attached: BwResult,
        content: ByteArray,
    ) {
        val attachment = cipher.attachments.orEmpty().single()
        assertEquals(attached.json.string("attachmentId"), attachment.id)
        assertEquals(attached.json.string("fileName"), accounts.decrypt(key, attachment.fileName).decodeToString())
        val attachmentKey = accounts.decrypt(key, attachment.key)
        val frame = httpClient.get(assertNotNull(attachment.url)).bodyAsBytes()
        assertContentEquals(content, EncryptedFileFrame.decrypt(attachmentKey, frame))
    }

    private fun JsonObject.items(name: String): List<JsonObject> = getValue(name).jsonArray.map { it.jsonObject }

    companion object {
        private const val PASSWORD = "correct horse battery staple"
    }
}
