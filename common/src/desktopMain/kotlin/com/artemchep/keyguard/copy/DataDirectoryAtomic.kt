package com.artemchep.keyguard.copy

import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.LocalPath
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.util.io.atomic.AtomicDirectoryDestination
import com.artemchep.keyguard.util.io.atomic.AtomicPathComponent
import com.artemchep.keyguard.util.io.atomic.AtomicRelativePath
import java.nio.file.Files
import java.nio.file.Path

/**
 * App data is created below AppDirs' existing platform-data parent.
 */
internal fun DataDirectory.atomicDataDirectory(): AtomicDirectoryDestination =
    atomicAppDirectory(Path.of(dataBlocking()))

/**
 * Preserves AppDirs' download location while retaining its existing parent.
 */
internal fun DataDirectory.atomicDownloadsDirectory(): AtomicDirectoryDestination {
    return atomicAppDirectory(Path.of(downloadsBlocking()))
}

private fun atomicAppDirectory(
    appDirectory: Path,
): AtomicDirectoryDestination {
    val directory = appDirectory
        .toAbsolutePath()
        .normalize()
    val platformOwnedSuffixCount = when (CurrentPlatform) {
        Platform.Desktop.Windows -> 2
        else -> 1
    }
    var root = directory
    repeat(platformOwnedSuffixCount) {
        root = requireNotNull(root.parent) {
            "App directory has no platform trust root"
        }
    }
    return atomicDirectoryUnderExistingRoot(
        root = root,
        directory = directory,
    )
}

internal fun atomicDirectoryUnderExistingRoot(
    root: Path,
    directory: Path,
): AtomicDirectoryDestination {
    val normalizedRoot = root
        .toAbsolutePath()
        .normalize()
    require(Files.isDirectory(normalizedRoot)) {
        "Atomic trust root must be an existing directory"
    }
    val normalizedDirectory = directory
        .toAbsolutePath()
        .normalize()
    require(normalizedDirectory.startsWith(normalizedRoot)) {
        "Atomic destination directory must be beneath its trust root"
    }
    val components = normalizedRoot
        .relativize(normalizedDirectory)
        .map { component ->
            AtomicPathComponent.parse(component.toString())
        }
    require(components.isNotEmpty()) {
        "Atomic destination directory must be below its trust root"
    }
    return AtomicDirectoryDestination(
        root = LocalPath(normalizedRoot.toString()),
        relativePath = AtomicRelativePath.fromComponents(
            first = components.first(),
            *components.drop(1).toTypedArray(),
        ),
    )
}
