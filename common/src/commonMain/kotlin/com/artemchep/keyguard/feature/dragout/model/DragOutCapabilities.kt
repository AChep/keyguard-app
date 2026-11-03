package com.artemchep.keyguard.feature.dragout.model

import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform

/** What a platform can hand to another app without a Keyguard-owned plaintext file. */
data class DragOutCapabilities(
    val files: Boolean,
    /** The verified plaintext cap for one file; null leaves only the content store's budget. */
    val fileMaximumBytes: Long?,
    val urls: Boolean,
) {
    companion object {
        val None = DragOutCapabilities(files = false, fileMaximumBytes = null, urls = false)

        fun of(platform: Platform): DragOutCapabilities = when (platform) {
            is Platform.Mobile.Android -> if (platform.isWatch) None else full()
            // iOS hands the recipient one in-memory Data representation.
            is Platform.Mobile.Ios -> full(fileMaximumBytes = IOS_FILE_MAXIMUM_BYTES)
            is Platform.Desktop.MacOS,
            is Platform.Desktop.Windows,
            -> full()
            // Linux has no portable transport for virtual files; the portal only accepts paths.
            is Platform.Desktop.Linux,
            is Platform.Desktop.Other,
            -> DragOutCapabilities(files = false, fileMaximumBytes = null, urls = true)
        }

        private fun full(fileMaximumBytes: Long? = null) =
            DragOutCapabilities(files = true, fileMaximumBytes = fileMaximumBytes, urls = true)

        private const val IOS_FILE_MAXIMUM_BYTES = 2L * 1024 * 1024
    }
}

val dragOutCapabilities: DragOutCapabilities
    get() = DragOutCapabilities.of(CurrentPlatform)
