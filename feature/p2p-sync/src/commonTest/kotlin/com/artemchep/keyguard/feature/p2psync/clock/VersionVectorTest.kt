package com.artemchep.keyguard.feature.p2psync.clock

import com.artemchep.keyguard.feature.p2psync.P2pJson
import kotlinx.serialization.SerializationException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VersionVectorTest {
    private fun stamp(node: String, wall: Long) = Hlc(wall, 0, node)

    private fun vv(vararg stamps: Pair<String, Long>) =
        VersionVector.of(stamps.map { (node, wall) -> stamp(node, wall) })

    @Test
    fun `a missing writer has no event`() {
        assertNull(vv()["a"])
        assertTrue(vv().isEmpty())
        assertTrue(vv("a" to 1).covers(vv()))
        assertFalse(vv().covers(vv("a" to 1)))
    }

    @Test
    fun `a newer vector covers an older one and not the other way round`() {
        val older = vv("a" to 1)
        val newer = vv("a" to 2, "b" to 1)
        assertTrue(newer.covers(older))
        assertFalse(older.covers(newer))
        assertTrue(newer.covers(newer))
    }

    @Test
    fun `concurrent vectors cover neither side`() {
        val a = vv("a" to 2, "b" to 1)
        val b = vv("a" to 1, "b" to 2)
        assertFalse(a.covers(b))
        assertFalse(b.covers(a))
        assertTrue(a.witness(stamp("a", 3)).covers(a))
    }

    @Test
    fun `join is the per-writer latest stamp`() {
        val a = vv("a" to 2, "b" to 1)
        val b = vv("b" to 3, "c" to 1)
        assertEquals(vv("a" to 2, "b" to 3, "c" to 1), a.join(b))
        assertTrue(a.join(b).covers(a))
        assertTrue(a.join(b).covers(b))
    }

    @Test
    fun `join is commutative associative and idempotent`() {
        val seed = 5
        val random = Random(seed)
        fun randomVector() = VersionVector.of(
            listOf("a", "b", "c", "d").filter { random.nextBoolean() }.map { stamp(it, random.nextLong(1, 5)) },
        )
        repeat(200) {
            val (a, b, c) = Triple(randomVector(), randomVector(), randomVector())
            assertEquals(a.join(b), b.join(a), "seed=$seed")
            assertEquals(a.join(b).join(c), a.join(b.join(c)), "seed=$seed")
            assertEquals(a, a.join(a), "seed=$seed")
        }
    }

    @Test
    fun `witness keeps the latest stamp of the node`() {
        assertEquals(vv("a" to 1), vv().witness(stamp("a", 1)))
        assertEquals(vv("a" to 3, "b" to 1), vv("a" to 2, "b" to 1).witness(stamp("a", 3)))
        assertEquals(vv("a" to 2), vv("a" to 2).witness(stamp("a", 1)))
        assertFalse(vv("a" to 1).covers(vv("a" to 1).witness(stamp("b", 1))))
    }

    @Test
    fun `a stamp must be of its writer`() {
        assertFailsWith<IllegalArgumentException> { VersionVector(mapOf("a" to stamp("b", 1))) }
    }

    @Test
    fun `a vector is written as the stamps sorted by writer`() {
        val vector = vv("b" to 1, "a" to 2)
        val json = P2pJson.encodeToString(VersionVector.serializer(), vector)
        assertEquals("""["2:0:a","1:0:b"]""", json)
        assertEquals(vector, P2pJson.decodeFromString(VersionVector.serializer(), json))
        assertFailsWith<SerializationException> {
            P2pJson.decodeFromString(VersionVector.serializer(), """["2:0:a","1:0:a"]""")
        }
    }
}
