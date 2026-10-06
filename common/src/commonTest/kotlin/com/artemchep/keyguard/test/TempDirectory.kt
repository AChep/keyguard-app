package com.artemchep.keyguard.test

import java.nio.file.Path
import kotlin.io.path.createTempDirectory

/**
 * Runs [block] with a fresh temporary directory and deletes it afterwards.
 * JVM-only: not on the Apple test include list.
 */
internal inline fun <T> withTempDirectory(
    prefix: String = "keyguard-test",
    block: (Path) -> T,
): T {
    val dir = createTempDirectory(prefix)
    try {
        return block(dir)
    } finally {
        dir.toFile().deleteRecursively()
    }
}
