/**
 * The Bouncy Castle License
 *
 * Copyright (c) 2000-2021 The Legion Of The Bouncy Castle Inc. (https://www.bouncycastle.org)
 * <p>
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software
 * and associated documentation files (the "Software"), to deal in the Software without restriction,
 * including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense,
 * and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 * <p>
 * The above copyright notice and this permission notice shall be included in all copies or substantial
 * portions of the Software.
 * <p>
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
 * INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR
 * PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
 * LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 * DEALINGS IN THE SOFTWARE.
 */

package app.keemobile.kotpass.cryptography.engines

import app.keemobile.kotpass.cryptography.intToLittleEndian
import app.keemobile.kotpass.cryptography.littleEndianToInt

/**
 * Implementation of Daniel J. Bernstein's ChaCha stream cipher.
 */

internal class ChaCha7539Engine : Salsa20Engine() {
    override val algorithmName = "ChaCha7539"

    override fun advanceCounter(diff: Long) {
        val hi = (diff ushr 32).toInt()
        val lo = diff.toInt()
        check(hi <= 0) {
            "Attempt to increase counter past 2^32."
        }

        val oldState = engineState[12]
        engineState[12] += lo
        check(!(oldState != 0 && engineState[12] < oldState)) {
            "Attempt to increase counter past 2^32."
        }
    }

    override fun advanceCounter() {
        check(++engineState[12] != 0) { "Attempt to increase counter past 2^32." }
    }

    override fun retreatCounter(diff: Long) {
        val hi = (diff ushr 32).toInt()
        val lo = diff.toInt()
        check(hi == 0) { "Attempt to reduce counter past zero." }

        if (engineState[12] and 0xffffffffL.toInt() >= lo and 0xffffffffL.toInt()) {
            engineState[12] -= lo
        } else {
            throw IllegalStateException("Attempt to reduce counter past zero.")
        }
    }

    override fun retreatCounter() {
        check(engineState[12] != 0) { "Attempt to reduce counter past zero." }
        --engineState[12]
    }

    override fun getCounter(): Long {
        return (engineState[12] and 0xffffffffL.toInt()).toLong()
    }

    override fun resetCounter() {
        engineState[12] = 0
    }

    override fun setKey(key: ByteArray?, iv: ByteArray) {
        if (key != null) {
            require(key.size == 32) {
                "$algorithmName requires 256 bit key"
            }
            packTauOrSigma(key.size, engineState)

            // Key
            littleEndianToInt(key, 0, engineState, 4, 8)
        }

        // IV
        littleEndianToInt(iv, 0, engineState, 13, 3)
    }

    override fun generateKeyStream(output: ByteArray) {
        chachaCore(rounds, engineState, x)
        intToLittleEndian(x, output, 0)
    }
}
