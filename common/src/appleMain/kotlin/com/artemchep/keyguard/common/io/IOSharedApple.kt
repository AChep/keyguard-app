package com.artemchep.keyguard.common.io

internal actual fun <T : Any> refSoft(value: T): Ref<T> =
    RefStrong(value)
