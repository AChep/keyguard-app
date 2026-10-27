package com.artemchep.keyguard.provider.bitwarden.crypto

import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Pins the Bitwarden transport fields with hand-written samples. Installed
 * versions read these fields, so a failure here is a compatibility break.
 */
class BitwardenEnvironmentFieldCodecTest {
    private val document = """{"version":1,"variables":[""" +
        """{"name":"TOKEN","source":{"kind":"literal","value":"secret"}}]}"""

    private val fields = listOf(
        hidden("keyguard.environment.v1.part.1", document),
        hidden("keyguard.environment.v1.sha256", "1cbd072117d6357a9636e176830326204752605262f06b983069d12a26c9c8b6"),
    )

    @Test
    fun `single part sample decodes and encodes to the same fields`() {
        val ordinary = BitwardenCipher.Field(name = "ordinary", value = "kept", type = BitwardenCipher.Field.Type.Text)
        val decoded = assertNotNull(BitwardenEnvironmentFieldCodec.decode(listOf(ordinary) + fields))
        assertEquals(BitwardenCipher.Environment(document = document), decoded.environment)
        assertEquals(listOf(ordinary), decoded.remainingFields)
        assertEquals(fields, BitwardenEnvironmentFieldCodec.encode(decoded.environment))
    }

    @Test
    fun `parts are cut at UTF-8 character boundaries`() {
        // The 4-byte key emoji starts 2 bytes before the 3,500-byte part limit.
        val prefix = """{"version":1,"variables":[{"name":"TOKEN","source":{"kind":"literal","value":"""" +
            "a".repeat(3_420)
        val suffix = "🔑" + "b".repeat(10) + """"}}]}"""
        val sample = listOf(
            hidden("keyguard.environment.v1.part.1", prefix),
            hidden("keyguard.environment.v1.part.2", suffix),
            hidden(
                "keyguard.environment.v1.sha256",
                "625f63dee5651974d0a285e99d67656e594897aebb071470651476f765ea2aac",
            ),
        )
        val decoded = assertNotNull(BitwardenEnvironmentFieldCodec.decode(sample))
        assertEquals(prefix + suffix, decoded.environment.document)
        assertEquals(sample, BitwardenEnvironmentFieldCodec.encode(decoded.environment))
    }

    @Test
    fun `an intact document of any version is kept as a document`() {
        val future = """{"version":2}"""
        val encoded = BitwardenEnvironmentFieldCodec.encode(BitwardenCipher.Environment(document = future))
        val decoded = assertNotNull(BitwardenEnvironmentFieldCodec.decode(encoded))
        assertEquals(future, decoded.environment.document)
    }

    @Test
    fun `damaged fields are not an environment`() {
        val large = BitwardenEnvironmentFieldCodec.encode(BitwardenCipher.Environment(document = "a".repeat(8_000)))
        val cases = listOf(
            large.dropLast(1),
            large + large.first(),
            large.mapIndexed { index, field -> if (index == 0) field.copy(value = "altered") else field },
            large.mapIndexed { index, field -> if (index == 0) field.copy(name = field.name + " #1") else field },
            large.mapIndexed { index, field ->
                if (index == 0) field.copy(type = BitwardenCipher.Field.Type.Text) else field
            },
            large.mapIndexed { index, field ->
                if (index == 0) field.copy(name = "keyguard.environment.v2.part.1") else field
            },
            listOf(hidden("keyguard.environment.unknown", "x")),
        )
        cases.forEach { damaged ->
            assertNull(BitwardenEnvironmentFieldCodec.decode(damaged))
        }
    }

    @Test
    fun `other fields under the environment name are ordinary fields`() {
        val note = hidden("keyguard.environment.note", "mine")
        val decoded = assertNotNull(BitwardenEnvironmentFieldCodec.decode(fields + note))
        assertEquals(document, decoded.environment.document)
        assertEquals(listOf(note), decoded.remainingFields)
        assertNull(BitwardenEnvironmentFieldCodec.decode(listOf(note)))
    }

    @Test
    fun `fields without the prefix carry no environment`() {
        val field = hidden("environment", document)
        assertNull(BitwardenEnvironmentFieldCodec.decode(listOf(field)))
    }

    private fun hidden(name: String, value: String) = BitwardenCipher.Field(
        name = name,
        value = value,
        type = BitwardenCipher.Field.Type.Hidden,
    )
}
