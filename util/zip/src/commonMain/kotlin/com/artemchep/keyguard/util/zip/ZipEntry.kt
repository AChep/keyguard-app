package com.artemchep.keyguard.util.zip

import kotlinx.io.Sink
import kotlinx.io.Source

/** @param name the entry path inside the archive; `/` separates directories. */
class ZipEntry(
    val name: String,
    val data: Data,
) {
    sealed interface Data {
        /** The entry's bytes are pulled from a source the archiver closes. */
        data class In(
            val stream: suspend () -> Source,
        ) : Data

        /** The entry's bytes are pushed into a sink the archiver owns. */
        data class Out(
            val stream: suspend (Sink) -> Unit,
        ) : Data
    }
}
