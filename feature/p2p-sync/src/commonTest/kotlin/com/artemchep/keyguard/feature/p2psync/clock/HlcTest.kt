package com.artemchep.keyguard.feature.p2psync.clock

import com.artemchep.keyguard.feature.p2psync.P2pJson
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HlcTest {
    private class ManualTime(var now: Long)

    private fun clock(
        time: ManualTime,
        node: String = "a",
        persisted: MutableList<Hlc> = mutableListOf(),
        restored: Hlc? = null,
    ) =
        HlcClock(node = node, physicalNow = { time.now }, persist = { persisted += it }, restored = restored)

    @Test
    fun `stamps order by wall then counter then node`() {
        val stamps = listOf(
            Hlc(2, 0, "a"),
            Hlc(1, 5, "b"),
            Hlc(1, 5, "a"),
            Hlc(1, 0, "z"),
        )
        assertEquals(stamps.reversed(), stamps.sorted())
        assertTrue(Hlc.MIN < Hlc(0, 0, ""))
    }

    @Test
    fun `stamps are strictly monotonic even when the wall clock goes backwards`() {
        val seed = 11
        val random = Random(seed)
        val time = ManualTime(1_000)
        val clock = clock(time)
        var last = Hlc.MIN
        repeat(1_000) {
            time.now += random.nextLong(-50, 50)
            val next = clock.now()
            assertTrue(next > last, "seed=$seed: $next must follow $last")
            last = next
        }
    }

    @Test
    fun `events in one millisecond count up`() {
        val time = ManualTime(5_000)
        val clock = clock(time)
        assertEquals(Hlc(5_000, 0, "a"), clock.now())
        assertEquals(Hlc(5_000, 1, "a"), clock.now())
        time.now = 4_000
        assertEquals(Hlc(5_000, 2, "a"), clock.now())
        time.now = 6_000
        assertEquals(Hlc(6_000, 0, "a"), clock.now())
    }

    @Test
    fun `a full counter rolls over into the next millisecond`() {
        val time = ManualTime(1_000)
        val clock = clock(time, restored = Hlc(1_000, Int.MAX_VALUE, "a"))
        assertEquals(Hlc(1_001, 0, "a"), clock.now())
    }

    @Test
    fun `receive adopts a remote stamp within the allowed drift`() {
        val time = ManualTime(10_000)
        val clock = clock(time)
        val remote = Hlc(10_000 + 60_000, 7, "b")
        val stamp = clock.receive(remote)
        assertEquals(Hlc(70_000, 8, "a"), stamp)
        assertTrue(clock.now() > remote)
    }

    @Test
    fun `receive never adopts a stamp more than five minutes ahead`() {
        val time = ManualTime(10_000)
        val clock = clock(time)
        val stamp = clock.receive(Hlc(10_000 + HlcClock.MAX_FUTURE_DRIFT_MS + 1, 0, "b"))
        assertEquals(Hlc(10_000, 0, "a"), stamp)
    }

    @Test
    fun `nowAfter adopts a floor within the allowed drift`() {
        val time = ManualTime(10_000)
        val clock = clock(time)
        val floor = Hlc(10_000 + 60_000, 3, "b")
        assertEquals(Hlc(70_000, 4, "a"), clock.nowAfter(floor))
        assertTrue(clock.now() > floor)
    }

    @Test
    fun `nowAfter never moves the clock to a floor far ahead`() {
        val time = ManualTime(10_000)
        val clock = clock(time)
        // A roster record stamped a day ahead, by a device whose clock was wrong.
        val floor = Hlc(10_000 + 24L * 60L * 60L * 1_000L, 3, "b")
        assertEquals(Hlc(floor.wall, 4, "a"), clock.nowAfter(floor))
        // The stamps of later entries follow the physical clock, so peers take them.
        assertEquals(10_000L, clock.now().wall)
    }

    @Test
    fun `every stamp is persisted and a restored clock never repeats one`() {
        val time = ManualTime(1_000)
        val persisted = mutableListOf<Hlc>()
        val first = clock(time, persisted = persisted)
        val issued = List(5) { first.now() }
        assertEquals(issued, persisted)
        val restored = clock(time, restored = persisted.last())
        assertTrue(restored.now() > issued.last())
    }

    @Test
    fun `a stamp is written as wall counter and node`() {
        val stamp = Hlc(1_700_000_000_000, 3, "0b6f:node")
        val json = P2pJson.encodeToString(Hlc.serializer(), stamp)
        assertEquals("\"1700000000000:3:0b6f:node\"", json)
        assertEquals(stamp, P2pJson.decodeFromString(Hlc.serializer(), json))
        assertNull(Hlc.parseOrNull("1:x:node"))
        assertNull(Hlc.parseOrNull("12"))
    }
}
