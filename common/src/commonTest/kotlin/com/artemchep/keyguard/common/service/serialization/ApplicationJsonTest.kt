package com.artemchep.keyguard.common.service.serialization

import com.artemchep.keyguard.common.service.gpmprivapps.PrivilegedAppListEntity
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.BitwardenToken
import com.artemchep.keyguard.core.store.bitwarden.KeePassToken
import com.artemchep.keyguard.core.store.bitwarden.ServiceToken
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ApplicationJsonTest {
    private val json = createApplicationJson()

    @Test
    fun `legacy attachments without a discriminator remain readable`() {
        val attachment = json.decodeFromString<BitwardenCipher.Attachment>(
            """{id:legacy,url:null,fileName:file,size:12,ignored:true}""",
        )
        assertEquals(BitwardenCipher.Attachment.Remote("legacy", null, "file", size = 12), attachment)
    }

    @Test
    fun `local and remote attachments retain their discriminator`() {
        val attachments = listOf(
            BitwardenCipher.Attachment.Remote("remote", "https://example.com", "remote.txt", size = 12),
            BitwardenCipher.Attachment.Local("local", "file:///local.txt", "local.txt"),
        )
        attachments.forEach { attachment ->
            val encoded = json.encodeToString<BitwardenCipher.Attachment>(attachment)
            assertEquals(attachment, json.decodeFromString<BitwardenCipher.Attachment>(encoded))
        }
    }

    @Test
    fun `legacy accounts fall back to Bitwarden and coerce unknown regions`() {
        val account = json.decodeFromString<ServiceToken>(
            """{
                "id":"legacy",
                "key":{"masterKeyBase64":"a","passwordKeyBase64":"b","encryptionKeyBase64":"c","macKeyBase64":"d"},
                "user":{"email":"user@example.com"},
                "env":{"region":"future-region"},
                "ignored":true
            }""",
        )
        assertIs<BitwardenToken>(account)
        assertEquals("legacy", account.id)
        assertEquals(BitwardenToken.Environment.Region.US, account.env.region)
        assertEquals(account, json.decodeFromString<ServiceToken>(json.encodeToString<ServiceToken>(account)))
    }

    @Test
    fun `KeePass accounts retain their type and legacy location migration`() {
        val account: ServiceToken = KeePassToken(
            id = "keepass",
            key = KeePassToken.Key(passwordBase64 = "password"),
            files = KeePassToken.Files(databaseUri = "file:///vault.kdbx"),
        )
        assertEquals(account, json.decodeFromString<ServiceToken>(json.encodeToString(account)))
    }

    @Test
    fun `unknown privileged apps do not prevent reading Android entries`() {
        val catalog = json.decodeFromString<PrivilegedAppListEntity>(
            """{"apps":[
                {"type":"future","unexpected":true},
                {"type":"android","info":{"package_name":"test.app","signatures":[]}}
            ]}""",
        )
        assertEquals(PrivilegedAppListEntity.App.Unknown, catalog.apps[0])
        val android = assertIs<PrivilegedAppListEntity.App.AndroidApp>(catalog.apps[1])
        assertEquals("test.app", android.info.packageName)
    }
}
