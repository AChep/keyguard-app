package com.artemchep.keyguard.common.util

operator fun Int.contains(other: Int) = (other and this) == other
