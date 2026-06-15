package com.artemchep.keyguard.common.service.zip

import kotlinx.io.Sink
import kotlinx.io.Source

class ZipEntry(
    val name: String,
    val data: Data,
) {
    sealed interface Data {
        data class In(
            val stream: suspend () -> Source,
        ) : Data

        data class Out(
            val stream: suspend (Sink) -> Unit,
        ) : Data
    }
}
