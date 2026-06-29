package com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.codec

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.models.EntryValue
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.buildEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KeePassUrlCodecTest {
    private val codec = KeePassUrlCodec()

    @Test
    fun `encode writes android uris as KeePassDX AndroidApp fields`() {
        val writes = codec.encode(
            listOf(
                BitwardenCipher.Login.Uri(uri = "androidapp://com.example.one"),
                BitwardenCipher.Login.Uri(uri = "https://example.com"),
                BitwardenCipher.Login.Uri(uri = "androidapp://com.example.two"),
                BitwardenCipher.Login.Uri(uri = "androidapp://com.example.three"),
            ),
        )

        assertEquals("https://example.com", writes.content("URL"))
        assertEquals("com.example.one", writes.content("AndroidApp"))
        assertEquals("com.example.two", writes.content("AndroidApp_1"))
        assertEquals("com.example.three", writes.content("AndroidApp_2"))
        assertTrue(writes.none { it.key == "KP2A_URL_1" })
    }

    @Test
    fun `encode writes android signatures as protected companion fields`() {
        val writes = codec.encode(
            listOf(
                BitwardenCipher.Login.Uri(
                    uri = "androidapp://com.example.app",
                    match = BitwardenCipher.Login.Uri.MatchType.Host,
                    signatures = listOf(
                        BitwardenCipher.Login.Uri.Signature(FINGERPRINT_LOWERCASE),
                        BitwardenCipher.Login.Uri.Signature(FINGERPRINT_NO_COLONS),
                    ),
                ),
            ),
        )

        assertEquals("com.example.app", writes.content("AndroidApp"))
        assertEquals("Host", writes.content("AndroidApp_MATCH_TYPE"))
        val signature = writes.single { it.key == "AndroidApp Signature" }
        assertTrue(signature.value is EntryValue.Encrypted)
        assertEquals("$FINGERPRINT_CANONICAL##SIG##$FINGERPRINT_CANONICAL_NO_COLONS", signature.value.content)
    }

    @Test
    fun `decode reads paired android signatures`() {
        val entry = buildEntry(
            extraFields = linkedMapOf(
                "AndroidApp" to EntryValue.Plain("com.example.app"),
                "AndroidApp Signature" to EntryValue.Encrypted(
                    EncryptedValue.fromString("$FINGERPRINT_LOWERCASE##SIG##$FINGERPRINT_NO_COLONS"),
                ),
            ),
        )
        val scope = DecodeToCipherScope(entry)

        val uris = codec.decode(scope, entry)

        assertEquals("androidapp://com.example.app", uris.single().uri)
        assertEquals(
            listOf(FINGERPRINT_CANONICAL, FINGERPRINT_CANONICAL_NO_COLONS),
            uris.single().signatures.map { it.certFingerprintSha256 },
        )
        assertEquals(
            "$FINGERPRINT_CANONICAL##SIG##$FINGERPRINT_CANONICAL_NO_COLONS",
            codec.encode(uris).content("AndroidApp Signature"),
        )
        assertTrue(scope.getAvailableFields().none { it.key == "AndroidApp" })
        assertTrue(scope.getAvailableFields().none { it.key == "AndroidApp Signature" })
    }

    @Test
    fun `decode leaves invalid android signature as custom field`() {
        val entry = buildEntry(
            extraFields = linkedMapOf(
                "AndroidApp" to EntryValue.Plain("com.example.app"),
                "AndroidApp Signature" to EntryValue.Encrypted(EncryptedValue.fromString("fingerprint")),
            ),
        )
        val scope = DecodeToCipherScope(entry)

        val uris = codec.decode(scope, entry)

        assertEquals("androidapp://com.example.app", uris.single().uri)
        assertEquals(emptyList(), uris.single().signatures)
        assertTrue(scope.getAvailableFields().none { it.key == "AndroidApp" })
        assertEquals("fingerprint", scope.getAvailableFields()["AndroidApp Signature"]?.content)
    }

    @Test
    fun `decode ignores orphan android signature`() {
        val entry = buildEntry(
            extraFields = linkedMapOf(
                "AndroidApp Signature" to EntryValue.Encrypted(EncryptedValue.fromString(FINGERPRINT_CANONICAL)),
            ),
        )
        val scope = DecodeToCipherScope(entry)

        val uris = codec.decode(scope, entry)

        assertEquals(emptyList(), uris)
        assertEquals(FINGERPRINT_CANONICAL, scope.getAvailableFields()["AndroidApp Signature"]?.content)
    }

    private fun List<KeePassFieldWrite>.content(key: String): String? =
        singleOrNull { it.key == key }?.value?.content

    private companion object {
        const val FINGERPRINT_CANONICAL =
            "00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF"
        const val FINGERPRINT_LOWERCASE =
            "00:11:22:33:44:55:66:77:88:99:aa:bb:cc:dd:ee:ff:00:11:22:33:44:55:66:77:88:99:aa:bb:cc:dd:ee:ff"
        const val FINGERPRINT_NO_COLONS =
            "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF"
        const val FINGERPRINT_CANONICAL_NO_COLONS =
            "FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF"
    }
}
