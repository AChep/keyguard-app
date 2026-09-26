package app.keemobile.kotpass.database

import app.keemobile.kotpass.errors.FormatError
import app.keemobile.kotpass.models.BinaryData
import com.artemchep.keyguard.util.foundation.crypto.sha256
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

class BinaryIdentityTest {
    @Test
    fun preservesDifferentContentsWithIdenticalStoredBytesInEitherOrder() {
        val text = "attachment".encodeToByteArray()
        val compressed = BinaryData.Uncompressed(false, text).toCompressed()
        val raw = BinaryData.Uncompressed(false, compressed.rawContent)

        for (values in listOf(listOf(compressed, raw), listOf(raw, compressed))) {
            val pool = BinaryPool().apply {
                values.forEachIndexed { ref, binary -> add(ref, binary) }
            }
            val index = BinaryIndex(pool)

            assertEquals(2, pool.size)
            values.forEachIndexed { ref, binary ->
                assertContentEquals(binary.getContent(), index.getByRef(ref)?.data?.getContent())
            }
            assertContentEquals(text, index.findByContentSha256(sha256(text))?.data?.getContent())
            assertContentEquals(
                raw.rawContent,
                index.findByContentSha256(sha256(raw.rawContent))?.data?.getContent(),
            )
        }
    }

    @Test
    fun rawContentCannotImpersonateTheCompressedHashPrefix() {
        val compressed = BinaryData.Uncompressed(false, "attachment".encodeToByteArray())
            .toCompressed()
        val raw = BinaryData.Uncompressed(false, byteArrayOf(1) + compressed.rawContent)

        assertNotEquals(compressed.hash, raw.hash)
        val index = BinaryIndex(mapOf(compressed.hash to compressed, raw.hash to raw))
        assertNotNull(index.getByHash(compressed.hash))
        assertContentEquals(raw.rawContent, index.getByHash(raw.hash)?.data?.getContent())
    }

    @Test
    fun deduplicatesMatchingRepresentationsWithoutMergingCompressionVariants() {
        val raw = BinaryData.Uncompressed(false, "attachment".encodeToByteArray())
        val compressed = raw.toCompressed()
        val pool = BinaryPool().apply {
            add(0, raw)
            add(1, BinaryData.Uncompressed(false, raw.rawContent.copyOf()))
            add(2, compressed)
            add(3, BinaryData.Compressed(false, compressed.rawContent.copyOf()))
        }

        assertEquals(2, pool.size)
        assertEquals(pool.hashesByRef[0], pool.hashesByRef[1])
        assertEquals(pool.hashesByRef[2], pool.hashesByRef[3])
        assertNotEquals(pool.hashesByRef[0], pool.hashesByRef[2])
        assertContentEquals(raw.getContent(), compressed.getContent())
    }

    @Test
    fun identityIncludesMemoryProtectionWithoutDecompressing() {
        val invalidGzip = byteArrayOf(1, 2, 3)
        val compressed = BinaryData.Compressed(true, invalidGzip)
        val unprotected = BinaryData.Compressed(false, invalidGzip)

        assertEquals(true, compressed.memoryProtection)
        assertNotEquals(compressed.hash, unprotected.hash)
        assertEquals(32, compressed.hash.size)
        assertFailsWith<FormatError.FailedCompression> { compressed.getContent() }
    }

    @Test
    fun preservesProtectionVariantsInEitherInsertionOrder() {
        for (compressed in listOf(false, true)) {
            for (protectedFirst in listOf(false, true)) {
                val values = listOf(protectedFirst, !protectedFirst).map { protected ->
                    if (compressed) {
                        BinaryData.Compressed(protected, byteArrayOf(1, 2, 3))
                    } else {
                        BinaryData.Uncompressed(protected, byteArrayOf(1, 2, 3))
                    }
                }
                val pool = BinaryPool().apply {
                    values.forEachIndexed { ref, binary -> add(ref, binary) }
                    values.forEachIndexed { ref, binary -> add(ref + 2, binary) }
                }
                assertEquals(2, pool.size)
                val index = BinaryIndex(pool)
                values.forEachIndexed { ref, binary ->
                    assertEquals(binary.memoryProtection, index.getByRef(ref)?.data?.memoryProtection)
                    assertEquals(pool.hashesByRef[ref], pool.hashesByRef[ref + 2])
                }
                assertNotEquals(pool.hashesByRef[0], pool.hashesByRef[1])
            }
        }
    }
}
