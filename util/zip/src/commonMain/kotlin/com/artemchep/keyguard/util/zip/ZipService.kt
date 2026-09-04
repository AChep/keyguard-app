package com.artemchep.keyguard.util.zip

import kotlinx.io.Sink

/** Writes ZIP archives; zip4j on the JVM, the `keyguard-zip` crate on Apple. */
interface ZipService {
    /**
     * Writes a complete archive of [entries] into [outputStream] and flushes
     * it. The sink is never closed.
     *
     * @throws ZipException when the archive cannot be produced.
     */
    suspend fun zip(
        outputStream: Sink,
        config: ZipConfig,
        entries: List<ZipEntry>,
    )
}

expect fun createZipService(): ZipService
