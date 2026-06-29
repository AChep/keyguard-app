package app.keemobile.kotpass.constants

internal enum class HeaderFieldId {
    /** Marks the end of the header fields section. */
    EndOfHeader,

    /** Legacy comment field — deprecated and no longer used. */
    Comment,

    /** Identifier for the encryption cipher used (e.g. AES, ChaCha20). */
    CipherId,

    /** Compression algorithm identifier. */
    Compression,

    /** Random seed used for master key derivation (32 bytes) */
    MasterSeed,

    /** Random seed used for key transformation/derivation (legacy AES-KDF). */
    TransformSeed,

    /** Number of transformation rounds for key derivation (legacy AES-KDF). */
    TransformRounds,

    /** Initialization vector for the encryption cipher. */
    EncryptionIV,

    /** Key used for the inner random stream encryption. */
    InnerRandomStreamKey,

    /** First bytes of decrypted data used to verify correct decryption. */
    StreamStartBytes,

    /** Identifier for inner random stream algorithm. */
    InnerRandomStreamId,

    /** Parameters for modern key derivation functions stored as variant dictionary. */
    KdfParameters,

    /** Custom data that can be read by third-party applications. */
    PublicCustomData
}
