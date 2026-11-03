package com.artemchep.keyguard.common.service.sshagent.bookmarks.impl

import com.artemchep.keyguard.common.service.crypto.SSH_CERTIFICATE_FILE_SUFFIX
import com.artemchep.keyguard.common.service.gpgagent.OWNER_ONLY_FILE_PERMISSIONS
import com.artemchep.keyguard.common.service.sshagent.bookmarks.SSH_BOOKMARKS_CONFIG_FILE_NAME
import com.artemchep.keyguard.common.service.sshagent.bookmarks.SSH_BOOKMARKS_CONFIG_MARKER
import com.artemchep.keyguard.common.service.sshagent.bookmarks.SshBookmarksConfig
import com.artemchep.keyguard.common.service.sshagent.bookmarks.SshBookmarksConfigStore
import com.artemchep.keyguard.common.service.sshagent.bookmarks.SshBookmarksSummary
import com.artemchep.keyguard.common.usecase.ClearDataHook
import com.artemchep.keyguard.copy.DataDirectory
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.platform.flatpakDataDirectory
import com.artemchep.keyguard.util.io.LocalPath
import com.artemchep.keyguard.util.io.atomic.AtomicDirectoryPermissions
import com.artemchep.keyguard.util.io.atomic.AtomicFilePermissions
import com.artemchep.keyguard.util.io.atomic.AtomicPublicationPolicy
import com.artemchep.keyguard.util.io.atomic.AtomicWriteOptions
import com.artemchep.keyguard.util.io.atomic.ExistingParentLinkPolicy
import com.artemchep.keyguard.util.io.atomic.ParentDirectoryPolicy
import com.artemchep.keyguard.util.io.atomic.ReplacementAccessPolicy
import com.artemchep.keyguard.util.io.atomic.SyncLevel
import com.artemchep.keyguard.util.io.atomic.SynchronizationPolicy
import com.artemchep.keyguard.util.io.writeText
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.UserPrincipal

// OpenSSH refuses to read a configuration that other users can write, and
// the include applies to every host, so a lax mode would break every login.
private val SSH_BOOKMARKS_ATOMIC_WRITE_OPTIONS = AtomicWriteOptions(
    publication = AtomicPublicationPolicy.Replace(
        access = ReplacementAccessPolicy.UseRequestedPermissions(
            permissions = AtomicFilePermissions.OwnerOnly,
        ),
    ),
    parentDirectories = ParentDirectoryPolicy.CreateMissing(
        permissions = AtomicDirectoryPermissions.OwnerOnly,
    ),
    // Dotfile managers often link ~/.ssh, and some systems link /home.
    existingParentLinks = ExistingParentLinkPolicy.FollowAndPin,
    synchronization = SynchronizationPolicy.Required(
        SyncLevel.FileSynchronized,
    ),
)

// Only these names are ever deleted, so files that
// the user puts in the directory survive.
private val SSH_BOOKMARKS_FILE_NAME_REGEX = run {
    val config = Regex.escape(SSH_BOOKMARKS_CONFIG_FILE_NAME)
    val certificate = Regex.escape(SSH_CERTIFICATE_FILE_SUFFIX)
    Regex("^($config|[0-9a-f]{64}(\\.pub|$certificate))$")
}

/**
 * Whether only the [owner] has access. OpenSSH for Windows refuses a
 * configuration that another user can write, for example through an
 * access entry inherited from the home directory.
 */
internal fun isOwnerOnlyAcl(
    owner: UserPrincipal,
    acl: List<AclEntry>,
): Boolean = acl.all { entry ->
    entry.type() != AclEntryType.ALLOW || entry.principal() == owner
}

/**
 * Keeps the generated SSH configuration of bookmarked hosts in a directory
 * that the SSH clients of the host read.
 */
class SshBookmarksConfigStoreImpl(
    private val directory: Path,
    override val configDirectory: String,
    /** The home whose `.ssh/config` should include the configuration, if the app can read it. */
    private val sshHome: Path? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SshBookmarksConfigStore, ClearDataHook {
    companion object {
        /**
         * The directory for the [platform]. Flatpak can't write to `~/.ssh`,
         * but its data directory has the same path on the host.
         */
        fun ofPlatform(
            platform: Platform,
            userHome: Path = Path.of(System.getProperty("user.home")),
        ): SshBookmarksConfigStoreImpl {
            val flatpak = platform is Platform.Desktop.Linux && platform.isFlatpak
            val directory = if (flatpak) {
                flatpakDataDirectory(userHome)
                    .resolve("ssh-bookmarks")
            } else {
                userHome
                    .resolve(".ssh")
                    .resolve(DataDirectory.APP_NAME)
            }
            return SshBookmarksConfigStoreImpl(
                directory = directory,
                configDirectory = sshConfigDirectory(
                    directory = directory,
                    userHome = userHome,
                ),
                // Flatpak can't read ~/.ssh.
                sshHome = userHome.takeUnless { flatpak },
            )
        }

        /** Writes the [directory] relative to the home, so it reads the same for any user. */
        internal fun sshConfigDirectory(
            directory: Path,
            userHome: Path,
        ): String = if (directory.startsWith(userHome) && directory != userHome) {
            "~/" + userHome.relativize(directory).joinToString("/")
        } else {
            directory.toString()
        }
    }

    private val mutex = Mutex()

    private val summarySink = MutableStateFlow<SshBookmarksSummary?>(null)

    private val configFile = directory.resolve(SSH_BOOKMARKS_CONFIG_FILE_NAME)

    override val includePath: String = configFile.toString()

    override val summary: StateFlow<SshBookmarksSummary?> = summarySink.asStateFlow()

    override suspend fun isIncluded(): Boolean? {
        val home = sshHome
            ?: return null
        return withContext(ioDispatcher) {
            try {
                val text = Files.readString(home.resolve(".ssh").resolve("config"))
                sshConfigIncludes(
                    text = text,
                    file = configFile,
                    userHome = home,
                )
            } catch (_: NoSuchFileException) {
                false
            } catch (_: IOException) {
                // Unknown, for example a file that isn't UTF-8.
                null
            }
        }
    }

    override suspend fun replaceAll(config: SshBookmarksConfig) {
        mutex.withLock {
            withContext(ioDispatcher) {
                // The configuration goes last, so that it
                // never names a key file that isn't there.
                config.files
                    .filterKeys { it != SSH_BOOKMARKS_CONFIG_FILE_NAME }
                    .forEach { (name, text) -> writeIfChanged(name, text) }
                config.files[SSH_BOOKMARKS_CONFIG_FILE_NAME]
                    ?.let { text -> writeIfChanged(SSH_BOOKMARKS_CONFIG_FILE_NAME, text) }
                generatedFiles()
                    .filter { it.fileName.toString() !in config.files }
                    .forEach(Files::deleteIfExists)
            }
            summarySink.value = config.summary
        }
    }

    override suspend fun clear() {
        mutex.withLock {
            withContext(ioDispatcher) {
                val files = generatedFiles()
                files.forEach(Files::deleteIfExists)
                // Keep a link to the directory, a directory that had no
                // generated files, and the directory if the user's files are in it.
                if (files.isNotEmpty() && Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                    try {
                        Files.deleteIfExists(directory)
                    } catch (_: DirectoryNotEmptyException) {
                        // Do nothing.
                    }
                }
            }
            summarySink.value = null
        }
    }

    override suspend fun beforeClearData() = clear()

    private fun writeIfChanged(
        name: String,
        text: String,
    ) {
        val path = directory.resolve(name)
        if (isUpToDate(path, text)) {
            return
        }
        LocalPath(path.toString())
            .writeText(text, SSH_BOOKMARKS_ATOMIC_WRITE_OPTIONS)
            .requireCleanupComplete()
    }

    /** Whether the file at [path] holds the [text], with a mode that OpenSSH accepts. */
    private fun isUpToDate(
        path: Path,
        text: String,
    ): Boolean = try {
        Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
            isOwnerOnly(path) &&
            // Bytes, so that a file that isn't UTF-8 reads as different.
            Files.readAllBytes(path).contentEquals(text.encodeToByteArray())
    } catch (_: IOException) {
        // The write replaces a file that can't be read.
        false
    }

    private fun isOwnerOnly(path: Path): Boolean {
        val posix = Files.getFileAttributeView(
            path,
            PosixFileAttributeView::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        if (posix != null) {
            return posix.readAttributes().permissions() == OWNER_ONLY_FILE_PERMISSIONS
        }
        // Windows has no POSIX modes; the atomic write
        // sets an owner-only access list there instead.
        val acl = Files.getFileAttributeView(
            path,
            AclFileAttributeView::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        return acl != null &&
            isOwnerOnlyAcl(
                owner = acl.owner,
                acl = acl.acl,
            )
    }

    private fun generatedFiles(): List<Path> {
        if (!Files.isDirectory(directory)) {
            return emptyList()
        }
        return Files.newDirectoryStream(directory).use { stream ->
            stream.filter { path ->
                val name = path.fileName.toString()
                SSH_BOOKMARKS_FILE_NAME_REGEX.matches(name) &&
                    Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
                    (name != SSH_BOOKMARKS_CONFIG_FILE_NAME || isGeneratedConfig(path))
            }
        }
    }

    /** Whether Keyguard wrote the configuration at [path], rather than the user. */
    private fun isGeneratedConfig(path: Path): Boolean = try {
        val marker = SSH_BOOKMARKS_CONFIG_MARKER.encodeToByteArray()
        Files.newInputStream(path)
            .use { it.readNBytes(marker.size) }
            .contentEquals(marker)
    } catch (_: IOException) {
        false
    }
}
