package com.artemchep.keyguard.platform

import com.artemchep.keyguard.util.io.atomic.AtomicDirectoryDestination
import com.artemchep.keyguard.util.io.atomic.AtomicPathComponent
import com.artemchep.keyguard.util.io.atomic.AtomicRelativePath
import com.artemchep.keyguard.util.io.resolve
import com.artemchep.keyguard.util.io.toKotlinxIoPath
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.io.files.SystemFileSystem
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.posix.mkdtemp

/** A private, disposable root shared by file and SQLCipher integration tests. */
@OptIn(ExperimentalForeignApi::class)
internal class TestAppleStorageDirectories : AppleStorageDirectories {
    val root: LocalPath = run {
        val template = (NSTemporaryDirectory().trimEnd('/') + "/keyguard-storage-XXXXXX\u0000")
            .encodeToByteArray()
        template.usePinned {
            LocalPath(checkNotNull(mkdtemp(it.addressOf(0))).toKString())
        }
    }

    override val data = AtomicDirectoryDestination(
        root = root,
        relativePath = AtomicRelativePath.fromComponents(AtomicPathComponent.parse("data")),
    )
    override val cache = root.resolve("cache")
    override val temporary = root.resolve("temporary")

    init {
        var initialized = false
        try {
            listOf(data.path, cache, temporary).forEach {
                SystemFileSystem.createDirectories(it.toKotlinxIoPath())
            }
            initialized = true
        } finally {
            if (!initialized) close()
        }
    }

    fun close() {
        val files = NSFileManager.defaultManager
        if (files.fileExistsAtPath(root.value)) {
            check(files.removeItemAtPath(root.value, null)) { "Could not remove test storage: $root" }
        }
    }
}

internal suspend fun <T> withTestAppleStorage(
    block: suspend (TestAppleStorageDirectories) -> T,
): T {
    val directories = TestAppleStorageDirectories()
    try {
        return block(directories)
    } finally {
        directories.close()
    }
}
