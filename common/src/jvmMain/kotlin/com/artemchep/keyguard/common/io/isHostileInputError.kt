package com.artemchep.keyguard.common.io

@PublishedApi
internal actual fun Throwable.isHostileInputError(): Boolean =
    this is StackOverflowError || this is OutOfMemoryError
