package com.artemchep.keyguard.nativecrypto.wasm

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JsCryptoArraysTest {
    @Test
    fun copiesAllByteValuesAndClearsIntermediateResponse() {
        val input = ByteArray(256) { it.toByte() }
        JsCryptoArrays().use { arrays ->
            val js = arrays.copy(input)
            assertContentEquals(input, js.copyAndClear())
            repeat(js.length) { assertEquals(0, js.intAt(it)) }
            assertContentEquals(ByteArray(256) { it.toByte() }, input)
        }
    }

    @Test
    fun clearsInputCopiesWhenAnOperationThrows() {
        val arrays = JsCryptoArrays()
        val input = arrays.copy(byteArrayOf(1, -1, 127, -128))
        assertFailsWith<IllegalStateException> {
            arrays.use { error("test failure") }
        }
        repeat(input.length) { assertEquals(0, input.intAt(it)) }
    }

    @Test
    fun refusesMismatchedOutputLengths() {
        JsCryptoArrays().use { arrays ->
            assertFailsWith<IllegalArgumentException> {
                arrays.allocate(2).copyTo(ByteArray(1))
            }
        }
    }
}
