package com.artemchep.keyguard.feature.filepicker

import com.artemchep.keyguard.platform.LocalPath
import com.artemchep.keyguard.util.io.atomic.AtomicPathComponent
import com.artemchep.keyguard.util.io.resolve
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileType
import platform.Foundation.NSFileTypeDirectory
import kotlin.uuid.Uuid

internal fun newFilePickerStagingDirectoryName(): AtomicPathComponent =
    AtomicPathComponent.parse("$FILE_PICKER_STAGING_PREFIX${Uuid.random()}")

internal fun isFilePickerStagingDirectoryName(
    name: String,
): Boolean {
    val uuidText = name
        .takeIf { candidate -> candidate.startsWith(FILE_PICKER_STAGING_PREFIX) }
        ?.removePrefix(FILE_PICKER_STAGING_PREFIX)
    val uuid = uuidText?.let { value ->
        runCatching {
            Uuid.parse(value)
        }.getOrNull()
    }
    return uuid != null && uuid.toString() == uuidText
}

@OptIn(ExperimentalForeignApi::class)
internal fun cleanUpFilePickerStagingDirectories(
    root: LocalPath,
) {
    val fileManager = NSFileManager.defaultManager
    val names = fileManager
        .contentsOfDirectoryAtPath(root.value, error = null)
        ?.filterIsInstance<String>()
        .orEmpty()
    names
        .asSequence()
        .filter(::isFilePickerStagingDirectoryName)
        .forEach { name ->
            val directory = root.resolve(name)
            val attributes = fileManager.attributesOfItemAtPath(
                path = directory.value,
                error = null,
            )
            if (attributes?.get(NSFileType) != NSFileTypeDirectory) {
                return@forEach
            }
            fileManager.removeItemAtPath(
                path = directory.value,
                error = null,
            )
        }
}

internal const val FILE_PICKER_STAGING_PREFIX = "keyguard-file-picker-"
