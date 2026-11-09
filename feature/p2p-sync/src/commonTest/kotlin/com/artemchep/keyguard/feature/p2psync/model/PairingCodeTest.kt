package com.artemchep.keyguard.feature.p2psync.model

import com.artemchep.keyguard.feature.p2psync.crypto.P2pEncoding
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PairingCodeTest {
    private val psk = ByteArray(32) { it.toByte() }

    private val pub = ByteArray(32) { (it * 3).toByte() }

    private val minimal = PairingCode(
        psk = psk,
        pub = pub,
        member = true,
        expiresAt = 1_700_000_180,
        scope = SyncScope.APP,
    )

    private val code = PairingCode(
        psk = psk,
        pub = pub,
        member = true,
        expiresAt = 1_700_000_180,
        scope = SyncScope.APP,
        relay = "wss://relay.example.com",
        ice = "https://relay.example.com/v1/ice-servers",
    )

    /** The characters a QR code holds in its alphanumeric mode. */
    private val qrAlphanumeric = ('0'..'9') + ('A'..'Z') + " $%*+-./:".toList()

    @Test
    fun `the bytes of a code follow its layout`() {
        val bytes = minimal.toByteArray()
        assertEquals(75, bytes.size)
        assertEquals(PairingCode.MIN_SIZE, bytes.size)
        assertEquals(PairingCode.MAGIC, bytes[0].toInt())
        assertEquals(PairingCode.VERSION, bytes[1].toInt())
        // The member bit, and the scope APP, number 0, in the bits above it.
        assertEquals(0b0000_0001, bytes[2].toInt())
        assertContentEquals(psk, bytes.copyOfRange(3, 35))
        assertContentEquals(pub, bytes.copyOfRange(35, 67))
        // 1_700_000_180 seconds, big-endian.
        assertContentEquals(byteArrayOf(0x65, 0x53, 0xF1.toByte(), 0xB4.toByte()), bytes.copyOfRange(67, 71))
        // No relay and no ICE URL: the defaults.
        assertContentEquals(ByteArray(4), bytes.copyOfRange(71, 75))

        val joiner = PairingCode(psk = psk, pub = pub, member = false, expiresAt = 0, scope = SyncScope.APP)
        assertEquals(0, joiner.toByteArray()[2].toInt())
    }

    @Test
    fun `the URLs follow the fixed fields with their lengths`() {
        val bytes = code.toByteArray()
        val relay = "wss://relay.example.com".encodeToByteArray()
        val ice = "https://relay.example.com/v1/ice-servers".encodeToByteArray()
        assertEquals(PairingCode.MIN_SIZE + relay.size + ice.size, bytes.size)
        assertEquals(relay.size, (bytes[71].toInt() shl 8) or bytes[72].toInt())
        assertContentEquals(relay, bytes.copyOfRange(73, 73 + relay.size))
        val iceAt = 73 + relay.size
        assertEquals(ice.size, (bytes[iceAt].toInt() shl 8) or bytes[iceAt + 1].toInt())
        assertContentEquals(ice, bytes.copyOfRange(iceAt + 2, bytes.size))
    }

    @Test
    fun `the text form is the prefix and uppercase base32, all of it QR alphanumeric`() {
        val text = minimal.encode()
        assertTrue(text.startsWith("KGP:"))
        // 75 bytes are 120 base32 characters, without padding.
        assertEquals(4 + 120, text.length)
        assertEquals(P2pEncoding.base32UpperCase(minimal.toByteArray()), text.removePrefix("KGP:"))
        assertTrue(text.all { it in qrAlphanumeric }, text)
        assertTrue(code.encode().all { it in qrAlphanumeric })
    }

    @Test
    fun `a code round trips through its text form`() {
        assertEquals(PairingCodeDecoding.Valid(code), PairingCode.decode(code.encode()))
        assertEquals(PairingCodeDecoding.Valid(minimal), PairingCode.decode(minimal.encode()))
        assertEquals(PairingCodeDecoding.Valid(code), PairingCode.decode("  ${code.encode()}\n"))
        assertEquals(code.encode(), PairingCode.decode(code.encode()).validOrNull()?.encode())
    }

    @Test
    fun `the text form is read in either case`() {
        val text = code.encode()
        assertEquals(PairingCodeDecoding.Valid(code), PairingCode.decode(text.lowercase()))
        val mixed = text.mapIndexed { i, c -> if (i % 2 == 0) c.lowercaseChar() else c }.joinToString("")
        assertEquals(PairingCodeDecoding.Valid(code), PairingCode.decode(" \t$mixed "))
    }

    @Test
    fun `an empty URL is the default one`() {
        val empty = PairingCode(psk = psk, pub = pub, member = true, expiresAt = 1, scope = SyncScope.APP, relay = "")
        assertNull(empty.relay)
        assertEquals(PairingCode.MIN_SIZE, empty.toByteArray().size)
    }

    @Test
    fun `a code keeps its keys out of its text`() {
        val text = code.toString()
        assertFalse(P2pEncoding.base64Url(psk) in text)
        assertFalse(psk.joinToString() in text)
    }

    @Test
    fun `other text is refused for its prefix`() {
        assertEquals(PairingCodeDecoding.Invalid(PairingCodeError.WRONG_PREFIX), PairingCode.decode("hello"))
        assertEquals(PairingCodeDecoding.Invalid(PairingCodeError.WRONG_PREFIX), PairingCode.decode(""))
        assertEquals(
            PairingCodeDecoding.Invalid(PairingCodeError.WRONG_PREFIX),
            PairingCode.decode(code.encode().removePrefix("KGP:")),
        )
        // The JSON codes of before are no codes.
        assertEquals(PairingCodeDecoding.Invalid(PairingCodeError.WRONG_PREFIX), PairingCode.decode("kgp1.eyJ2IjoxfQ"))
    }

    @Test
    fun `codes of another version say which device needs an update`() {
        assertEquals(
            PairingCodeDecoding.Invalid(PairingCodeError.NEWER_VERSION),
            PairingCode.decode(textOf(minimal.toByteArray().also { it[1] = 2 })),
        )
        // A newer code may change everything after its version.
        assertEquals(
            PairingCodeDecoding.Invalid(PairingCodeError.NEWER_VERSION),
            PairingCode.decode(textOf(byteArrayOf(PairingCode.MAGIC.toByte(), 9, 1, 2, 3))),
        )
        assertEquals(
            PairingCodeDecoding.Invalid(PairingCodeError.OLDER_VERSION),
            PairingCode.decode(textOf(minimal.toByteArray().also { it[1] = 0 })),
        )
    }

    @Test
    fun `damaged codes are refused`() {
        val bytes = code.toByteArray()
        listOf(
            // Not base32.
            "KGP:!!!",
            "KGP:ABC=",
            // A length no bytes encode to, and stray bits.
            "KGP:A",
            code.encode() + "A",
            "KGP:AB",
            // Another magic.
            textOf(bytes.copyOf().also { it[0] = 0 }),
            // Cut off in each field, and with bytes after its end that are no extension.
            textOf(bytes.copyOf(1)),
            textOf(bytes.copyOf(2)),
            textOf(bytes.copyOf(20)),
            textOf(bytes.copyOf(50)),
            textOf(bytes.copyOf(70)),
            textOf(bytes.copyOf(72)),
            textOf(bytes.copyOf(bytes.size - 1)),
            textOf(bytes + 0),
            textOf(bytes + byteArrayOf(1, 0)),
            textOf(bytes + byteArrayOf(1, 0, 5, 1)),
            textOf(bytes + extension(1, byteArrayOf(1)) + 0),
            // A flag this build does not know.
            textOf(bytes.copyOf().also { it[2] = (it[2].toInt() or 0x80).toByte() }),
            // A URL that is not UTF-8.
            textOf(minimal.toByteArray().withRelay(byteArrayOf(0xC3.toByte(), 0x28))),
        ).forEach { text ->
            assertEquals(PairingCodeDecoding.Invalid(PairingCodeError.MALFORMED), PairingCode.decode(text), text)
        }
        assertEquals(PairingCodeDecoding.Invalid(PairingCodeError.MALFORMED), PairingCode.decode("KGP:"))
    }

    @Test
    fun `a code skips the extensions it does not know, unless they are critical`() {
        val extended = code.toByteArray() + extension(1, byteArrayOf(1, 2, 3)) + extension(0x7F, ByteArray(0))
        assertEquals(PairingCodeDecoding.Valid(code), PairingCode.decode(textOf(extended)))
        // Up to the limit, the types and lengths included.
        val largest = minimal.toByteArray() + extension(2, ByteArray(PairingCode.MAX_EXTENSIONS_SIZE - 3))
        assertEquals(PairingCodeDecoding.Valid(minimal), PairingCode.decode(textOf(largest)))
        val oversized = minimal.toByteArray() + extension(2, ByteArray(PairingCode.MAX_EXTENSIONS_SIZE - 2))
        assertEquals(PairingCodeDecoding.Invalid(PairingCodeError.MALFORMED), PairingCode.decode(textOf(oversized)))

        // A critical extension is one a newer build made the code with.
        val critical = code.toByteArray() + extension(1, byteArrayOf(1)) + extension(0x81, byteArrayOf(1))
        assertEquals(PairingCodeDecoding.Invalid(PairingCodeError.NEWER_VERSION), PairingCode.decode(textOf(critical)))
    }

    @Test
    fun `a code is made only with keys of the right size`() {
        assertFailsWith<IllegalArgumentException> {
            PairingCode(psk = ByteArray(16), pub = pub, member = true, expiresAt = 1, scope = SyncScope.APP)
        }
        assertFailsWith<IllegalArgumentException> {
            PairingCode(psk = psk, pub = ByteArray(33), member = true, expiresAt = 1, scope = SyncScope.APP)
        }
        assertFailsWith<IllegalArgumentException> {
            PairingCode(psk = psk, pub = pub, member = true, expiresAt = 1L shl 32, scope = SyncScope.APP)
        }
    }

    @Test
    fun `a code checks its scope`() {
        assertEquals(PairingCodeDecoding.Valid(code), PairingCode.decode(code.encode(), expected = SyncScope.APP))
        // A scope this build does not know, such as the vaults.
        val vault = minimal.toByteArray().also { it[2] = (it[2].toInt() or (1 shl 1)).toByte() }
        assertEquals(PairingCodeDecoding.Invalid(PairingCodeError.WRONG_SCOPE), PairingCode.decode(textOf(vault)))
        assertEquals(
            PairingCodeDecoding.Invalid(PairingCodeError.WRONG_SCOPE),
            PairingCode.decode(textOf(vault), expected = SyncScope.APP),
        )
    }

    @Test
    fun `a code expires at its expiry`() {
        assertEquals(1_700_000_180_000L, code.expiresAtMillis)
        assertFalse(code.isExpired(code.expiresAtMillis - 1))
        assertTrue(code.isExpired(code.expiresAtMillis))
    }

    @Test
    fun `a code holds URLs up to the limit, and text longer than any code is refused`() {
        val url = "wss://" + "a".repeat(PairingCode.MAX_URL_SIZE - 6)
        val largest = PairingCode(
            psk = psk,
            pub = pub,
            member = false,
            expiresAt = 1,
            scope = SyncScope.APP,
            relay = url,
            ice = url,
        )
        assertTrue(largest.encode().length <= PairingCode.MAX_TEXT_LENGTH)
        assertEquals(PairingCodeDecoding.Valid(largest), PairingCode.decode(largest.encode()))
        val extended = textOf(largest.toByteArray() + extension(1, ByteArray(PairingCode.MAX_EXTENSIONS_SIZE - 3)))
        assertTrue(extended.length <= PairingCode.MAX_TEXT_LENGTH)
        assertEquals(PairingCodeDecoding.Valid(largest), PairingCode.decode(extended))
        assertFailsWith<IllegalArgumentException> {
            PairingCode(psk = psk, pub = pub, member = false, expiresAt = 1, scope = SyncScope.APP, relay = url + "a")
        }
        // A longer URL is refused even where the text fits.
        val long = minimal.toByteArray().withRelay(ByteArray(PairingCode.MAX_URL_SIZE + 1) { 'a'.code.toByte() })
        assertEquals(PairingCodeDecoding.Invalid(PairingCodeError.MALFORMED), PairingCode.decode(textOf(long)))

        val padded = code.encode() + " ".repeat(10) + "x".repeat(PairingCode.MAX_TEXT_LENGTH)
        assertEquals(PairingCodeDecoding.Invalid(PairingCodeError.MALFORMED), PairingCode.decode(padded))
        val oversized = "KGP:" + "A".repeat(PairingCode.MAX_TEXT_LENGTH)
        assertEquals(PairingCodeDecoding.Invalid(PairingCodeError.MALFORMED), PairingCode.decode(oversized))
        // Surrounding whitespace does not count.
        val spaced = " ".repeat(PairingCode.MAX_TEXT_LENGTH) + code.encode()
        assertEquals(PairingCodeDecoding.Valid(code), PairingCode.decode(spaced))
    }

    @Test
    fun `base32 follows RFC 4648`() {
        val vectors = mapOf(
            "" to "",
            "f" to "MY",
            "fo" to "MZXQ",
            "foo" to "MZXW6",
            "foob" to "MZXW6YQ",
            "fooba" to "MZXW6YTB",
            "foobar" to "MZXW6YTBOI",
        )
        vectors.forEach { (plain, encoded) ->
            assertEquals(encoded, P2pEncoding.base32UpperCase(plain.encodeToByteArray()))
            assertEquals(encoded.lowercase(), P2pEncoding.base32(plain.encodeToByteArray()))
            assertEquals(plain, P2pEncoding.base32OrNull(encoded)?.decodeToString())
            assertEquals(plain, P2pEncoding.base32OrNull(encoded.lowercase())?.decodeToString())
        }
        // Stray bits, a length no bytes encode to, padding and other characters.
        listOf("MZ", "M", "MZX", "MY======", "MZ1", "MZ8").forEach {
            assertNull(P2pEncoding.base32OrNull(it), it)
        }
    }

    private fun textOf(bytes: ByteArray): String = PairingCode.PREFIX + P2pEncoding.base32UpperCase(bytes)

    /** Returns the bytes of an extension of the [type] with the [value]. */
    private fun extension(type: Int, value: ByteArray): ByteArray =
        byteArrayOf(type.toByte(), (value.size shr 8).toByte(), value.size.toByte()) + value

    /** Returns the bytes of a code without URLs, with a [relay] URL of these bytes. */
    private fun ByteArray.withRelay(relay: ByteArray): ByteArray {
        val fixed = copyOf(PairingCode.MIN_SIZE - 4)
        val length = byteArrayOf((relay.size shr 8).toByte(), relay.size.toByte())
        return fixed + length + relay + byteArrayOf(0, 0)
    }

    private fun PairingCodeDecoding.validOrNull(): PairingCode? = (this as? PairingCodeDecoding.Valid)?.code
}
