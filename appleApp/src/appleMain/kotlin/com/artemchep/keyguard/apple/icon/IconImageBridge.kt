package com.artemchep.keyguard.apple.icon

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.usecase.GetIconImage
import com.artemchep.keyguard.feature.favicon.iconRequestOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Loads the images of the `iconKey`s that snapshots carry. Swift
 * can not cancel these calls, so a fast scroll queues its loads
 * behind a few permits instead of opening a connection per row.
 */
internal class IconImageBridge(
    private val getIconImage: GetIconImage,
    permits: Int = DEFAULT_PERMITS,
) {
    private val semaphore = Semaphore(permits)

    /** Returns `null` if there is nothing to load; throws if the download fails. */
    suspend fun load(key: String): ByteArray? {
        val request = iconRequestOrNull(key)
            ?: return null
        return semaphore.withPermit {
            getIconImage(request).bind()
        }
    }

    private companion object {
        const val DEFAULT_PERMITS = 6
    }
}
