package com.artemchep.keyguard.provider.keyguard.applink

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Every device builds the same blob from the same words, and reads only canonical ones. */
class WordlistBlobTest {
    @Test
    fun `the words are sorted, each once, one per line, in UTF-8`() {
        val bytes = WordlistBlob.encode(listOf("dog", "émeu", "cat", "dog"))
        assertContentEquals("cat\ndog\némeu".encodeToByteArray(), bytes)
        assertEquals(listOf("cat", "dog", "émeu"), WordlistBlob.decodeOrNull(bytes))
    }

    @Test
    fun `the order of the words does not change the blob`() {
        assertContentEquals(
            WordlistBlob.encode(listOf("b", "a", "c")),
            WordlistBlob.encode(listOf("c", "b", "a")),
        )
    }

    @Test
    fun `no words are an empty blob`() {
        assertContentEquals(ByteArray(0), WordlistBlob.encode(emptyList()))
        assertEquals(emptyList(), WordlistBlob.decodeOrNull(ByteArray(0)))
    }

    @Test
    fun `a blob that is not canonical is refused`() {
        // Out of order, and a word twice.
        assertNull(WordlistBlob.decodeOrNull("dog\ncat".encodeToByteArray()))
        assertNull(WordlistBlob.decodeOrNull("cat\ncat".encodeToByteArray()))
        // A blank word, and a trailing line break.
        assertNull(WordlistBlob.decodeOrNull("cat\n \ndog".encodeToByteArray()))
        assertNull(WordlistBlob.decodeOrNull("cat\ndog\n".encodeToByteArray()))
        // A carriage return.
        assertNull(WordlistBlob.decodeOrNull("cat\r\ndog".encodeToByteArray()))
        // Not UTF-8.
        assertNull(WordlistBlob.decodeOrNull(byteArrayOf(0x63, 0xFF.toByte(), 0x74)))
        // A word longer than the import allows.
        val long = "a".repeat(WordlistBlob.MAX_WORD_LENGTH + 1)
        assertNull(WordlistBlob.decodeOrNull(long.encodeToByteArray()))
    }

    @Test
    fun `a blob holds at most so many words`() {
        // Zero-padded numbers sort as their values: each word once, in order.
        fun words(count: Int) = List(count) { it.toString().padStart(7, '0') }
        val most = WordlistBlob.encode(words(WordlistBlob.MAX_WORDS))
        assertEquals(WordlistBlob.MAX_WORDS, WordlistBlob.decodeOrNull(most)?.size)
        assertNull(WordlistBlob.decodeOrNull(WordlistBlob.encode(words(WordlistBlob.MAX_WORDS + 1))))
        // The line breaks are counted before a word is made: millions of tiny words cost nothing.
        assertNull(WordlistBlob.decodeOrNull("a\n".repeat(8 * 1024 * 1024).encodeToByteArray()))
    }

    @Test
    fun `a blob larger than the limit is refused`() {
        val bytes = ByteArray(AppLinkBlobs.MAX_BLOB_SIZE.toInt() + 1) { 'a'.code.toByte() }
        assertNull(WordlistBlob.decodeOrNull(bytes))
    }
}
