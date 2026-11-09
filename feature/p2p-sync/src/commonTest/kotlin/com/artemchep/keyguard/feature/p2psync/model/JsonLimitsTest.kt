package com.artemchep.keyguard.feature.p2psync.model

import com.artemchep.keyguard.feature.p2psync.session.SyncSessionConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JsonLimitsTest {
    private fun JsonLimits.admits(text: String) = admits(text.encodeToByteArray())

    @Test
    fun `nodes are the brackets, commas and colons outside strings`() {
        // { : [ , , : are six nodes, two levels deep.
        val text = """{"a":[1,2],"b":3}"""
        assertTrue(JsonLimits(maxDepth = 2, maxNodes = 6).admits(text))
        assertFalse(JsonLimits(maxDepth = 2, maxNodes = 5).admits(text))
        assertFalse(JsonLimits(maxDepth = 1, maxNodes = 6).admits(text))
        // Closing brackets and scalars are not counted.
        assertTrue(JsonLimits(maxDepth = 1, maxNodes = 1).admits("[0]"))
        assertFalse(JsonLimits(maxDepth = 1, maxNodes = 1).admits("[0,0]"))
    }

    @Test
    fun `strings hide brackets and escaped quotes`() {
        val limits = JsonLimits(maxDepth = 1, maxNodes = 2)
        assertTrue(limits.admits("""{"a":"[{,:\"]}"}"""))
        // An escaped backslash ends the escape: the quote after it closes the string.
        assertTrue(limits.admits("""["\\",1]"""))
        assertFalse(limits.admits("""["\\",1,2]"""))
        // Non-ASCII text is never mistaken for a bracket.
        assertTrue(limits.admits("""["ж,{","😀"]"""))
    }

    @Test
    fun `a compressible envelope of tiny values is past the limits of an envelope`() {
        // 16 KiB of gzip inflate to this; parsed, it would take about 40 times its size.
        val bomb = """{"v":1,"x":[""" + List(EnvelopeCodec.MAX_NODES) { "0" }.joinToString(",") + "]}"
        assertTrue(bomb.length < SyncSessionConfig.MAX_ENVELOPE_SIZE)
        assertFalse(EnvelopeCodec.limits.admits(bomb))
        assertEquals(EnvelopeDecoding.TooLarge, EnvelopeCodec.decode(bomb.encodeToByteArray()) { it })
    }
}
