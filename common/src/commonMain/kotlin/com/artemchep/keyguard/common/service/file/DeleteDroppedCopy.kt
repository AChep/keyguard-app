package com.artemchep.keyguard.common.service.file

import com.artemchep.keyguard.common.io.runCatchingNonFatal
import com.artemchep.keyguard.platform.recordException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Deletes the app's own copy of a dropped file at [uri]. On Android and
 * iOS a drop can arrive as such a copy; any other file is left alone.
 */
suspend fun FileService.deleteDroppedCopy(uri: String) {
    withContext(NonCancellable + Dispatchers.IO) {
        runCatchingNonFatal {
            deleteManagedSourceFile(uri)
        }.onFailure(::recordException)
    }
}

/**
 * Runs [block] that reads the file at [uri] once, then deletes the
 * app's own copy of it, see [deleteDroppedCopy].
 */
suspend inline fun <T> FileService.useDroppedCopy(
    uri: String,
    block: () -> T,
): T = try {
    block()
} finally {
    deleteDroppedCopy(uri)
}
