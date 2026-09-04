package com.artemchep.keyguard.util.zip

/**
 * The archive both compatibility directions build. The Rust side repeats this
 * definition in `util/zip/rust/crates/keyguard-zip-core/tests/`; keep them in
 * sync.
 */
internal object CompatFixtures {
    const val PASSWORD = "correct horse battery staple"

    val NAMES = listOf("hello.txt", "nested/dir/data.bin", "empty.txt")

    val CONTENTS = listOf(
        "Hello from Keyguard zip fixtures.\n".encodeToByteArray(),
        ByteArray(100 * 1024) { index -> ((index * 7 + 3) % 251).toByte() },
        ByteArray(0),
    )
}
