package com.artemchep.keyguard.common.io

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class IOMonadTest {
    @Test
    fun `parallel rejects zero parallelism with IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            listOf(io(1))
                .parallel(parallelism = 0)
                .bindBlocking()
        }
    }

    @Test
    fun `parallel returns all values for valid parallelism`() {
        val values = listOf(
            io(1),
            io(2),
            io(3),
            io(4),
        ).parallel(parallelism = 2)
            .bindBlocking()

        assertEquals(listOf(1, 2, 3, 4), values)
    }
}
