package com.artemchep.keyguard.util.dns.internal

import com.artemchep.keyguard.util.dns.DnsFormatException
import com.artemchep.keyguard.util.dns.DnsResponseCodeException
import com.artemchep.keyguard.util.dns.DnsTruncatedException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val FLAGS_RESPONSE = 0x8180
private const val FLAGS_NXDOMAIN = 0x8183
private const val FLAGS_SERVFAIL = 0x8182
private const val FLAGS_REFUSED = 0x8185
private const val FLAGS_TRUNCATED = 0x8380
private const val FLAGS_QUERY = 0x0100
private const val FLAGS_OPCODE_IQUERY = 0x8800

private const val TYPE_A = 1
private const val TYPE_CNAME = 5
private const val TYPE_TXT = 16
private const val CLASS_IN = 1
private const val CLASS_CHAOS = 3

private const val QUESTION_OFFSET = 12
private const val QNAME = "_bitwarden.example.com"
private const val SERVER_URL = "https://vault.example.com"

class DnsMessageParserTest {
    @Test
    fun `parses a single txt record with a compressed owner name`() {
        val message = DnsMessageBuilder()
            .header(FLAGS_RESPONSE, questionCount = 1, answerCount = 1)
            .question(QNAME)
            .answer({ pointer(QUESTION_OFFSET) }, TYPE_TXT, CLASS_IN, txt(SERVER_URL))
            .build()

        assertEquals(listOf(SERVER_URL), DnsMessageParser.parseTxtMessage(message))
    }

    @Test
    fun `concatenates the character strings of one record`() {
        val message = DnsMessageBuilder()
            .header(FLAGS_RESPONSE, questionCount = 1, answerCount = 1)
            .question(QNAME)
            .answer({ pointer(QUESTION_OFFSET) }, TYPE_TXT, CLASS_IN, txt("https://vault.", "example.com"))
            .build()

        assertEquals(listOf(SERVER_URL), DnsMessageParser.parseTxtMessage(message))
    }

    @Test
    fun `returns multiple records in wire order`() {
        val message = DnsMessageBuilder()
            .header(FLAGS_RESPONSE, questionCount = 1, answerCount = 2)
            .question(QNAME)
            .answer({ pointer(QUESTION_OFFSET) }, TYPE_TXT, CLASS_IN, txt("first"))
            .answer({ name(QNAME) }, TYPE_TXT, CLASS_IN, txt("second"))
            .build()

        assertEquals(listOf("first", "second"), DnsMessageParser.parseTxtMessage(message))
    }

    @Test
    fun `skips a cname and reads the txt record behind it`() {
        val builder = DnsMessageBuilder()
            .header(FLAGS_RESPONSE, questionCount = 1, answerCount = 2)
            .question(QNAME)
        val cnameRdata = DnsMessageBuilder().name("txt.example.net").build()
        // The CNAME RDATA starts after the owner pointer (2) and the fixed fields (10).
        val cnameTargetOffset = builder.size + 2 + 10
        builder.answer({ pointer(QUESTION_OFFSET) }, TYPE_CNAME, CLASS_IN, cnameRdata)
        builder.answer({ pointer(cnameTargetOffset) }, TYPE_TXT, CLASS_IN, txt(SERVER_URL))

        assertEquals(listOf(SERVER_URL), DnsMessageParser.parseTxtMessage(builder.build()))
    }

    @Test
    fun `returns an empty list for a name error`() {
        val message = DnsMessageBuilder()
            .header(FLAGS_NXDOMAIN, questionCount = 1, answerCount = 0)
            .question(QNAME)
            .build()

        assertEquals(emptyList(), DnsMessageParser.parseTxtMessage(message))
    }

    @Test
    fun `returns an empty list when there is no data`() {
        val message = DnsMessageBuilder()
            .header(FLAGS_RESPONSE, questionCount = 1, answerCount = 0)
            .question(QNAME)
            .build()

        assertEquals(emptyList(), DnsMessageParser.parseTxtMessage(message))
    }

    @Test
    fun `throws for an error response code`() {
        val servfail = DnsMessageBuilder()
            .header(FLAGS_SERVFAIL, questionCount = 0, answerCount = 0)
            .build()
        val refused = DnsMessageBuilder()
            .header(FLAGS_REFUSED, questionCount = 0, answerCount = 0)
            .build()

        assertEquals(2, assertFailsWith<DnsResponseCodeException> { DnsMessageParser.parseTxtMessage(servfail) }.rcode)
        assertEquals(5, assertFailsWith<DnsResponseCodeException> { DnsMessageParser.parseTxtMessage(refused) }.rcode)
    }

    @Test
    fun `throws for a truncated response`() {
        val message = DnsMessageBuilder()
            .header(FLAGS_TRUNCATED, questionCount = 0, answerCount = 0)
            .build()

        assertFailsWith<DnsTruncatedException> { DnsMessageParser.parseTxtMessage(message) }
    }

    @Test
    fun `rejects a message that is not a response`() {
        val message = DnsMessageBuilder()
            .header(FLAGS_QUERY, questionCount = 0, answerCount = 0)
            .build()

        assertFailsWith<DnsFormatException> { DnsMessageParser.parseTxtMessage(message) }
    }

    @Test
    fun `rejects a response with an unexpected opcode`() {
        val message = DnsMessageBuilder()
            .header(FLAGS_OPCODE_IQUERY, questionCount = 0, answerCount = 0)
            .build()

        assertFailsWith<DnsFormatException> { DnsMessageParser.parseTxtMessage(message) }
    }

    @Test
    fun `rejects a name pointer that points at itself`() {
        val message = DnsMessageBuilder()
            .header(FLAGS_RESPONSE, questionCount = 1, answerCount = 0)
            .pointer(QUESTION_OFFSET)
            .u16(TYPE_TXT)
            .u16(CLASS_IN)
            .build()

        assertFailsWith<DnsFormatException> { DnsMessageParser.parseTxtMessage(message) }
    }

    @Test
    fun `rejects a name pointer that points forwards`() {
        val message = DnsMessageBuilder()
            .header(FLAGS_RESPONSE, questionCount = 1, answerCount = 0)
            .pointer(QUESTION_OFFSET + 4)
            .u16(TYPE_TXT)
            .u16(CLASS_IN)
            .name("a")
            .build()

        assertFailsWith<DnsFormatException> { DnsMessageParser.parseTxtMessage(message) }
    }

    @Test
    fun `rejects a pointer chain that is too long`() {
        val builder = DnsMessageBuilder()
            .header(FLAGS_RESPONSE, questionCount = 1, answerCount = 2)
            .question("a")
        // Build a chain of pointers inside an A record's RDATA. Every pointer
        // points at the one before it, and the first one at the root label of
        // the question name, so the chain is valid apart from its length.
        val chain = DnsMessageBuilder()
        val chainOffset = builder.size + 2 + 10
        chain.pointer(QUESTION_OFFSET + 2)
        repeat(70) { index ->
            chain.pointer(chainOffset + index * 2)
        }
        val lastPointerOffset = chainOffset + chain.size - 2
        builder.answer({ pointer(QUESTION_OFFSET) }, TYPE_A, CLASS_IN, chain.build())
        builder.answer({ pointer(lastPointerOffset) }, TYPE_TXT, CLASS_IN, txt(SERVER_URL))

        assertFailsWith<DnsFormatException> { DnsMessageParser.parseTxtMessage(builder.build()) }
    }

    @Test
    fun `rejects a name that is too long`() {
        val label = "a".repeat(63)
        val longName = List(5) { label }.joinToString(".")
        val message = DnsMessageBuilder()
            .header(FLAGS_RESPONSE, questionCount = 1, answerCount = 0)
            .question(longName)
            .build()

        assertFailsWith<DnsFormatException> { DnsMessageParser.parseTxtMessage(message) }
    }

    @Test
    fun `rejects unsupported label types`() {
        val extendedLabel = DnsMessageBuilder()
            .header(FLAGS_RESPONSE, questionCount = 1, answerCount = 0)
            .u8(0x40)
            .u8(0x00)
            .u16(TYPE_TXT)
            .u16(CLASS_IN)
            .build()
        val reservedLabel = DnsMessageBuilder()
            .header(FLAGS_RESPONSE, questionCount = 1, answerCount = 0)
            .u8(0x80)
            .u8(0x00)
            .u16(TYPE_TXT)
            .u16(CLASS_IN)
            .build()

        assertFailsWith<DnsFormatException> { DnsMessageParser.parseTxtMessage(extendedLabel) }
        assertFailsWith<DnsFormatException> { DnsMessageParser.parseTxtMessage(reservedLabel) }
    }

    @Test
    fun `rejects rdata that runs past the message`() {
        val message = DnsMessageBuilder()
            .header(FLAGS_RESPONSE, questionCount = 1, answerCount = 1)
            .question(QNAME)
            .pointer(QUESTION_OFFSET)
            .u16(TYPE_TXT)
            .u16(CLASS_IN)
            .u32(3600)
            .u16(100)
            .raw(3, 'a'.code, 'b'.code, 'c'.code)
            .build()

        assertFailsWith<DnsFormatException> { DnsMessageParser.parseTxtMessage(message) }
    }

    @Test
    fun `rejects a character string that runs past its rdata`() {
        val message = DnsMessageBuilder()
            .header(FLAGS_RESPONSE, questionCount = 1, answerCount = 1)
            .question(QNAME)
            .pointer(QUESTION_OFFSET)
            .u16(TYPE_TXT)
            .u16(CLASS_IN)
            .u32(3600)
            .u16(4)
            .raw(9, 'a'.code, 'b'.code, 'c'.code)
            .build()

        assertFailsWith<DnsFormatException> { DnsMessageParser.parseTxtMessage(message) }
    }

    @Test
    fun `rejects a message shorter than the header`() {
        val message = ByteArray(11)

        assertFailsWith<DnsFormatException> { DnsMessageParser.parseTxtMessage(message) }
    }

    @Test
    fun `ignores records of another class or type`() {
        val message = DnsMessageBuilder()
            .header(FLAGS_RESPONSE, questionCount = 1, answerCount = 3)
            .question(QNAME)
            .answer({ pointer(QUESTION_OFFSET) }, TYPE_TXT, CLASS_CHAOS, txt("chaos"))
            .answer({ pointer(QUESTION_OFFSET) }, TYPE_A, CLASS_IN, byteArrayOf(10, 0, 0, 1))
            .answer({ pointer(QUESTION_OFFSET) }, TYPE_TXT, CLASS_IN, txt(SERVER_URL))
            .build()

        assertEquals(listOf(SERVER_URL), DnsMessageParser.parseTxtMessage(message))
    }

    @Test
    fun `caps the number of returned records`() {
        val builder = DnsMessageBuilder()
            .header(FLAGS_RESPONSE, questionCount = 1, answerCount = 70)
            .question(QNAME)
        repeat(70) { index ->
            builder.answer({ pointer(QUESTION_OFFSET) }, TYPE_TXT, CLASS_IN, txt("record $index"))
        }

        assertEquals(64, DnsMessageParser.parseTxtMessage(builder.build()).size)
    }

    @Test
    fun `parses rdata on its own`() {
        assertEquals(SERVER_URL, DnsMessageParser.parseTxtRdata(txt(SERVER_URL)))
        assertEquals("ab", DnsMessageParser.parseTxtRdata(txt("a", "b")))
        assertEquals("", DnsMessageParser.parseTxtRdata(ByteArray(0)))
    }

    @Test
    fun `replaces invalid utf8 instead of failing`() {
        val rdata = byteArrayOf(2, 0xFF.toByte(), 0xFE.toByte())

        val value = DnsMessageParser.parseTxtRdata(rdata)

        assertTrue(value.isNotEmpty())
        assertTrue(value.all { it == '�' })
    }
}

private fun txt(vararg strings: String): ByteArray {
    val builder = DnsMessageBuilder()
    strings.forEach { string ->
        val bytes = string.encodeToByteArray()
        builder.u8(bytes.size)
        builder.bytes(bytes)
    }
    return builder.build()
}

private class DnsMessageBuilder {
    private val data = mutableListOf<Byte>()

    val size: Int
        get() = data.size

    fun u8(value: Int) = apply { data += value.toByte() }

    fun u16(value: Int) = apply {
        u8(value ushr 8)
        u8(value)
    }

    fun u32(value: Int) = apply {
        u16(value ushr 16)
        u16(value)
    }

    fun raw(vararg values: Int) = apply { values.forEach { u8(it) } }

    fun bytes(values: ByteArray) = apply { values.forEach { data += it } }

    fun name(name: String) = apply {
        name.split('.').forEach { label ->
            val bytes = label.encodeToByteArray()
            u8(bytes.size)
            bytes(bytes)
        }
        u8(0)
    }

    fun pointer(offset: Int) = apply { u16(0xC000 or offset) }

    fun header(flags: Int, questionCount: Int, answerCount: Int) = apply {
        u16(0x2A2A)
        u16(flags)
        u16(questionCount)
        u16(answerCount)
        u16(0)
        u16(0)
    }

    fun question(name: String) = apply {
        name(name)
        u16(TYPE_TXT)
        u16(CLASS_IN)
    }

    fun answer(
        owner: DnsMessageBuilder.() -> Unit,
        type: Int,
        rrClass: Int,
        rdata: ByteArray,
    ) = apply {
        owner()
        u16(type)
        u16(rrClass)
        u32(3600)
        u16(rdata.size)
        bytes(rdata)
    }

    fun build(): ByteArray = data.toByteArray()
}
