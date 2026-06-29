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

package app.keemobile.kotpass.cryptography.padding

import app.keemobile.kotpass.errors.CryptoError.InvalidCipherText

/**
 * PKCS#7 is described in RFC 5652.
 *
 * Padding is in whole bytes. The value of each added byte is the number of
 * bytes that are added, i.e. N bytes, each of value N are added. The number
 * of bytes added will depend on the block boundary to which the message
 * needs to be extended.
 *
 * **See Also:** [RFC 5652](https://tools.ietf.org/html/rfc5652#section-6.3)
 */
internal data object PKCS7Padding : BlockCipherPadding {
    override fun addPadding(input: ByteArray, offset: Int): Int {
        val code = (input.size - offset).toByte()
        input.fill(code, offset)

        return code.toInt()
    }

    override fun padCount(input: ByteArray): Int {
        val countAsByte = input[input.size - 1]
        val count = countAsByte.toInt() and 0xFF
        val position = input.size - count

        var failed = (position or (count - 1)) shr 31

        for (i in input.indices) {
            failed = failed or (
                (input[i].toInt() xor countAsByte.toInt())
                and ((i - position) shr 31).inv()
            )
        }

        if (failed != 0) {
            throw InvalidCipherText("Pad block is corrupted")
        }

        return count
    }
}
