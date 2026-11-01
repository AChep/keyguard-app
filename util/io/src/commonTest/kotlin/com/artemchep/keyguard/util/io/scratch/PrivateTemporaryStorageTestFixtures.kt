package com.artemchep.keyguard.util.io.scratch

import com.artemchep.keyguard.util.io.InternalKeyguardIoApi
import com.artemchep.keyguard.util.io.LocalPath
import kotlinx.io.Buffer
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random

@OptIn(InternalKeyguardIoApi::class)
internal fun withSealedPrivateStorage(payload: ByteArray, block: (PrivateTemporaryStorage) -> Unit) {
    val directory = Path(SystemTemporaryDirectory, "keyguard-read-at-${Random.nextLong()}")
    SystemFileSystem.createDirectories(directory)
    try {
        createPrivateTemporaryStorage(LocalPath(directory.toString())).use { storage ->
            storage.sink().write(Buffer().apply { write(payload) }, payload.size.toLong())
            storage.sealForReading()
            block(storage)
        }
    } finally {
        SystemFileSystem.delete(directory)
    }
}
