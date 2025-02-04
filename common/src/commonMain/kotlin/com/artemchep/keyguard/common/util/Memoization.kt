package com.artemchep.keyguard.common.util

import arrow.core.continuations.AtomicRef
import arrow.core.continuations.loop

fun <R> (suspend () -> R).memoize(): suspend () -> R {
    val m = MemoizedHandler<suspend () -> R, MemoizeKey0<R>, R>(this@memoize)
    return { m.invoke(MemoizeKey0(0)) }
}

fun <P1, R> (suspend (P1) -> R).memoize(): suspend (P1) -> R {
    val m = MemoizedHandler<(suspend (P1) -> R), MemoizeKey1<P1, R>, R>(this@memoize)
    return { p1 -> m.invoke(MemoizeKey1(p1)) }
}

fun <P1, P2, R> (suspend (P1, P2) -> R).memoize(): suspend (P1, P2) -> R {
    val m = MemoizedHandler<(suspend (P1, P2) -> R), MemoizeKey2<P1, P2, R>, R>(this@memoize)
    return { p1: P1, p2: P2 -> m.invoke(MemoizeKey2(p1, p2)) }
}

fun <P1, P2, P3, R> (suspend (P1, P2, P3) -> R).memoize(): suspend (P1, P2, P3) -> R {
    val m =
        MemoizedHandler<(suspend (P1, P2, P3) -> R), MemoizeKey3<P1, P2, P3, R>, R>(this@memoize)
    return { p1: P1, p2: P2, p3: P3 -> m.invoke(MemoizeKey3(p1, p2, p3)) }
}

private interface MemoizedCall<in F, out R> {
    suspend fun invoke(f: F): R
}

private data class MemoizeKey0<R>(val p1: Byte) : MemoizedCall<suspend () -> R, R> {
    override suspend fun invoke(f: suspend () -> R): R = f()
}

private data class MemoizeKey1<out P1, R>(val p1: P1) : MemoizedCall<suspend (P1) -> R, R> {
    override suspend fun invoke(f: suspend (P1) -> R) = f(p1)
}

private data class MemoizeKey2<out P1, out P2, R>(val p1: P1, val p2: P2) :
    MemoizedCall<suspend (P1, P2) -> R, R> {
    override suspend fun invoke(f: suspend (P1, P2) -> R) = f(p1, p2)
}

private data class MemoizeKey3<out P1, out P2, out P3, R>(val p1: P1, val p2: P2, val p3: P3) :
    MemoizedCall<suspend (P1, P2, P3) -> R, R> {
    override suspend fun invoke(f: suspend (P1, P2, P3) -> R) = f(p1, p2, p3)
}

private class MemoizedHandler<F, in K : MemoizedCall<F, R>, out R>(val f: F) {
    private val cache = AtomicRef(emptyMap<K, R>())

    suspend fun invoke(k: K): R = when (k) {
        in cache.get() -> cache.get().getValue(k)
        else -> {
            val b = k.invoke(f)
            cache.loop { old ->
                when (k) {
                    in old ->
                        return@invoke old.getValue(k)

                    else -> {
                        if (cache.compareAndSet(old, old + Pair(k, b)))
                            return@invoke b
                    }
                }
            }
        }
    }
}
