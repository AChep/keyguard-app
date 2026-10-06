package com.artemchep.keyguard.copy

import com.artemchep.keyguard.util.io.toKotlinxIoPath
import com.artemchep.keyguard.util.io.toLocalPathFromFileUriOrNull
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

/**
 * Plaintext copies of the files a user picks or drops on iOS. The system only
 * grants access to the original briefly, so the SwiftUI layer copies it to
 * `<tmp>/keyguard-import/<UUID>/<name>` first, into a [newDirectory].
 *
 * A copy is useful only to the process that made it: it is deleted once the
 * pending-upload staging has encrypted it, and whatever a cancelled form left
 * behind is cleared on the next launch.
 */
object AppleManagedImportFiles {
    private const val DIRECTORY_NAME = "keyguard-import"

    private val root: Path
        get() = Path(NSTemporaryDirectory(), DIRECTORY_NAME)

    /** The `<UUID>` directory for a new copy; the caller creates it. */
    fun newDirectory(): Path = Path(root, NSUUID().UUIDString)

    /**
     * Deletes the file at [uri] and its `<UUID>` directory if, and only if, it
     * is a managed copy. Anything else, such as the user's original file that
     * the macOS picker hands over, is left untouched.
     */
    fun deleteIfManaged(uri: String): Boolean {
        val file = managedFileOrNull(uri)
            ?: return false
        SystemFileSystem.delete(file, mustExist = false)
        // Holds exactly one copy, so it should be empty now.
        runCatching {
            file.parent?.let { directory ->
                SystemFileSystem.delete(directory, mustExist = false)
            }
        }
        return true
    }

    /** The resolved path of [uri] if it is a managed copy: `<root>/<UUID>/<name>`. */
    private fun managedFileOrNull(uri: String): Path? {
        // Resolving symlinks makes /var and /private/var compare equal.
        val resolvedRoot = resolvedOrNull(root)
            ?: return null
        return uri.toLocalPathFromFileUriOrNull()
            ?.let { path -> resolvedOrNull(path.toKotlinxIoPath()) }
            ?.takeIf { file ->
                file.parent?.parent == resolvedRoot &&
                    SystemFileSystem.metadataOrNull(file)?.isRegularFile == true
            }
    }

    @OptIn(ExperimentalForeignApi::class)
    fun clear() {
        NSFileManager.defaultManager.removeItemAtPath(
            path = root.toString(),
            error = null,
        )
    }

    private fun resolvedOrNull(path: Path): Path? =
        runCatching { SystemFileSystem.resolve(path) }.getOrNull()
}
