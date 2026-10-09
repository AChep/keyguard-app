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
 * App data is created below AppDirs' platform-data directory.
 */
internal fun DataDirectory.atomicDataDirectory(): AtomicDirectoryDestination =
    atomicAppDirectory(dataPlatformBlocking())

/**
 * Downloads are created below AppDirs' platform-downloads directory.
 */
internal fun DataDirectory.atomicDownloadsDirectory(): AtomicDirectoryDestination =
    atomicAppDirectory(downloadsPlatformBlocking())

private fun DataDirectory.atomicAppDirectory(
    platformDirectory: String,
): AtomicDirectoryDestination = atomicAppDirectory(
    platformDirectory = Path.of(platformDirectory),
    appDirectory = Path.of(appDirectory(platformDirectory)),
    creatableRoot = creatableRoot(),
)

/**
 * A Flatpak sandbox discards what it does not expose, so there a missing
 * platform directory must not be created.
 */
private fun creatableRoot(): Path? {
    val platform = CurrentPlatform
    if (platform is Platform.Desktop.Linux && platform.isFlatpak) {
        return null
    }
    return Path.of(System.getProperty("user.home"))
}

/**
 * Trusts the platform-owned directory, which may be a link, and rejects links
 * in the app-owned components below it.
 *
 * A missing platform directory is created only inside [creatableRoot].
 * Elsewhere it might be on a drive that is not mounted.
 */
internal fun atomicAppDirectory(
    platformDirectory: Path,
    appDirectory: Path,
    creatableRoot: Path?,
): AtomicDirectoryDestination {
    val platform = platformDirectory
        .toAbsolutePath()
        .normalize()
    val directory = appDirectory
        .toAbsolutePath()
        .normalize()
    require(directory.startsWith(platform) && directory != platform) {
        "App directory must be below its platform directory"
    }
    // A fresh profile might lack the platform directory, for example
    // the user's Downloads folder. Trust its nearest existing ancestor
    // instead, so the write creates the missing components.
    val root = requireNotNull(
        generateSequence(platform) { it.parent }
            .firstOrNull { Files.isDirectory(it) },
    ) {
        "Platform directory has no existing ancestor"
    }
    val creatable = creatableRoot != null && root.startsWith(creatableRoot)
    require(root == platform || creatable) {
        "Platform directory $platform does not exist"
    }
    val components = root
        .relativize(directory)
        .map { component ->
            AtomicPathComponent.parse(component.toString())
        }
    return AtomicDirectoryDestination(
        root = LocalPath(root.toString()),
        relativePath = AtomicRelativePath.fromComponents(
            first = components.first(),
            *components.drop(1).toTypedArray(),
        ),
    )
}
