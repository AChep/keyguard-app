package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.service.Files
import com.artemchep.keyguard.common.service.keyvalue.impl.JsonKeyValueStore
import com.artemchep.keyguard.common.service.vault.FingerprintReadWriteRepository
import com.artemchep.keyguard.common.usecase.ClearData
import com.artemchep.keyguard.platform.LocalPath
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager

class ClearDataApple(
    private val directories: () -> List<LocalPath>,
    private val fingerprintRepository: FingerprintReadWriteRepository,
    private val getStore: (Files) -> JsonKeyValueStore,
) : ClearData {
    @OptIn(ExperimentalForeignApi::class)
    override fun invoke(): IO<Unit> = ioEffect {
        directories().forEach { directory ->
            val fileManager = NSFileManager.defaultManager
            if (fileManager.fileExistsAtPath(directory.value)) {
                check(fileManager.removeItemAtPath(path = directory.value, error = null)) {
                    "Could not erase app data."
                }
            }
        }
        // Reset the same stores held by live repositories. Removing their files
        // alone leaves cached preferences active and able to be written back.
        Files.entries.filter { it != Files.FINGERPRINT }.forEach { file ->
            getStore(file).clearAndCommit().bind()
        }
        // Apple apps keep running after erasure. Update the live authentication flow
        // after removing the vault files so the root returns to vault creation.
        fingerprintRepository.put(null).bind()
    }
}
