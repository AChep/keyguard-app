package com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.codec

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryValue
import com.artemchep.keyguard.core.store.bitwarden.CipherSourceCanonicalPaths
import com.artemchep.keyguard.core.store.bitwarden.CipherSourceData
import com.artemchep.keyguard.core.store.bitwarden.CipherSourceProviderIds
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.buildEntry
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.testBase32Service
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.testBase64Service
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.testJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeePassTotpCodecTest {
    private val codec = KeePassTotpCodec(
        base32Service = testBase32Service,
        base64Service = testBase64Service,
    )

    @Test
    fun `decode reads single-field totp conventions`() {
        assertEquals(
            "otpauth://totp/Example?secret=XYZ",
            decode(
                "otp" to EntryValue.Plain("otpauth://totp/Example?secret=XYZ"),
            ).totp?.value,
        )
        assertEquals(
            "otpauth://totp/Example?secret=ABC",
            decode(
                "OTPAuth" to EntryValue.Plain("otpauth://totp/Example?secret=ABC"),
            ).totp?.value,
        )
    }

    @Test
    fun `decode reads otp KeeOTP query string`() {
        val secret = "JBSWY3DPEHPK3PXP"
        val decoded = decode(
            "otp" to EntryValue.Plain("key=$secret&step=45&size=8&otpHashMode=SHA256"),
        )

        assertEquals(
            "otpauth://totp/?secret=$secret&period=45&digits=8&algorithm=SHA256",
            decoded.totp?.value,
        )
        assertConsumed(decoded, "otp")

        val uriWithKeeOtpParams = "otpauth://totp/Example?secret=URISECRET&key=QUERYSECRET&step=45&size=8"
        val uriDecoded = decode(
            "otp" to EntryValue.Plain(uriWithKeeOtpParams),
        )
        assertEquals(uriWithKeeOtpParams, uriDecoded.totp?.value)
        assertConsumed(uriDecoded, "otp")
    }

    @Test
    fun `decode reads TOTP seed and settings split fields`() {
        val decoded = decode(
            "TOTP Seed" to EntryValue.Plain("JBSW Y3DP EHPK 3PXP"),
            "TOTP Settings" to EntryValue.Plain("45;8"),
        )

        assertEquals(
            "otpauth://totp/?secret=JBSWY3DPEHPK3PXP&period=45&digits=8",
            decoded.totp?.value,
        )
        assertConsumed(decoded, "TOTP Seed", "TOTP Settings")
    }

    @Test
    fun `decode reads TimeOtp secret fields`() {
        val base32 = decode(
            "TimeOtp-Secret-Base32" to EntryValue.Plain("JBSWY3DPEHPK3PXP"),
        )
        assertEquals("JBSWY3DPEHPK3PXP", base32.totp?.value)
        assertConsumed(base32, "TimeOtp-Secret-Base32")

        val hex = decode(
            "TimeOtp-Secret-Hex" to EntryValue.Plain("68656c6c6f"),
            "TimeOtp-Period" to EntryValue.Plain("60"),
            "TimeOtp-Length" to EntryValue.Plain("8"),
            "TimeOtp-Algorithm" to EntryValue.Plain("HMAC-SHA-256"),
        )
        assertEquals(
            "otpauth://totp/?secret=NBSWY3DP&period=60&digits=8&algorithm=SHA256",
            hex.totp?.value,
        )
        assertConsumed(hex, "TimeOtp-Secret-Hex", "TimeOtp-Period", "TimeOtp-Length", "TimeOtp-Algorithm")
    }

    @Test
    fun `decode preserves invalid TimeOtp sidecars as custom fields`() {
        val decoded = decode(
            "TimeOtp-Secret-Base32" to EntryValue.Plain("JBSWY3DPEHPK3PXP"),
            "TimeOtp-Period" to EntryValue.Plain("0"),
        )

        assertEquals("JBSWY3DPEHPK3PXP", decoded.totp?.value)
        assertConsumed(decoded, "TimeOtp-Secret-Base32")
        assertAvailable(decoded, "TimeOtp-Period")
    }

    @Test
    fun `decode reads HmacOtp hotp fields`() {
        val secret = "JBSWY3DPEHPK3PXP"
        val decoded = decode(
            "HmacOtp-Secret-Base32" to EntryValue.Plain(secret),
            "HmacOtp-Counter" to EntryValue.Plain("42"),
        )

        assertEquals(
            "otpauth://hotp/?secret=$secret&counter=42",
            decoded.totp?.value,
        )
        assertConsumed(decoded, "HmacOtp-Secret-Base32", "HmacOtp-Counter")
    }

    @Test
    fun `decode precedence keeps non-winning OTP fields available`() {
        decode(
            "otp" to EntryValue.Plain("otpauth://totp/Example?secret=OTPPRIMARY"),
            "OTPAuth" to EntryValue.Plain("otpauth://totp/Example?secret=OTPAUTH"),
        ).also { decoded ->
            assertEquals("otpauth://totp/Example?secret=OTPPRIMARY", decoded.totp?.value)
            assertConsumed(decoded, "otp")
            assertAvailable(decoded, "OTPAuth")
        }

        decode(
            "OTPAuth" to EntryValue.Plain("otpauth://totp/Example?secret=OTPAUTH"),
            "TOTP Seed" to EntryValue.Plain("TOTPSEED"),
            "TOTP Settings" to EntryValue.Plain("45;8"),
        ).also { decoded ->
            assertEquals("otpauth://totp/Example?secret=OTPAUTH", decoded.totp?.value)
            assertConsumed(decoded, "OTPAuth")
            assertAvailable(decoded, "TOTP Seed", "TOTP Settings")
        }

        decode(
            "TOTP Seed" to EntryValue.Plain("TOTPSEED"),
            "TOTP Settings" to EntryValue.Plain("45;8"),
            "TimeOtp-Secret-Base32" to EntryValue.Plain("TIMEOTP"),
        ).also { decoded ->
            assertEquals("otpauth://totp/?secret=TOTPSEED&period=45&digits=8", decoded.totp?.value)
            assertConsumed(decoded, "TOTP Seed", "TOTP Settings")
            assertAvailable(decoded, "TimeOtp-Secret-Base32")
        }

        decode(
            "TimeOtp-Secret-Base32" to EntryValue.Plain("TIMEOTP"),
            "HmacOtp-Secret-Base32" to EntryValue.Plain("HMACOTP"),
            "HmacOtp-Counter" to EntryValue.Plain("42"),
        ).also { decoded ->
            assertEquals("TIMEOTP", decoded.totp?.value)
            assertConsumed(decoded, "TimeOtp-Secret-Base32")
            assertAvailable(decoded, "HmacOtp-Secret-Base32", "HmacOtp-Counter")
        }

        decode(
            "HmacOtp-Secret-Base32" to EntryValue.Plain("HMACOTP"),
            "HmacOtp-Counter" to EntryValue.Plain("42"),
            "otp_1" to EntryValue.Plain("otpauth://totp/Example?secret=OTPONE"),
        ).also { decoded ->
            assertEquals("otpauth://hotp/?secret=HMACOTP&counter=42", decoded.totp?.value)
            assertConsumed(decoded, "HmacOtp-Secret-Base32", "HmacOtp-Counter")
            assertAvailable(decoded, "otp_1")
        }
    }

    @Test
    fun `decode precedence keeps secondary secret encodings available`() {
        decode(
            "TimeOtp-Secret-Base32" to EntryValue.Plain("BASE32SECRET"),
            "TimeOtp-Secret-Hex" to EntryValue.Plain("68656c6c6f"),
        ).also { decoded ->
            assertEquals("BASE32SECRET", decoded.totp?.value)
            assertConsumed(decoded, "TimeOtp-Secret-Base32")
            assertAvailable(decoded, "TimeOtp-Secret-Hex")
        }

        decode(
            "HmacOtp-Secret-Base32" to EntryValue.Plain("BASE32SECRET"),
            "HmacOtp-Secret-Hex" to EntryValue.Plain("68656c6c6f"),
            "HmacOtp-Counter" to EntryValue.Plain("42"),
        ).also { decoded ->
            assertEquals("otpauth://hotp/?secret=BASE32SECRET&counter=42", decoded.totp?.value)
            assertConsumed(decoded, "HmacOtp-Secret-Base32", "HmacOtp-Counter")
            assertAvailable(decoded, "HmacOtp-Secret-Hex")
        }
    }

    @Test
    fun `decode does not treat display or embedded OTP fields as configuration`() {
        val display = decode(
            "OTP Token" to EntryValue.Plain("123456"),
            "{TOTP}" to EntryValue.Plain("234567"),
            "{TIMEOTP}" to EntryValue.Plain("345678"),
        )
        assertNull(display.totp)
        assertAvailable(display, "OTP Token", "{TOTP}", "{TIMEOTP}")

        val embedded = decode(
            entry = buildEntry(
                password = "otpauth://totp/Password?secret=PASSWORD",
                notes = "Strongbox TOTP Auth URL: [otpauth://totp/Notes?secret=NOTES]",
            ),
        )
        assertNull(embedded.totp)
    }

    @Test
    fun `decode records source bindings without duplicating secrets`() {
        val otpAuth = "otpauth://totp/Example?secret=ABC"
        decode(
            "OTPAuth" to EntryValue.Plain(otpAuth),
        ).totp
            .let(::assertNotNull)
            .also { decoded ->
                val binding = decoded.binding
                assertEquals(CipherSourceCanonicalPaths.LOGIN_TOTP, binding.canonicalFields.single().path)
                assertEquals(KeePassTotpProjectionIds.SINGLE_FIELD, binding.projection.id)
                val sourceField = binding.sourceFields.single()
                assertEquals("OTPAuth", sourceField.key)
                assertEquals(KeePassTotpRepresentationIds.OTPAUTH_URI, sourceField.representationId)
                assertEquals(false, sourceField.concealed)
                val serialized = testJson.encodeToString(sourceData(decoded))
                assertFalse(serialized.contains("ABC"))
                assertFalse(serialized.contains(otpAuth))
            }

        val base32 = "JBSWY3DPEHPK3PXP"
        decode(
            "TimeOtp-Secret-Base32" to EntryValue.Plain(base32),
        ).totp
            .let(::assertNotNull)
            .also { decoded ->
                val binding = decoded.binding
                assertEquals(CipherSourceCanonicalPaths.LOGIN_TOTP, binding.canonicalFields.single().path)
                assertEquals(KeePassTotpProjectionIds.SINGLE_FIELD, binding.projection.id)
                val sourceField = binding.sourceFields.single()
                assertEquals("TimeOtp-Secret-Base32", sourceField.key)
                assertEquals(KeePassTotpRepresentationIds.BASE32_SECRET, sourceField.representationId)
                assertEquals(false, sourceField.concealed)
                val serialized = testJson.encodeToString(sourceData(decoded))
                assertFalse(serialized.contains(base32))
            }
    }

    @Test
    fun `encode preserves decoded source field names and encodings`() {
        assertEncodedAfterDecode(
            fields = arrayOf(
                "OTPAuth" to EntryValue.Plain("otpauth://totp/Example?secret=ABC"),
            ),
            expectedWrites = mapOf("OTPAuth" to "otpauth://totp/Example?secret=ABC"),
        )

        assertEncodedAfterDecode(
            fields = arrayOf(
                "TOTP Seed" to EntryValue.Plain("JBSWY3DPEHPK3PXP"),
                "TOTP Settings" to EntryValue.Plain("45;8"),
            ),
            expectedWrites = mapOf(
                "TOTP Seed" to "JBSWY3DPEHPK3PXP",
                "TOTP Settings" to "45;8",
            ),
        )

        assertEncodedAfterDecode(
            fields = arrayOf(
                "TimeOtp-Secret-Hex" to EntryValue.Plain("68656c6c6f"),
                "TimeOtp-Period" to EntryValue.Plain("60"),
                "TimeOtp-Length" to EntryValue.Plain("8"),
                "TimeOtp-Algorithm" to EntryValue.Plain("HMAC-SHA-256"),
            ),
            expectedWrites = mapOf(
                "TimeOtp-Secret-Hex" to "68656c6c6f",
                "TimeOtp-Period" to "60",
                "TimeOtp-Length" to "8",
                "TimeOtp-Algorithm" to "HMAC-SHA-256",
            ),
        )

        assertEncodedAfterDecode(
            fields = arrayOf(
                "HmacOtp-Secret-Base32" to EntryValue.Plain("JBSWY3DPEHPK3PXP"),
                "HmacOtp-Counter" to EntryValue.Plain("42"),
            ),
            expectedWrites = mapOf(
                "HmacOtp-Secret-Base32" to "JBSWY3DPEHPK3PXP",
                "HmacOtp-Counter" to "42",
            ),
        )
    }

    @Test
    fun `encode preserves raw and base64 TimeOtp and HmacOtp secrets`() {
        assertEncodedAfterDecode(
            fields = arrayOf(
                "TimeOtp-Secret" to EntryValue.Encrypted(EncryptedValue.fromString("Hello")),
            ),
            expectedWrites = mapOf("TimeOtp-Secret" to "Hello"),
        )

        val timeOtpBase64Bytes = byteArrayOf(1, 2, 3, 4, 5)
        val timeOtpBase64 = testBase64Service.encodeToString(timeOtpBase64Bytes)
        assertEncodedAfterDecode(
            fields = arrayOf(
                "TimeOtp-Secret-Base64" to EntryValue.Encrypted(EncryptedValue.fromString(timeOtpBase64)),
            ),
            expectedWrites = mapOf("TimeOtp-Secret-Base64" to timeOtpBase64),
            expectedDecodedValue = testBase32Service.encodeToString(timeOtpBase64Bytes).trimEnd('='),
        )

        assertEncodedAfterDecode(
            fields = arrayOf(
                "HmacOtp-Secret" to EntryValue.Encrypted(EncryptedValue.fromString("Hello")),
                "HmacOtp-Counter" to EntryValue.Plain("5"),
            ),
            expectedWrites = mapOf(
                "HmacOtp-Secret" to "Hello",
                "HmacOtp-Counter" to "5",
            ),
        )

        val hmacOtpBase64 = testBase64Service.encodeToString(byteArrayOf(9, 8, 7, 6))
        assertEncodedAfterDecode(
            fields = arrayOf(
                "HmacOtp-Secret-Base64" to EntryValue.Encrypted(EncryptedValue.fromString(hmacOtpBase64)),
                "HmacOtp-Counter" to EntryValue.Plain("7"),
            ),
            expectedWrites = mapOf(
                "HmacOtp-Secret-Base64" to hmacOtpBase64,
                "HmacOtp-Counter" to "7",
            ),
        )
    }

    @Test
    fun `encode new base32 and base64 totp values uses TimeOtp fields`() {
        val base32 = "JBSWY3DPEHPK3PXP"
        codec.encode(base32, sourceData = null).also { encoded ->
            assertWrite(encoded, "TimeOtp-Secret-Base32", base32, concealed = true)
            assertNull(encoded.writes.firstOrNull { it.key == "otp" })
            assertEquals(KeePassTotpProjectionIds.TIME_OTP, encoded.sourceData?.bindings?.single()?.projection?.id)
            assertEquals(KeePassTotpRepresentationIds.BASE32_SECRET, encoded.sourceData?.bindings?.single()?.sourceFields?.single()?.representationId)

            val decoded = decode(entryFrom(encoded))
            assertEquals(base32, decoded.totp?.value)
        }

        val rawBytes = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
        val base64 = testBase64Service.encodeToString(rawBytes)
        codec.encode(base64, sourceData = null).also { encoded ->
            assertWrite(encoded, "TimeOtp-Secret-Base64", base64, concealed = true)
            assertNull(encoded.writes.firstOrNull { it.key == "otp" })
            assertEquals(KeePassTotpProjectionIds.TIME_OTP, encoded.sourceData?.bindings?.single()?.projection?.id)
            assertEquals(KeePassTotpRepresentationIds.TIME_OTP_SECRET_BASE64, encoded.sourceData?.bindings?.single()?.sourceFields?.single()?.representationId)

            val decoded = decode(entryFrom(encoded))
            assertEquals(testBase32Service.encodeToString(rawBytes).trimEnd('='), decoded.totp?.value)
        }
    }

    @Test
    fun `encode new short and invalid raw totp values stays in otp field`() {
        listOf(
            "JBSWY3DP",
            testBase64Service.encodeToString(byteArrayOf(1, 2, 3, 4, 5)),
            "QUJDREVGR0hJSg=!",
        ).forEach { value ->
            val encoded = codec.encode(value, sourceData = null)

            assertNull(encoded.writes.firstOrNull { it.key == "TimeOtp-Secret-Base32" })
            assertNull(encoded.writes.firstOrNull { it.key == "TimeOtp-Secret-Base64" })
            val otp = assertWrite(encoded, "otp", concealed = true)
            assertTrue(otp.content.startsWith("otpauth://totp/"))
        }
    }

    @Test
    fun `encode opaque OTP URIs verbatim in otp field`() {
        listOf(
            "steam://GEZDGNBVGY3TQOJQ",
            "motp://Example:alice?secret=abcdef0123456789&pin=1234",
        ).forEach { value ->
            val encoded = codec.encode(value, sourceData = null)

            assertWrite(encoded, "otp", value, concealed = true)
            assertNull(encoded.writes.firstOrNull { it.key == "TimeOtp-Secret-Base32" })
            assertNull(encoded.writes.firstOrNull { it.key == "TimeOtp-Secret-Base64" })

            val decoded = decode(entryFrom(encoded))
            assertEquals(value, decoded.totp?.value)
        }
    }

    @Test
    fun `encode removes decoded OTP source when canonical value is empty`() {
        val decoded = assertNotNull(
            decode(
                "OTPAuth" to EntryValue.Plain("otpauth://totp/Example?secret=ABC"),
            ).totp,
        )

        val encoded = codec.encode(canonicalTotp = null, sourceData = sourceData(decoded))

        assertTrue(encoded.writes.isEmpty())
        assertNull(encoded.sourceData)
    }

    private fun assertEncodedAfterDecode(
        fields: Array<Pair<String, EntryValue>>,
        expectedWrites: Map<String, String>,
        expectedDecodedValue: String? = null,
    ) {
        val decoded = assertNotNull(decode(*fields).totp)
        expectedDecodedValue?.let { expected ->
            assertEquals(expected, decoded.value)
        }

        val encoded = codec.encode(
            canonicalTotp = decoded.value,
            sourceData = sourceData(decoded),
        )

        expectedWrites.forEach { (key, value) ->
            assertWrite(
                encoded = encoded,
                key = key,
                content = value,
                concealed = key.contains("Secret") || key in setOf("OTPAuth", "TOTP Seed"),
            )
        }
        assertNull(encoded.writes.firstOrNull { it.key == "otp" })
    }

    private fun sourceData(
        decoded: KeePassTotpCodec.DecodedTotp,
    ): CipherSourceData = CipherSourceData(
        providerId = CipherSourceProviderIds.KEEPASS,
        bindings = listOf(decoded.binding),
    )

    private fun decode(
        vararg fields: Pair<String, EntryValue>,
    ): DecodeResult = decode(buildEntry(extraFields = mapOf(*fields)))

    private fun decode(
        entry: Entry,
    ): DecodeResult {
        val scope = DecodeToCipherScope(entry)
        val decoded = codec.decode(scope = scope, remote = entry)
        return DecodeResult(
            totp = decoded,
            availableFields = scope.getAvailableFields(),
        )
    }

    private fun entryFrom(
        encoded: KeePassTotpCodec.EncodedTotp,
    ): Entry = buildEntry(
        extraFields = encoded.writes.associate { write -> write.key to write.value },
    )

    private fun assertConsumed(
        decoded: DecodeResult,
        vararg keys: String,
    ) {
        keys.forEach { key ->
            assertFalse(key in decoded.availableFields)
        }
    }

    private fun assertAvailable(
        decoded: DecodeResult,
        vararg keys: String,
    ) {
        keys.forEach { key ->
            assertTrue(key in decoded.availableFields)
        }
    }

    private fun assertWrite(
        encoded: KeePassTotpCodec.EncodedTotp,
        key: String,
        content: String? = null,
        concealed: Boolean,
    ): EntryValue {
        val value = assertNotNull(encoded.writes.firstOrNull { it.key == key }?.value)
        if (content != null) {
            assertEquals(content, value.content)
        }
        assertEquals(concealed, value is EntryValue.Encrypted)
        return value
    }

    private data class DecodeResult(
        val totp: KeePassTotpCodec.DecodedTotp?,
        val availableFields: Map<String, EntryValue>,
    )
}
