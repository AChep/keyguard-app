package com.artemchep.keyguard.common.io

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class IOCombineTest {
    @Test
    fun `combine rejects zero bucket with IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            listOf(io(1))
                .combine(bucket = 0)
                .bindBlocking()
        }
    }

    @Test
    fun `combine returns all values for valid bucket`() {
        val values = listOf(
            io(1),
            io(2),
            io(3),
            io(4),
        ).combine(bucket = 2)
            .bindBlocking()

        assertEquals(listOf(1, 2, 3, 4), values)
    }
}
