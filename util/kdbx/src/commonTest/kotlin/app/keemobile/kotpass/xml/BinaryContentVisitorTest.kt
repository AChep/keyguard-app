package app.keemobile.kotpass.xml

import app.keemobile.kotpass.constants.CrsAlgorithm
import app.keemobile.kotpass.cryptography.EncryptionSaltGenerator
import app.keemobile.kotpass.errors.FormatError
import app.keemobile.kotpass.io.encodeBase64
import app.keemobile.kotpass.io.gzip
import okio.Buffer
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class BinaryContentVisitorTest {
    private val streamKey = ByteArray(64) { index ->
        (index * 11).toByte()
    }.toByteString()

    @Test
    fun decodesChunkedWhitespaceAndUnpaddedBase64() {
        val content = ByteArray(256 * 1024 + 2) { index ->
            (index % 251).toByte()
        }
        val encoded = content.encodeBase64()
            .trimEnd('=')
            .chunked(71)
            .joinToString(separator = " \n\t")
        val xml = document(
            valueAttributes = "",
            encodedValue = encoded,
        )

        val actual = visit(
            xml = xml,
            limits = XmlReadLimits(maxScalarChars = 8),
        )

        assertContentEquals(content, actual)
    }

    @Test
    fun decodesLargeCDataWithoutMaterializingAScalar() {
        val content = ByteArray(256 * 1024 + 5) { index ->
            (index * 19).toByte()
        }
        val xml = document(
            valueAttributes = "",
            encodedValue = "<![CDATA[${content.encodeBase64()}]]>",
        )

        val actual = visit(
            xml = xml,
            limits = XmlReadLimits(maxScalarChars = 8),
        )

        assertContentEquals(content, actual)
    }

    @Test
    fun decryptsAndInflatesProtectedInlineBinaryInDocumentOrder() {
        val content = ByteArray(384 * 1024 + 7) { index ->
            (index * 17).toByte()
        }
        val encryptor = EncryptionSaltGenerator.create(
            id = CrsAlgorithm.ChaCha20,
            key = streamKey,
        )
        val precedingCiphertext = encryptor.processBytes(
            "protected field before attachment".encodeToByteArray(),
        )
        val binaryCiphertext = encryptor.processBytes(content.gzip())
        val xml = document(
            precedingProtectedValue = precedingCiphertext.encodeBase64(),
            valueAttributes = """Protected="True" Compressed="True"""",
            encodedValue = binaryCiphertext
                .encodeBase64()
                .chunked(97)
                .joinToString("\n"),
        )

        val actual = visit(xml)

        assertContentEquals(content, actual)
    }

    @Test
    fun rejectsMalformedBinaryBase64() {
        val xml = document(
            valueAttributes = "",
            encodedValue = "valid-prefix!invalid",
        )

        assertFailsWith<FormatError.InvalidXml> {
            visit(xml)
        }
    }

    private fun visit(
        xml: String,
        limits: XmlReadLimits = XmlReadLimits.Default,
    ): ByteArray {
        val binaries = mutableListOf<ByteArray>()
        visitXmlBinaryContents(
            source = Buffer().writeUtf8(xml),
            innerEncryption = EncryptionSaltGenerator.create(
                id = CrsAlgorithm.ChaCha20,
                key = streamKey,
            ),
            visitor = XmlBinaryContentVisitor { source, declaredLength ->
                assertNull(declaredLength)
                val output = Buffer()
                while (source.read(output, 64 * 1024L) != -1L) {
                    // Read the full candidate.
                }
                binaries += output.readByteArray()
            },
            limits = limits,
        )
        return binaries.single()
    }

    private fun document(
        valueAttributes: String,
        encodedValue: String,
        precedingProtectedValue: String? = null,
    ): String {
        val preceding = precedingProtectedValue?.let { value ->
            """
                <String>
                    <Key>Password</Key>
                    <Value Protected="True">$value</Value>
                </String>
            """.trimIndent()
        }.orEmpty()
        return """
            <KeePassFile>
                <Root>
                    <Group>
                        <Entry>
                            $preceding
                            <Binary>
                                <Key>attachment.bin</Key>
                                <Value $valueAttributes>$encodedValue</Value>
                            </Binary>
                        </Entry>
                    </Group>
                </Root>
            </KeePassFile>
        """.trimIndent()
    }
}
