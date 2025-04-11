package arrow.core

fun <A, C, B : C> Either<A, B>.widen(): Either<A, C> =
    this

fun <B, A : B> Iterable<A>.widen(): Iterable<B> =
    this

fun <K, B, A : B> Map<K, A>.widen(): Map<K, B> =
    this

fun <B, A : B> List<A>.widen(): List<B> =
    this
