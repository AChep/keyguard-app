package com.artemchep.keyguard.common.util

import kotlin.test.Test
import kotlin.test.assertEquals

class UniqueKeyBuilderTest {
    @Test
    fun `unique keys are returned unchanged`() {
        val builder = UniqueKeyBuilder()

        assertEquals("alpha", builder.build("alpha"))
        assertEquals("beta", builder.build("beta"))
    }

    @Test
    fun `duplicate keys receive independent suffixes`() {
        val builder = UniqueKeyBuilder()

        assertEquals(
            listOf(
                "client",
                "other",
                "client#1",
                "other#1",
                "client#2",
            ),
            listOf(
                "client",
                "other",
                "client",
                "other",
                "client",
            ).map(builder::build),
        )
    }

    @Test
    fun `custom formatting is supported`() {
        val builder = UniqueKeyBuilder { key, occurrence ->
            "$key:${occurrence + 1}"
        }

        assertEquals("word:1", builder.build("word"))
        assertEquals("word:2", builder.build("word"))
    }

    @Test
    fun `generated keys cannot collide with another base key`() {
        val builder = UniqueKeyBuilder()

        assertEquals(
            listOf(
                "a",
                "a#1",
                "a#1#1",
            ),
            listOf("a", "a", "a#1").map(builder::build),
        )
    }
}
