package com.artemchep.keyguard.common.util

val ByteArray.int: Int
    get() {
        require(size == 4)
        return this
            .foldIndexed(0.toUInt()) { index, y, x ->
                // We need to convert byte to unsigned byte first, otherwise
                // the sign bit will plague the rest of the integer.
                val xUInt = x.toUByte().toUInt()
                val yUIntPatch = xUInt shl (size - index - 1).times(8)
                y or yUIntPatch
            }
            .toInt()
    }
