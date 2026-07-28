package com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.codec

import app.keemobile.kotpass.models.EntryValue
import app.keemobile.kotpass.models.XmlExtension
import app.keemobile.kotpass.models.XmlExtensionContent
import app.keemobile.kotpass.models.XmlNamespace
import app.keemobile.kotpass.models.XmlQualifiedName
import com.artemchep.keyguard.common.service.cipherlink.CipherLinkFields
import com.artemchep.keyguard.common.service.file.FileService
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.provider.bitwarden.sync.v2.UploadTestPasswordStrength
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.buildEntry
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.testBase32Service
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.testBase64Service
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.testBitwardenCipher
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.testCryptoGenerator
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.testJson
import kotlinx.coroutines.test.runTest
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class KeePassCipherCodecTest {
    private val codec = KeePassCipherCodec(
        cryptoGenerator = testCryptoGenerator,
        base32Service = testBase32Service,
        base64Service = testBase64Service,
        fileService = UnusedFileService,
        getPasswordStrength = UploadTestPasswordStrength,
        json = testJson,
    )

    @Test
    fun `encode preserves remote XML extensions on current and historical entries`() = runTest {
        val extension = XmlExtension(
            name = XmlQualifiedName(
                localName = "PluginData",
                namespaceUri = "urn:keyguard:test-plugin",
                prefix = "plugin",
            ),
            namespaces = listOf(
                XmlNamespace(
                    prefix = "plugin",
                    namespaceUri = "urn:keyguard:test-plugin",
                ),
            ),
            content = listOf(
                XmlExtensionContent.Text(EntryValue.Plain("plugin-value")),
            ),
        )
        val remote = buildEntry(title = "Before edit").copy(
            extensions = listOf(extension),
        )

        val encoded = codec.encode(
            local = testBitwardenCipher(
                cipherId = remote.uuid.toString(),
                name = "After edit",
            ),
            remote = remote,
            existingBinaries = emptyMap(),
        )

        assertEquals(remote.extensions, encoded.entry.extensions)
        assertEquals(remote.extensions, encoded.entry.history.single().extensions)
    }

    @Test
    fun `decode collapses duplicate cipher links by canonical target`() = runTest {
        val remote = buildEntry(
            extraFields = linkedMapOf(
                CipherLinkFields.fieldName(2) to EntryValue.Plain(
                    "keyguard://cipher/${TARGET_REMOTE_ID.uppercase()}",
                ),
                CipherLinkFields.fieldName(1) to EntryValue.Plain(
                    "keyguard://cipher/$TARGET_REMOTE_ID",
                ),
            ),
        )

        val decoded = codec.decode(
            accountId = "account",
            folderId = null,
            cipherId = "cipher",
            remote = remote,
            local = testBitwardenCipher(cipherId = "cipher"),
            revisionDate = Instant.parse("2024-01-01T00:00:00Z"),
            binaries = emptyMap(),
        )

        assertEquals(
            listOf(BitwardenCipher.Link(TARGET_REMOTE_ID)),
            decoded.links,
        )
        assertEquals(emptyList(), decoded.fields)
    }
}

private const val TARGET_REMOTE_ID = "b0eebc99-9c0b-4ef8-bb6d-6bb9bd380a12"

private object UnusedFileService : FileService {
    override fun exists(uri: String): Boolean = error("Not used by this test")

    override fun readFromFile(uri: String): Source = error("Not used by this test")

    override fun writeToFile(uri: String): Sink = error("Not used by this test")

    override fun delete(uri: String): Boolean = error("Not used by this test")
}
