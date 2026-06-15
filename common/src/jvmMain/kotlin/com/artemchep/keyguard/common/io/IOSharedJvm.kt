package com.artemchep.keyguard.common.io

import java.lang.ref.Reference
import java.lang.ref.SoftReference

internal actual fun <T : Any> refSoft(value: T): Ref<T> =
    RefJvm(SoftReference(value))

private class RefJvm<T : Any>(
    private val reference: Reference<T>,
) : Ref<T> {
    override fun get(): T? = reference.get()
}
