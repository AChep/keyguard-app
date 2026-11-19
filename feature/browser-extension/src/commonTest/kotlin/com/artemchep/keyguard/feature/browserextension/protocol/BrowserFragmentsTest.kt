package com.artemchep.keyguard.feature.browserextension.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class BrowserFragmentsTest {
    @Test
    fun `messages of every size survive the round trip`() {
        val max = BrowserFragments.MAX_FRAGMENT_DATA
        for (size in listOf(1, 2, max, max + 1, 2 * max + 1, BrowserFragments.MAX_MESSAGE_SIZE)) {
            val message = ByteArray(size) { (it * 31).toByte() }
            val fragments = BrowserFragments.split(message)
            assertEquals((size + max - 1) / max, fragments.size, "$size")
            // Every fragment but the last says that more follow.
            fragments.dropLast(1).forEach { assertEquals(1, it[0].toInt()) }
            assertEquals(0, fragments.last()[0].toInt())
            assertContentEquals(message, BrowserReassembler().feed(fragments))
        }
    }

    @Test
    fun `the largest message takes 17 fragments`() {
        assertEquals(17, BrowserFragments.split(ByteArray(BrowserFragments.MAX_MESSAGE_SIZE)).size)
    }

    @Test
    fun `a sender may split a message inside a UTF-8 sequence`() {
        // 27 bytes: two-byte letters and a four-byte emoji.
        val text = """{"id":1,"n":"Ключ🔑"}"""
        val message = text.encodeToByteArray()
        // Fragments of 1 to 3 bytes cut through every multi-byte sequence.
        for (maxData in 1..3) {
            val joined = BrowserReassembler().feed(BrowserFragments.split(message, maxData = maxData))
            assertEquals(text, joined.decodeToString())
            assertEquals(1L, BrowserMessages.parseRequest(joined).id)
        }
    }

    @Test
    fun `the reassembler starts over after each message`() {
        val reassembler = BrowserReassembler()
        assertNull(reassembler.add(byteArrayOf(1, 'a'.code.toByte())))
        assertContentEquals("ab".encodeToByteArray(), reassembler.add(byteArrayOf(0, 'b'.code.toByte())))
        assertContentEquals("c".encodeToByteArray(), reassembler.add(byteArrayOf(0, 'c'.code.toByte())))
    }

    @Test
    fun `a reserved bit ends the session`() {
        for (flags in listOf(0x02, 0x80, 0x03)) {
            assertFailsWith<BrowserProtocolException> { BrowserReassembler().add(byteArrayOf(flags.toByte(), 1)) }
        }
    }

    @Test
    fun `an empty fragment ends the session`() {
        assertFailsWith<BrowserProtocolException> { BrowserReassembler().add(byteArrayOf()) }
        assertFailsWith<BrowserProtocolException> { BrowserReassembler().add(byteArrayOf(0)) }
        assertFailsWith<BrowserProtocolException> { BrowserReassembler().add(byteArrayOf(1)) }
    }

    @Test
    fun `a 33rd fragment ends the session`() {
        val reassembler = BrowserReassembler()
        // 32 fragments, the last one included, make a message.
        repeat(BrowserFragments.MAX_FRAGMENTS - 1) { assertNull(reassembler.add(byteArrayOf(1, 7))) }
        assertContentEquals(ByteArray(BrowserFragments.MAX_FRAGMENTS) { 7 }, reassembler.add(byteArrayOf(0, 7)))
        repeat(BrowserFragments.MAX_FRAGMENTS) { assertNull(reassembler.add(byteArrayOf(1, 7))) }
        assertFailsWith<BrowserProtocolException> { reassembler.add(byteArrayOf(0, 7)) }
    }

    @Test
    fun `an oversized message ends the session`() {
        val reassembler = BrowserReassembler()
        val full = ByteArray(1 + BrowserFragments.MAX_FRAGMENT_DATA).also { it[0] = 1 }
        // 16 full fragments and a part of a 17th reach the limit exactly.
        repeat(16) { assertNull(reassembler.add(full)) }
        val rest = BrowserFragments.MAX_MESSAGE_SIZE - 16 * BrowserFragments.MAX_FRAGMENT_DATA
        assertNull(reassembler.add(ByteArray(1 + rest).also { it[0] = 1 }))
        assertFailsWith<BrowserProtocolException> { reassembler.add(byteArrayOf(0, 7)) }
    }

    @Test
    fun `an oversized fragment ends the session`() {
        val fragment = ByteArray(2 + BrowserFragments.MAX_FRAGMENT_DATA)
        assertFailsWith<BrowserProtocolException> { BrowserReassembler().add(fragment) }
    }

    @Test
    fun `the sender refuses what it may not send`() {
        assertFailsWith<IllegalArgumentException> { BrowserFragments.split(byteArrayOf()) }
        assertFailsWith<IllegalArgumentException> {
            BrowserFragments.split(ByteArray(BrowserFragments.MAX_MESSAGE_SIZE + 1))
        }
    }

    private fun BrowserReassembler.feed(fragments: List<ByteArray>): ByteArray {
        fragments.dropLast(1).forEach { assertNull(add(it)) }
        return checkNotNull(add(fragments.last()))
    }
}
