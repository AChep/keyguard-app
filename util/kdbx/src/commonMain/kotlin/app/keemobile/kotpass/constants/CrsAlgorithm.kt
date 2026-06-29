package app.keemobile.kotpass.constants

/**
 * Specifies the stream cipher for in-memory protection of sensitive data.
 */
enum class CrsAlgorithm {
    /** Unsupported. */
    None,

    /** Legacy ArcFour (RC4) variant. Unsupported. */
    ArcFourVariant,

    /** Salsa20 stream cipher. */
    Salsa20,

    /** ChaCha20 stream cipher. */
    ChaCha20
}
