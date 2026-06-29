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

private const val DefaultRounds = 20

internal class ChaChaEngine(rounds: Int = DefaultRounds) : Salsa20Engine(rounds) {
    override val algorithmName = "ChaCha"

    override fun advanceCounter(diff: Long) {
        val hi = (diff ushr 32).toInt()
        val lo = diff.toInt()
        if (hi > 0) {
            engineState[13] += hi
        }
        val oldState = engineState[12]
        engineState[12] += lo
        if (oldState != 0 && engineState[12] < oldState) {
            engineState[13]++
        }
    }

    override fun advanceCounter() {
        if (++engineState[12] == 0) {
            ++engineState[13]
        }
    }

    override fun retreatCounter(diff: Long) {
        val hi = (diff ushr 32).toInt()
        val lo = diff.toInt()
        if (hi != 0) {
            if (engineState[13] and 0xffffffffL.toInt() >= hi and 0xffffffffL.toInt()) {
                engineState[13] -= hi
            } else {
                throw IllegalStateException("Attempt to reduce counter past zero.")
            }
        }
        if (engineState[12] and 0xffffffffL.toInt() >= lo and 0xffffffffL.toInt()) {
            engineState[12] -= lo
        } else {
            if (engineState[13] != 0) {
                --engineState[13]
                engineState[12] -= lo
            } else {
                throw IllegalStateException("Attempt to reduce counter past zero.")
            }
        }
    }

    override fun retreatCounter() {
        check(engineState[12] != 0 || engineState[13] != 0) {
            "Attempt to reduce counter past zero."
        }
        if (--engineState[12] == -1) {
            --engineState[13]
        }
    }

    override fun getCounter(): Long {
        return engineState[13].toLong() shl 32 or ((engineState[12] and 0xffffffffL.toInt()).toLong())
    }

    override fun resetCounter() {
        engineState[13] = 0
        engineState[12] = engineState[13]
    }

    override fun setKey(key: ByteArray?, iv: ByteArray) {
        if (key != null) {
            require(key.size == 16 || key.size == 32) {
                "$algorithmName requires 128 bit or 256 bit key"
            }
            packTauOrSigma(key.size, engineState)

            // Key
            littleEndianToInt(key, 0, engineState, 4, 4)
            littleEndianToInt(key, key.size - 16, engineState, 8, 4)
        }

        // IV
        littleEndianToInt(iv, 0, engineState, 14, 2)
    }

    override fun generateKeyStream(output: ByteArray) {
        chachaCore(rounds, engineState, x)
        intToLittleEndian(x, output, 0)
    }
}
