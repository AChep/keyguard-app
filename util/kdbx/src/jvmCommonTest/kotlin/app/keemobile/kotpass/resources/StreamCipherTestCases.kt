package app.keemobile.kotpass.resources

internal class StreamCipherEncryptionTestCase(
    val rounds: Int,
    val key: String,
    val iv: String,
    val plaintext: String,
    val cipher: String
)

internal class StreamCipherTestCase(
    val rounds: Int,
    val key: String,
    val iv: String,
    val output: String
)
