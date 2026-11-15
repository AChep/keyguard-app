@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@file:Suppress("UnusedParameter") // Parameters are consumed by compile-time js() bodies.

package com.artemchep.keyguard.nativecrypto.wasm

import kotlin.js.JsAny

/** Numeric typed arrays owned by the JS bridge, outside the Kotlin/Wasm GC heap. */
internal external interface JsCryptoArray : JsAny {
    val length: Int

    fun fill(value: Int): JsCryptoArray
}

internal fun JsCryptoArray.intAt(index: Int): Int = readElement(this, index)

internal fun JsCryptoArray.copyTo(output: ByteArray) {
    require(length == output.size)
    for (index in output.indices) output[index] = intAt(index).toByte()
}

internal fun JsCryptoArray.copyAndClear(): ByteArray = try {
    ByteArray(length) { intAt(it).toByte() }
} finally {
    fill(0)
}

/** Clears the extra JS copies introduced by the WasmGC-to-linear-memory boundary. */
internal class JsCryptoArrays : AutoCloseable {
    private val arrays = mutableListOf<JsCryptoArray>()

    fun allocate(size: Int): JsCryptoArray = newByteArray(size).also(arrays::add)

    fun copy(value: ByteArray): JsCryptoArray = allocate(value.size).also { array ->
        for (index in value.indices) writeElement(array, index, value[index].toInt())
    }

    override fun close() {
        arrays.forEach { it.fill(0) }
    }
}

private fun newByteArray(size: Int): JsCryptoArray = js("new Int8Array(size)")

private fun readElement(array: JsCryptoArray, index: Int): Int = js("array[index]")

private fun writeElement(array: JsCryptoArray, index: Int, value: Int): Unit = js("{ array[index] = value; }")
