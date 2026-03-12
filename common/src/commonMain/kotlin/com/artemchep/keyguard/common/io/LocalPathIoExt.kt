package com.artemchep.keyguard.common.io

import com.artemchep.keyguard.platform.LocalPath
import com.artemchep.keyguard.platform.toKotlinxIoPath
import kotlinx.io.buffered
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.io.readString
import kotlinx.io.writeString

fun LocalPath.readText(): String {
    val source = SystemFileSystem.source(toKotlinxIoPath())
        .buffered()
    return source.use { source ->
        source.readString()
    }
}

fun LocalPath.writeText(
    data: String,
) {
    val sink = SystemFileSystem.sink(toKotlinxIoPath())
        .buffered()
    sink.use { sink ->
        sink.writeString(data)
        sink.flush()
    }
}

fun LocalPath.readBytes(): ByteArray {
    val source = SystemFileSystem.source(toKotlinxIoPath())
        .buffered()
    return source.use { source ->
        source.readByteArray()
    }
}

fun LocalPath.writeBytes(
    data: ByteArray,
) {
    val sink = SystemFileSystem.sink(toKotlinxIoPath())
        .buffered()
    sink.use { sink ->
        sink.write(data)
        sink.flush()
    }
}
