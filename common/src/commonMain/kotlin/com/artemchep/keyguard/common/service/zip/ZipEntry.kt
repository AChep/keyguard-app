package com.artemchep.keyguard.common.service.zip

import java.io.InputStream
import java.io.OutputStream

class ZipEntry(
    val name: String,
    val data: Data,
) {
    sealed interface Data {
        data class In(
            val stream: suspend () -> InputStream,
        ) : Data

        data class Out(
            val stream: suspend (OutputStream) -> Unit,
        ) : Data
    }
}
