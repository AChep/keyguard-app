package app.keemobile.kotpass.database

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.header.DatabaseHeader
import app.keemobile.kotpass.database.header.KdfParameters
import app.keemobile.kotpass.database.modifiers.modifyBinaries
import app.keemobile.kotpass.models.BinaryData
import app.keemobile.kotpass.models.Meta
import okio.Buffer
import okio.ByteString.Companion.toByteString
import okio.Source
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlinx.io.Buffer as KotlinxBuffer

class KdbxBinaryContentVisitorTest {
    private val credentials = Credentials.from(
        EncryptedValue.fromString("streaming-test-password"),
    )

    @Test
    fun visitsUncompressedAndCompressedKdbx3Binaries() {
        val uncompressed = "plain attachment".encodeToByteArray()
        val compressedContent = ByteArray(256 * 1024) { index ->
            (index % 251).toByte()
        }
        val database = database(
            majorVersion = 3,
            binaries = listOf(
                BinaryData.Uncompressed(false, uncompressed),
                BinaryData.Uncompressed(false, compressedContent).toCompressed(),
            ),
        )

        val visited = visit(database.encode())

        assertEquals(2, visited.size)
        assertContentEquals(uncompressed, visited[0].content)
        assertContentEquals(compressedContent, visited[1].content)
        // XML-embedded binaries never declare their decoded size upfront.
        assertEquals(listOf(null, null), visited.map { it.declaredLength })
    }

    @Test
    fun visitsEmptyAndDuplicateContentKdbx4Binaries() {
        val duplicate = ByteArray(128 * 1024) { index ->
            (index * 13).toByte()
        }
        val database = database(
            majorVersion = 4,
            binaries = listOf(
                BinaryData.Uncompressed(false, ByteArray(0)),
                BinaryData.Uncompressed(false, duplicate),
                BinaryData.Uncompressed(false, duplicate).toCompressed(),
            ),
        )

        val visited = visit(database.encode())

        assertEquals(3, visited.size)
        assertContentEquals(ByteArray(0), visited[0].content)
        assertContentEquals(duplicate, visited[1].content)
        assertContentEquals(duplicate, visited[2].content)
        // KDBX 4 inner-header binaries declare their exact size upfront.
        assertEquals(
            visited.map { it.content.size.toLong() },
            visited.map { it.declaredLength },
        )
    }

    @Test
    fun drainsEachCandidateWhenVisitorReadsOnlyPrefix() {
        val first = ByteArray(256 * 1024) { 1 }
        val second = ByteArray(128 * 1024) { 2 }
        val database = database(
            majorVersion = 4,
            binaries = listOf(
                BinaryData.Uncompressed(false, first),
                BinaryData.Uncompressed(false, second),
            ),
        )
        val prefixes = mutableListOf<ByteArray>()

        KeePassDatabase.visitBinaryContents(
            source = KotlinxBuffer().apply { write(database.encode()) },
            credentials = credentials,
            visitor = KdbxBinaryContentVisitor { source, _ ->
                prefixes += source.readExactly(17)
            },
        )

        assertEquals(2, prefixes.size)
        assertContentEquals(first.copyOf(17), prefixes[0])
        assertContentEquals(second.copyOf(17), prefixes[1])
    }

    @Test
    fun rejectsWrongCredentials() {
        val encoded = database(
            majorVersion = 4,
            binaries = listOf(BinaryData.Uncompressed(false, "secret".encodeToByteArray())),
        ).encode()
        val wrongCredentials = Credentials.from(
            EncryptedValue.fromString("wrong-password"),
        )

        assertFails {
            KeePassDatabase.visitBinaryContents(
                source = KotlinxBuffer().apply { write(encoded) },
                credentials = wrongCredentials,
                visitor = KdbxBinaryContentVisitor { source, _ -> source.drain() },
            )
        }
    }

    @Test
    fun validatesDataFollowingVisitedBinary() {
        val encoded = database(
            majorVersion = 4,
            binaries = listOf(BinaryData.Uncompressed(false, "target".encodeToByteArray())),
        ).encode()
        val truncated = encoded.copyOf(encoded.size - 8)
        var targetWasVisited = false

        assertFails {
            KeePassDatabase.visitBinaryContents(
                source = KotlinxBuffer().apply { write(truncated) },
                credentials = credentials,
                visitor = KdbxBinaryContentVisitor { source, _ ->
                    source.drain()
                    targetWasVisited = true
                },
            )
        }
        assertEquals(true, targetWasVisited)
    }

    private fun visit(
        encoded: ByteArray,
    ): List<VisitedBinary> = buildList {
        KeePassDatabase.visitBinaryContents(
            source = KotlinxBuffer().apply { write(encoded) },
            credentials = credentials,
            visitor = KdbxBinaryContentVisitor { source, declaredLength ->
                add(
                    VisitedBinary(
                        content = source.readAll(),
                        declaredLength = declaredLength,
                    ),
                )
            },
        )
    }

    private fun database(
        majorVersion: Int,
        binaries: List<BinaryData>,
    ): KeePassDatabase {
        val base = when (majorVersion) {
            3 -> KeePassDatabase.Ver3x.create(
                rootName = "Root",
                meta = Meta(name = "Streaming test"),
                credentials = credentials,
            ).let { database ->
                database.copy(
                    header = database.header.copy(
                        compression = DatabaseHeader.Compression.None,
                        transformRounds = 1U,
                    ),
                )
            }

            4 -> KeePassDatabase.Ver4x.create(
                rootName = "Root",
                meta = Meta(name = "Streaming test"),
                credentials = credentials,
            ).let { database ->
                database.copy(
                    header = database.header.copy(
                        compression = DatabaseHeader.Compression.None,
                        kdfParameters = KdfParameters.Aes(
                            rounds = 1U,
                            seed = ByteArray(32) { index -> index.toByte() }.toByteString(),
                        ),
                    ),
                )
            }

            else -> error("Unsupported test version.")
        }
        return base.modifyBinaries {
            binaries.associateByTo(linkedMapOf()) { binary -> binary.hash }
        }
    }
}

private class VisitedBinary(
    val content: ByteArray,
    val declaredLength: Long?,
)

private fun Source.readAll(): ByteArray {
    val output = Buffer()
    drain(output)
    return output.readByteArray()
}

private fun Source.readExactly(
    byteCount: Long,
): ByteArray {
    val output = Buffer()
    var remaining = byteCount
    while (remaining > 0L) {
        val read = read(output, remaining)
        check(read != -1L) { "Source ended early." }
        remaining -= read
    }
    return output.readByteArray()
}

private fun Source.drain(
    output: Buffer = Buffer(),
) {
    while (read(output, 64 * 1024L) != -1L) {
        // Drain.
    }
}
