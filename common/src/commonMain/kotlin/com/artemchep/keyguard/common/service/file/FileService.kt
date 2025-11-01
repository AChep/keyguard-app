package com.artemchep.keyguard.common.service.file

import java.io.InputStream
import java.io.OutputStream

interface FileService {
    fun exists(
        uri: String,
    ): Boolean

    fun readFromFile(
        uri: String,
    ): InputStream

    fun writeToFile(
        uri: String,
    ): OutputStream
}
