package com.artemchep.keyguard.common.service.vault.impl

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.Fingerprint
import com.artemchep.keyguard.common.model.FingerprintBiometric
import com.artemchep.keyguard.common.model.FingerprintPassword
import com.artemchep.keyguard.common.model.MasterKdf
import com.artemchep.keyguard.common.model.MasterKdfVersion
import com.artemchep.keyguard.common.model.MasterPasswordHash
import com.artemchep.keyguard.common.model.MasterPasswordSalt
import com.artemchep.keyguard.common.service.keyvalue.impl.JsonKeyValueStore
import com.artemchep.keyguard.common.service.serialization.createApplicationJson
import com.artemchep.keyguard.common.service.text.impl.Base64ServiceImpl
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class FingerprintRepositoryImplTest {
    private val json = createApplicationJson()

    private val base64 = Base64ServiceImpl()

    private val store = JsonKeyValueStore()

    private val raw = store.getString(KEY, "")

    private val repository = FingerprintRepositoryImpl(
        store = store,
        json = json,
        base64Service = base64,
    )

    private val salt = ByteArray(SALT_SIZE) { (it + 1).toByte() }

    private val custom = MasterKdf.Argon2id(
        iterations = 7,
        memoryKib = 262144,
        parallelism = 2,
    )

    @Test
    fun `v1 key derivation writes version 1 with a plain salt`() = runTest {
        val fingerprint = derived(MasterKdf.Argon2id.V1)
        repository.put(fingerprint).bind()

        val entity = readEntity()
        assertEquals(1, entity.version)
        assertContentEquals(salt, base64.decode(entity.master.saltBase64))
        assertEquals(fingerprint, repository.get().first())
    }

    @Test
    fun `legacy key derivation writes version 0 with a plain salt`() = runTest {
        val fingerprint = derived(MasterKdf.Pbkdf2Legacy)
        repository.put(fingerprint).bind()

        val entity = readEntity()
        assertEquals(0, entity.version)
        assertContentEquals(salt, base64.decode(entity.master.saltBase64))
        assertEquals(fingerprint, repository.get().first())
    }

    @Test
    fun `wrapped master key writes version 2 with a header`() = runTest {
        val fingerprint = wrapped(custom)
        repository.put(fingerprint).bind()

        val entity = readEntity()
        assertEquals(2, entity.version)
        assertContentEquals(
            header(7, 262144, 2) + salt,
            base64.decode(entity.master.saltBase64),
        )
        assertContentEquals(wrappedKey, base64.decode(entity.master.hashBase64))

        val parsed = assertNotNull(repository.get().first())
        assertEquals(custom, parsed.master.kdf)
        assertEquals(MasterKdfVersion.V2, parsed.version)
        val master = assertIs<FingerprintPassword.Wrapped>(parsed.master)
        assertContentEquals(salt, master.salt.byteArray)
        assertContentEquals(wrappedKey, master.wrappedKey)
        assertEquals(fingerprint, parsed)
    }

    @Test
    fun `wrapped master key with the default parameters still writes a header`() = runTest {
        val fingerprint = wrapped(MasterKdf.Argon2id.V1)
        repository.put(fingerprint).bind()

        val entity = readEntity()
        assertEquals(2, entity.version)
        assertContentEquals(
            header(3, 65536, 4) + salt,
            base64.decode(entity.master.saltBase64),
        )
        assertEquals(fingerprint, repository.get().first())
    }

    @Test
    fun `version 2 with a short salt is unsupported`() = runTest {
        val bytes = header(7, 262144, 2)
        writeEntity(version = 2, saltBytes = bytes)

        val parsed = assertNotNull(repository.get().first())
        assertEquals(MasterKdf.Unsupported(MasterKdfVersion.V2), parsed.master.kdf)
        assertEquals(MasterKdfVersion.V2, parsed.version)
        val master = assertIs<FingerprintPassword.Wrapped>(parsed.master)
        assertContentEquals(bytes, master.salt.byteArray)
        assertContentEquals(ByteArray(32) { 0x42 }, master.wrappedKey)
        // The other unlock methods still open the vault.
        assertNotNull(parsed.biometric)
    }

    @Test
    fun `version 2 with values out of the native limits is unsupported`() = runTest {
        val headers = listOf(
            header(0, 262144, 2),
            header(7, 0, 2),
            header(7, 262144, 0),
            header(10_001, 262144, 2),
            header(7, 1_048_577, 2),
            header(7, 262144, 65),
            // Larger than a signed int.
            header(-1, 262144, 2),
        )
        headers.forEach { header ->
            writeEntity(version = 2, saltBytes = header + salt)

            val parsed = assertNotNull(repository.get().first())
            assertEquals(MasterKdf.Unsupported(MasterKdfVersion.V2), parsed.master.kdf)
            assertIs<FingerprintPassword.Wrapped>(parsed.master)
        }
    }

    @Test
    fun `version 2 accepts values within the native limits`() = runTest {
        writeEntity(version = 2, saltBytes = header(10_000, 1_048_576, 64) + salt)

        val parsed = assertNotNull(repository.get().first())
        assertEquals(
            MasterKdf.Argon2id(
                iterations = 10_000,
                memoryKib = 1_048_576,
                parallelism = 64,
            ),
            parsed.master.kdf,
        )
        assertIs<FingerprintPassword.Wrapped>(parsed.master)
        assertContentEquals(salt, parsed.master.salt.byteArray)
    }

    @Test
    fun `unknown version is unsupported and survives a rewrite`() = runTest {
        val bytes = byteArrayOf(9, 8, 7)
        writeEntity(version = 9, saltBytes = bytes)
        val before = raw.first()

        val parsed = assertNotNull(repository.get().first())
        assertEquals(MasterKdf.Unsupported(MasterKdfVersion.fromRaw(9)), parsed.master.kdf)
        assertIs<FingerprintPassword.Derived>(parsed.master)
        assertContentEquals(bytes, parsed.master.salt.byteArray)

        // Rewrite it through the domain model, as turning
        // biometric unlock on or off does.
        repository.update { it?.copy() }.bind()
        assertEquals(before, raw.first())
    }

    @Test
    fun `bad version 2 header survives a rewrite`() = runTest {
        writeEntity(version = 2, saltBytes = header(0, 262144, 2) + salt)
        val before = raw.first()

        repository.update { it?.copy() }.bind()
        assertEquals(before, raw.first())
    }

    @Test
    fun `wrapped master key survives a rewrite`() = runTest {
        repository.put(wrapped(custom)).bind()
        val before = raw.first()

        repository.update { it?.copy() }.bind()
        assertEquals(before, raw.first())
    }

    @Test
    fun `rewrite by an older build keeps the wrapped master key`() = runTest {
        val fingerprint = wrapped(custom)
        repository.put(fingerprint).bind()

        // An older build knows only these fields: it decodes the
        // fingerprint, drops the rest and writes it back.
        val old = json.decodeFromString<OldFingerprintEntity>(raw.first())
        val rewritten = old.copy(
            biometric = OldFingerprintEntity.Biometric(
                ivBase64 = base64.encodeToString(byteArrayOf(1, 2)),
                encryptedMasterKeyBase64 = base64.encodeToString(byteArrayOf(3, 4)),
            ),
        )
        raw.setAndCommit(json.encodeToString(rewritten)).bind()

        // The older build copies both fields verbatim.
        assertEquals(old.master, rewritten.master)

        val parsed = assertNotNull(repository.get().first())
        assertEquals(custom, parsed.master.kdf)
        val master = assertIs<FingerprintPassword.Wrapped>(parsed.master)
        assertContentEquals(salt, master.salt.byteArray)
        assertContentEquals(wrappedKey, master.wrappedKey)
        assertNotNull(parsed.biometric)
    }

    @Test
    fun `legacy pipe format parses as version 0`() = runTest {
        val hash = byteArrayOf(1, 2, 3)
        raw.setAndCommit(
            listOf(hash, salt).joinToString("|") { base64.encodeToString(it) },
        ).bind()

        val parsed = assertNotNull(repository.get().first())
        assertEquals(MasterKdf.Pbkdf2Legacy, parsed.master.kdf)
        val master = assertIs<FingerprintPassword.Derived>(parsed.master)
        assertEquals(MasterPasswordHash(MasterKdf.Pbkdf2Legacy, hash), master.hash)
        assertContentEquals(salt, parsed.master.salt.byteArray)
        assertEquals(null, parsed.biometric)
    }

    @Test
    fun `legacy pipe format with biometric parses as version 0`() = runTest {
        val hash = byteArrayOf(1, 2, 3)
        val encryptedMasterKey = byteArrayOf(4, 5)
        val iv = byteArrayOf(6, 7)
        raw.setAndCommit(
            listOf(hash, salt, encryptedMasterKey, iv)
                .joinToString("|") { base64.encodeToString(it) },
        ).bind()

        val parsed = assertNotNull(repository.get().first())
        assertEquals(MasterKdf.Pbkdf2Legacy, parsed.master.kdf)
        val biometric = assertIs<FingerprintBiometric>(parsed.biometric)
        assertContentEquals(iv, biometric.iv)
        assertContentEquals(encryptedMasterKey, biometric.encryptedMasterKey)
    }

    private val wrappedKey = "2.aXY=|Y3Q=|bWFj".encodeToByteArray()

    private fun derived(kdf: MasterKdf) = Fingerprint(
        master = FingerprintPassword.Derived(
            kdf = kdf,
            hash = MasterPasswordHash(
                kdf = kdf,
                byteArray = ByteArray(32) { 0x42 },
            ),
            salt = MasterPasswordSalt(salt),
        ),
        // The biometric token has no value equality.
        biometric = null,
    )

    private fun wrapped(kdf: MasterKdf.Argon2id) = Fingerprint(
        master = FingerprintPassword.Wrapped(
            kdf = kdf,
            salt = MasterPasswordSalt(salt),
            wrappedKey = wrappedKey,
        ),
        // The biometric token has no value equality.
        biometric = null,
    )

    private suspend fun readEntity(): FingerprintEntity =
        json.decodeFromString<FingerprintEntity>(raw.first())

    private suspend fun writeEntity(
        version: Int,
        saltBytes: ByteArray,
    ) {
        val entity = FingerprintEntity(
            version = version,
            master = FingerprintEntity.Master(
                hashBase64 = base64.encodeToString(ByteArray(32) { 0x42 }),
                saltBase64 = base64.encodeToString(saltBytes),
            ),
            biometric = FingerprintEntity.Biometric(
                ivBase64 = base64.encodeToString(byteArrayOf(1, 2)),
                encryptedMasterKeyBase64 = base64.encodeToString(byteArrayOf(3, 4)),
            ),
        )
        raw.setAndCommit(json.encodeToString(entity)).bind()
    }

    private fun header(
        iterations: Int,
        memoryKib: Int,
        parallelism: Int,
    ): ByteArray = listOf(iterations, memoryKib, parallelism)
        .flatMap { value ->
            listOf(
                (value ushr 24).toByte(),
                (value ushr 16).toByte(),
                (value ushr 8).toByte(),
                value.toByte(),
            )
        }
        .toByteArray()

    /**
     * The fingerprint as builds without the custom
     * key derivation know it.
     */
    @Serializable
    private data class OldFingerprintEntity(
        val version: Int,
        val master: Master,
        val biometric: Biometric?,
        val yubiKey: JsonElement? = null,
        val fido2: JsonElement? = null,
    ) {
        @Serializable
        data class Master(
            val hashBase64: String,
            val saltBase64: String,
        )

        @Serializable
        data class Biometric(
            val ivBase64: String,
            val encryptedMasterKeyBase64: String,
        )
    }

    private companion object {
        const val KEY = "data"

        const val SALT_SIZE = 64
    }
}
