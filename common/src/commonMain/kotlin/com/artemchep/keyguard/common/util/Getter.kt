package arrow.optics

typealias Getter<S, A> = Lens<S, A>

/**
 * Creates a [Lens] that only has a getter and crashes if
 * you try to set a value.
 */
fun <T, R> Getter(block: (T) -> R): Lens<T, R> = Lens(
    get = { t: T -> block(t) },
    set = { _, _: R ->
        throw UnsupportedOperationException("Setter is not supported for Getter!")
    },
)
