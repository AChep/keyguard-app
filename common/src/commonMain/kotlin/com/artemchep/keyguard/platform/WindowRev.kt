package com.artemchep.keyguard.platform

import kotlin.jvm.JvmInline
import kotlin.random.Random

@JvmInline
value class WindowRev(
    val value: Long,
) {
    companion object {
        fun generateWindowRev(): WindowRev {
            val value = Random.nextLong()
            return WindowRev(value)
        }
    }
}
