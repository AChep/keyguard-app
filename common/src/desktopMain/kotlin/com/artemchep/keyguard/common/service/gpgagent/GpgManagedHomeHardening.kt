package com.artemchep.keyguard.common.service.gpgagent

import com.artemchep.keyguard.common.service.agent.OWNER_ONLY_DIRECTORY_PERMISSIONS
import com.artemchep.keyguard.common.service.agent.OWNER_ONLY_FILE_PERMISSIONS
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions

internal fun prepareLinuxManagedGpgHome(
    home: Path,
    defaultUserHome: Path,
    expectedUid: Long,
    ownedDirectories: List<Path>,
) {
    requireSeparateGpgHome(home, defaultUserHome)
    val managedDirectories = ownedDirectories.map { it.toAbsolutePath() }

    // XDG and Flatpak data roots are not Keyguard-owned directories.
    // Allow their aliases and preserve their permissions; only the explicitly
    // owned children must be owner-only, non-symlink directories.
    Files.createDirectories(requireNotNull(managedDirectories.first().parent))
    managedDirectories.forEach { directory ->
        createOrValidateManagedDirectory(directory, expectedUid)
    }
    ensureUnixNoAutostart(home, expectedUid)
}

internal fun prepareMacosManagedGpgHome(
    home: Path,
    expectedUid: Long,
) {
    val normalizedHome = home.toAbsolutePath().normalize()
    val keyguardDirectory = requireNotNull(normalizedHome.parent) {
        "Managed GnuPG home must have a parent directory: $home"
    }
    // Only the Keyguard directory and GnuPG home are ours to restrict.
    // Preserve shared ancestors, including those of development homes.
    Files.createDirectories(requireNotNull(keyguardDirectory.parent))
    listOf(keyguardDirectory, normalizedHome).forEach { directory ->
        createOrValidateManagedDirectory(directory, expectedUid)
    }
}

internal fun ensureUnixNoAutostart(
    home: Path,
    expectedUid: Long,
) {
    val commonConf = home.resolve("common.conf")
    val attributes = try {
        Files.readAttributes(
            commonConf,
            "unix:uid,isRegularFile,isSymbolicLink",
            LinkOption.NOFOLLOW_LINKS,
        )
    } catch (_: NoSuchFileException) {
        appendNoAutostartLine(commonConf)
        Files.setPosixFilePermissions(commonConf, OWNER_ONLY_FILE_PERMISSIONS)
        return
    }
    require(attributes["isSymbolicLink"] != true) {
        "Managed GnuPG path must not be a symbolic link: $commonConf"
    }
    require(attributes["isRegularFile"] == true) {
        "Managed GnuPG path is not a regular file: $commonConf"
    }
    require((attributes.getValue("uid") as Number).toLong() == expectedUid) {
        "Managed GnuPG file is not owned by the current user: $commonConf"
    }
    Files.setPosixFilePermissions(commonConf, OWNER_ONLY_FILE_PERMISSIONS)
    appendNoAutostartLine(commonConf)
}

internal fun appendNoAutostartLine(commonConf: Path) {
    val existing = try {
        Files.readString(commonConf)
    } catch (_: NoSuchFileException) {
        ""
    }
    val hasNoAutostart = existing
        .lineSequence()
        .any { it.trim() == "no-autostart" }
    if (!hasNoAutostart) {
        val prefix = if (existing.isEmpty() || existing.endsWith("\n")) "" else "\n"
        Files.writeString(commonConf, existing + prefix + "no-autostart\n")
    }
}

private fun createOrValidateManagedDirectory(
    directory: Path,
    expectedUid: Long,
) {
    try {
        Files.createDirectory(
            directory,
            PosixFilePermissions.asFileAttribute(OWNER_ONLY_DIRECTORY_PERMISSIONS),
        )
    } catch (_: FileAlreadyExistsException) {
        // An existing path is accepted only after the no-follow checks below.
    }

    val attributes = Files.readAttributes(
        directory,
        "unix:uid,permissions,isDirectory,isSymbolicLink",
        LinkOption.NOFOLLOW_LINKS,
    )
    require(attributes["isSymbolicLink"] != true) {
        "Managed GnuPG path must not be a symbolic link: $directory"
    }
    require(attributes["isDirectory"] == true) {
        "Managed GnuPG path is not a directory: $directory"
    }
    require((attributes.getValue("uid") as Number).toLong() == expectedUid) {
        "Managed GnuPG directory is not owned by the current user: $directory"
    }
    if (attributes.getValue("permissions") != OWNER_ONLY_DIRECTORY_PERMISSIONS) {
        val view = Files.getFileAttributeView(
            directory,
            PosixFileAttributeView::class.java,
            LinkOption.NOFOLLOW_LINKS,
        ) ?: error("POSIX permissions are unavailable for managed GnuPG directory: $directory")
        view.setPermissions(OWNER_ONLY_DIRECTORY_PERMISSIONS)
    }
}

private fun requireSeparateGpgHome(home: Path, defaultUserHome: Path) {
    require(!isSameGpgPath(home, defaultUserHome)) {
        "Refusing to serve the default user GnuPG home: $home"
    }
    val commonConf = home.resolve("common.conf")
    require(!isSameGpgPath(commonConf, defaultUserHome.resolve("common.conf"))) {
        "Refusing to modify the default user GnuPG config: $commonConf"
    }
}

private fun isSameGpgPath(path: Path, other: Path): Boolean {
    // Resolve aliases for comparison only: gpgconf must still receive the
    // lexical GNUPGHOME that the user configures in their shell.
    if (resolveGpgPathForComparison(path) == resolveGpgPathForComparison(other)) {
        return true
    }
    return try {
        Files.isSameFile(path, other)
    } catch (_: NoSuchFileException) {
        false
    }
}

private fun resolveGpgPathForComparison(
    path: Path,
    remainingLinks: Int = 40,
): Path {
    val absolutePath = path.toAbsolutePath()
    try {
        return absolutePath.toRealPath()
    } catch (_: NoSuchFileException) {
        // A default-home or config symlink can point at a managed path that
        // does not exist yet. Resolve its missing suffix before creating it.
        if (Files.isSymbolicLink(absolutePath)) {
            require(remainingLinks > 0) { "Too many symbolic links in GnuPG path: $path" }
            val target = Files.readSymbolicLink(absolutePath)
            return resolveGpgPathForComparison(
                requireNotNull(absolutePath.parent).resolve(target),
                remainingLinks - 1,
            )
        }
        val parent = requireNotNull(absolutePath.parent) {
            "Could not resolve GnuPG path: $path"
        }
        return resolveGpgPathForComparison(parent, remainingLinks)
            .resolve(absolutePath.fileName)
            .normalize()
    }
}
