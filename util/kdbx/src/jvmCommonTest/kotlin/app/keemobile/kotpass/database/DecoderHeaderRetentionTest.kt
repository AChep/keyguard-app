package app.keemobile.kotpass.database

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.cryptography.EncryptionSaltGenerator
import app.keemobile.kotpass.database.header.DatabaseHeader
import app.keemobile.kotpass.database.header.KdfParameters
import app.keemobile.kotpass.database.modifiers.binaries
import app.keemobile.kotpass.database.modifiers.modifyBinaries
import app.keemobile.kotpass.io.KotlinxSourceAdapter
import app.keemobile.kotpass.io.TeeBufferedStream
import app.keemobile.kotpass.models.BinaryData
import app.keemobile.kotpass.models.DatabaseContent
import app.keemobile.kotpass.models.Meta
import app.keemobile.kotpass.models.XmlContext
import app.keemobile.kotpass.xml.DefaultXmlContentParser
import app.keemobile.kotpass.xml.XmlContentParser
import kotlinx.io.asSource
import kotlinx.io.buffered
import okio.Buffer
import okio.BufferedSource
import okio.ByteString.Companion.toByteString
import java.io.File
import java.lang.reflect.Modifier
import java.util.IdentityHashMap
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DecoderHeaderRetentionTest {
    private val credentials = Credentials.from(EncryptedValue.fromString("header-retention-test"))

    @Test
    fun modelDecodeReleasesHeaderCaptureBeforeParsingXml() = withDatabases { database, file ->
        var parsed = false
        val parser = object : XmlContentParser by DefaultXmlContentParser {
            override fun unmarshalContent(
                source: BufferedSource,
                innerEncryption: EncryptionSaltGenerator,
                contextBlock: (Meta) -> XmlContext.Decode,
            ): DatabaseContent {
                assertHeaderCaptureDetached(source)
                return DefaultXmlContentParser.unmarshalContent(source, innerEncryption, contextBlock)
                    .also {
                        assertHeaderCaptureDetached(source)
                        parsed = true
                    }
            }
        }
        val decoded = file.inputStream().asSource().buffered().use { source ->
            KeePassDatabase.decode(
                source = source,
                credentials = credentials,
                contentParser = parser,
            )
        }
        assertTrue(parsed)
        assertEquals(database.binaries.keys, decoded.binaries.keys)
    }

    @Test
    fun binaryVisitorReleasesHeaderCaptureWhileDiscardingLargeBinaries() = withDatabases { _, file ->
        var consumed = 0L
        file.inputStream().asSource().buffered().use { input ->
            KeePassDatabase.visitBinaryContents(
                source = input,
                credentials = credentials,
                visitor = KdbxBinaryContentVisitor { source, _ ->
                    assertHeaderCaptureDetached(source)
                    val discard = Buffer()
                    while (true) {
                        val read = source.read(discard, 8192L)
                        if (read == -1L) break
                        consumed += read
                        discard.clear()
                    }
                    assertHeaderCaptureDetached(source)
                },
            )
        }
        assertEquals(ATTACHMENT_SIZE.toLong(), consumed)
    }

    private fun withDatabases(block: (KeePassDatabase, File) -> Unit) {
        // Random bytes keep the encoded input larger than one content block
        // even when GZip is enabled. File-backed input avoids an input Buffer
        // intentionally owning the entire encoded database during the test.
        val binary = BinaryData.Uncompressed(false, Random(0).nextBytes(ATTACHMENT_SIZE))
        for (version in listOf(3, 4)) {
            for (compression in DatabaseHeader.Compression.entries) {
                val base = when (version) {
                    3 -> KeePassDatabase.Ver3x.create("Root", Meta(), credentials).let {
                        it.copy(header = it.header.copy(compression = compression, transformRounds = 1U))
                    }
                    else -> KeePassDatabase.Ver4x.create("Root", Meta(), credentials).let {
                        it.copy(header = it.header.copy(
                            compression = compression,
                            kdfParameters = KdfParameters.Aes(
                                rounds = 1U,
                                seed = ByteArray(32) { it.toByte() }.toByteString(),
                            ),
                        ))
                    }
                }
                val database = base.modifyBinaries { linkedMapOf(binary.hash to binary) }
                val file = File.createTempFile("kdbx-header-retention-", ".kdbx")
                try {
                    file.writeBytes(database.encode())
                    assertTrue(file.length() > ContentBlocks.BLOCK_SPLIT_RATE)
                    block(database, file)
                } finally {
                    file.delete()
                }
            }
        }
    }

    private fun assertHeaderCaptureDetached(source: Any) {
        // Inspect the live stream chain instead of relying on GC timing or
        // total heap measurements. A tee here retains all consumed ciphertext.
        val seen = IdentityHashMap<Any, Boolean>()
        var reachedInput = false
        fun inspect(value: Any) {
            if (seen.put(value, true) != null) return
            assertFalse(value is TeeBufferedStream, "Body stream retains its header-capturing tee")
            if (value is KotlinxSourceAdapter) {
                reachedInput = true
            }
            if (value is KotlinxSourceAdapter || value is Buffer || !isStreamWrapper(value)) return
            var type: Class<*>? = value.javaClass
            while (type != null && !type.name.startsWith("java.")) {
                for (field in type.declaredFields) {
                    if (Modifier.isStatic(field.modifiers) || field.type.isPrimitive) continue
                    field.isAccessible = true
                    field.get(value)?.let(::inspect)
                }
                type = type.superclass
            }
        }
        inspect(source)
        // Prevent a changed wrapper from silently making this probe vacuous.
        assertTrue(reachedInput, "Could not follow the body stream back to the encoded input")
    }

    private fun isStreamWrapper(value: Any): Boolean {
        val name = value.javaClass.name
        return name.startsWith("app.keemobile.kotpass.") ||
            name.startsWith("okio.") ||
            name.startsWith("nl.adaptivity.xmlutil.")
    }

    private companion object {
        const val ATTACHMENT_SIZE = 2 * 1024 * 1024 + 1024
    }
}
