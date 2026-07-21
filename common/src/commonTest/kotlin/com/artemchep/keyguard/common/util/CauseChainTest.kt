package com.artemchep.keyguard.common.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class CauseChainTest {
    @Test
    fun `deeply wrapped cause is retained`() {
        val leaf = IllegalStateException("leaf")
        var root: Throwable = leaf
        repeat(32) { index ->
            root = IllegalStateException("wrapper-$index", root)
        }

        val causes = root.causeChain()

        assertEquals(33, causes.size)
        assertSame(root, causes.first())
        assertSame(leaf, causes.last())
    }
}
