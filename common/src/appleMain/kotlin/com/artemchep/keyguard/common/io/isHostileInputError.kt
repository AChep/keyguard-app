package com.artemchep.keyguard.common.io

/**
 * Kotlin/Native has no `StackOverflowError`: exhausting the stack traps in the
 * OS and kills the process, so only the heap side of the pair is representable
 * here.
 */
@PublishedApi
internal actual fun Throwable.isHostileInputError(): Boolean = this is OutOfMemoryError
