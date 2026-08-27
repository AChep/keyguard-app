package com.artemchep.keyguard.common.service.gpgagent

import com.artemchep.keyguard.common.io.runCatchingNonFatal
import com.artemchep.keyguard.common.service.agent.currentUnixEffectiveUid
import com.artemchep.keyguard.common.service.agent.macosDevAgentSocketPath
import com.artemchep.keyguard.copy.DataDirectory
import com.artemchep.keyguard.platform.Platform
import java.nio.file.Files
import java.nio.file.Path

/**
 * The GnuPG home directory that Keyguard owns and serves the agent socket
 * for. The [kind] selects how the home is prepared before use.
 */
internal data class ManagedGpgHome(
    val path: Path,
    val kind: Kind,
    /** Keyguard-owned directories to secure, ordered from parent to child. */
    val ownedDirectories: List<Path> = listOf(path),
) {
    enum class Kind {
        MACOS,
        LINUX,
        WINDOWS,
    }

    val isWindows: Boolean
        get() = kind == Kind.WINDOWS

    companion object {
        fun ofPlatformOrNull(
            platform: Platform,
            dataDirectory: DataDirectory,
        ): ManagedGpgHome? = when (platform) {
            is Platform.Desktop.MacOS -> macosManagedGpgHomePath(
                userHome = Path.of(System.getProperty("user.home")),
                developmentHome = macosDevAgentSocketPath("gnupg"),
            ).let { home ->
                ManagedGpgHome(
                    path = home,
                    kind = Kind.MACOS,
                    ownedDirectories = listOf(requireNotNull(home.parent), home),
                )
            }

            is Platform.Desktop.Linux -> linuxManagedGpgHome(platform)

            is Platform.Desktop.Windows -> ManagedGpgHome(
                path = Path.of(dataDirectory.dataBlocking())
                    .resolve("gnupg"),
                kind = Kind.WINDOWS,
            )

            else -> null
        }
    }
}

internal fun ManagedGpgHome.prepare() {
    when (kind) {
        ManagedGpgHome.Kind.WINDOWS -> {
            // NTFS has no POSIX permissions; the home is inside the
            // app-private data directory.
            Files.createDirectories(path)
            appendNoAutostartLine(path.resolve("common.conf"))
        }

        ManagedGpgHome.Kind.LINUX -> {
            val uid = currentUnixUid()
                ?: error("Could not determine the current Linux user ID")
            prepareLinuxManagedGpgHome(
                home = path,
                defaultUserHome = Path.of(System.getProperty("user.home")).resolve(".gnupg"),
                expectedUid = uid,
                ownedDirectories = ownedDirectories,
            )
        }

        ManagedGpgHome.Kind.MACOS -> {
            val uid = currentUnixUid()
                ?: error("Could not determine the current macOS user ID")
            prepareMacosManagedGpgHome(
                home = path,
                expectedUid = uid,
            )
            ensureUnixNoAutostart(path, uid)
        }
    }
}

private fun linuxManagedGpgHome(platform: Platform.Desktop.Linux): ManagedGpgHome {
    if (platform.isFlatpak) {
        return ManagedGpgHome(
            path = flatpakManagedGpgHomePath(),
            kind = ManagedGpgHome.Kind.LINUX,
        )
    }

    return linuxManagedGpgHome(
        xdgRuntimeDir = System.getenv("XDG_RUNTIME_DIR"),
        uid = currentUnixUid() ?: error("Could not determine the current Linux user ID"),
    )
}

private fun flatpakManagedGpgHomePath(): Path =
    gpgEnvPath("XDG_DATA_HOME")
        ?.resolve("gnupg")
        ?: Path.of(System.getProperty("user.home"))
            .resolve(".var")
            .resolve("app")
            .resolve(flatpakId())
            .resolve("data")
            .resolve("gnupg")

private fun flatpakId(): String =
    System.getenv("FLATPAK_ID")
        ?.takeIf { it.isNotBlank() }
        ?: FLATPAK_APP_ID_FALLBACK

internal fun linuxManagedGpgHome(
    xdgRuntimeDir: String?,
    uid: Long,
): ManagedGpgHome {
    val runtimeDir = xdgRuntimeDir
        ?.takeIf { it.isNotBlank() }
        ?.let { Path.of(it) }
    if (runtimeDir != null) {
        return ManagedGpgHome(
            path = runtimeDir.resolve("keyguard-gpg-agent"),
            kind = ManagedGpgHome.Kind.LINUX,
        )
    }

    val keyguardDirectory = Path.of("/tmp", "keyguard-$uid")
    val home = keyguardDirectory.resolve("gnupg")
    return ManagedGpgHome(
        path = home,
        kind = ManagedGpgHome.Kind.LINUX,
        ownedDirectories = listOf(keyguardDirectory, home),
    )
}

internal fun macosManagedGpgHomePath(
    userHome: Path,
    developmentHome: Path?,
): Path = developmentHome
    ?: userHome.resolve("Library")
        .resolve("Group Containers")
        .resolve("com.artemchep.keyguard")
        .resolve("gnupg")

internal fun isDefaultUserGpgHome(
    home: Path,
    platform: Platform,
): Boolean {
    val userHome = System.getProperty("user.home")
        ?.takeIf { it.isNotBlank() }
        ?.let { Path.of(it) }
    val defaultHomes = buildList {
        userHome?.resolve(".gnupg")?.let(::add)
        if (platform is Platform.Desktop.Windows) {
            gpgEnvPath("APPDATA")?.resolve("gnupg")?.let(::add)
        }
    }
    val normalizedHome = home.toAbsolutePath().normalize()
    return defaultHomes.any { it.toAbsolutePath().normalize() == normalizedHome }
}

internal fun gpgEnvPath(name: String): Path? =
    System.getenv(name)
        ?.takeIf { it.isNotBlank() }
        ?.let { Path.of(it) }

private fun currentUnixUid(): Long? = runCatchingNonFatal {
    currentUnixEffectiveUid()
}.getOrNull()

private const val FLATPAK_APP_ID_FALLBACK = "com.artemchep.keyguard"
