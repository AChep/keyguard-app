package com.artemchep.keyguard.common.service.secretscan.impl

import com.artemchep.keyguard.common.io.runCatchingNonFatal
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.crypto.SshKeyImportRequest
import com.artemchep.keyguard.common.service.crypto.SshKeyImportResult
import com.artemchep.keyguard.common.service.crypto.SshKeyImportService
import com.artemchep.keyguard.common.service.environment.EnvironmentMounts
import com.artemchep.keyguard.common.service.environment.normalizeEnvironmentMountPath
import com.artemchep.keyguard.common.service.secretscan.PlaintextSecretScanner
import com.artemchep.keyguard.common.service.secretscan.SecretScanEvent
import com.artemchep.keyguard.common.service.secretscan.SecretScanItem
import com.artemchep.keyguard.common.service.secretscan.SecretScanKind
import com.artemchep.keyguard.common.service.secretscan.readWiped
import com.artemchep.keyguard.common.service.secretscan.secretScanLiteralDigest
import com.artemchep.keyguard.common.service.secretscan.secretScanLiteralOrNull
import com.artemchep.keyguard.common.service.secretscan.useWiped
import com.artemchep.keyguard.common.util.toHex
import com.artemchep.keyguard.feature.environments.EnvironmentDotenv
import com.artemchep.keyguard.feature.environments.EnvironmentFormat
import com.artemchep.keyguard.platform.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit

/**
 * Walks the user's folders for plaintext SSH private keys and `.env` files.
 *
 * Symbolic links are never followed, only regular files are read, and the
 * local `.env` files that Keyguard serves are skipped.
 */
class PlaintextSecretScannerImpl(
    private val sshKeyImportService: SshKeyImportService,
    private val environmentMounts: EnvironmentMounts,
    private val cryptoGenerator: CryptoGenerator,
    private val platform: Platform,
    homeDir: Path,
    private val trash: SecretScanTrash = SystemSecretScanTrash(platform),
) : PlaintextSecretScanner {
    /**
     * The real path of the home folder: the walk reports real paths, so a
     * home behind a symbolic link (`/home -> /var/home`) still shows as `~`.
     */
    private val homeDir: Path by lazy {
        runCatchingNonFatal { homeDir.toRealPath() }.getOrDefault(homeDir)
    }

    override val defaultRootsAvailable: Boolean
        get() = !(platform is Platform.Desktop.Linux && platform.isFlatpak)

    override val trashSupported: Boolean
        get() = trash.supported

    override fun scan(
        kind: SecretScanKind,
        extraRoots: List<String>,
    ): Flow<SecretScanEvent> = flow {
        val roots = buildList {
            if (defaultRootsAvailable) {
                // The home walk covers ~/.ssh: a scan for SSH keys is exempt
                // from the hidden folder rule there.
                add(homeDir)
            }
            extraRoots.forEach { root ->
                runCatchingNonFatal { Path.of(root) }.getOrNull()?.let(::add)
            }
        }
            .mapNotNull { root ->
                runCatchingNonFatal { root.toRealPath() }
                    .getOrNull()
                    ?.takeIf { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }
            }
            .distinct()
        val walk = Walk(
            collector = this,
            detector = detectorOf(kind),
            mounts = mountPaths(),
        )
        roots.forEach { root ->
            walk.walkDirectory(root, depth = 0)
        }
        emit(SecretScanEvent.Progress(walk.checked))
        emit(SecretScanEvent.Done(roots.map(::displayPath)))
    }.flowOn(Dispatchers.IO)

    override suspend fun read(item: SecretScanItem): ByteArray? = withContext(Dispatchers.IO) {
        val path = runCatchingNonFatal { Path.of(item.path) }.getOrNull()
            ?: return@withContext null
        val detector = when (item) {
            is SecretScanItem.SshKey -> sshKeyDetector
            is SecretScanItem.DotEnv -> dotEnvDetector
        }
        val size = regularFileSizeOrNull(path, maxSize = detector.maxSize)
            ?: return@withContext null
        val content = readRegularFile(path, size)
            ?: return@withContext null
        if (content.sha256() == item.sha256) {
            content
        } else {
            content.fill(0)
            null
        }
    }

    override suspend fun remove(
        items: List<SecretScanItem>,
    ): Map<String, Boolean> = withContext(Dispatchers.IO) {
        val unchanged = items
            .filter { item -> readWiped(item) { } != null }
            .map { item -> Path.of(item.path) }
        val removed = if (trash.supported) {
            runCatchingNonFatal { trash.moveToTrash(unchanged) }.getOrDefault(emptySet())
        } else {
            unchanged
                .filter { path ->
                    runCatchingNonFatal { Files.deleteIfExists(path) }.getOrDefault(false)
                }
                .toSet()
        }
        items.associate { item ->
            item.path to (Path.of(item.path) in removed)
        }
    }

    /** The [path] with `~` in place of the home folder. */
    private fun displayPath(path: Path): String {
        if (!path.startsWith(homeDir)) return path.toString()
        val rest = homeDir.relativize(path).toString()
        return if (rest.isEmpty()) "~" else "~" + File.separator + rest
    }

    private fun mountPaths(): Set<String> = environmentMounts.state.value.mounts
        .flatMap { mount ->
            val normalized = normalizeEnvironmentMountPath(mount.path)
            // A mount may be configured through a symbolic link, while the
            // walk reports real paths.
            val real = runCatchingNonFatal { Path.of(mount.path).toRealPath().toString() }.getOrNull()
            listOfNotNull(normalized, real)
        }
        .toSet()

    /** What a scan of one [SecretScanKind] reads, and how it reads it. */
    private abstract inner class Detector(
        /** The largest file that is read. */
        val maxSize: Int,
        /** Whether the walk enters the `.ssh` folders, which are hidden. */
        val visitsSshDirectory: Boolean,
    ) {
        /** Whether the [file] may hold a finding, by its name. */
        abstract fun matches(file: Path): Boolean

        abstract fun itemOrNull(file: Path, content: ByteArray): SecretScanItem?
    }

    private val dotEnvDetector: Detector = object : Detector(
        maxSize = EnvironmentFormat.MAX_PAYLOAD_BYTES,
        visitsSshDirectory = false,
    ) {
        override fun matches(file: Path): Boolean =
            isDotEnvName(file.fileName.toString())

        override fun itemOrNull(file: Path, content: ByteArray): SecretScanItem? =
            dotEnvItemOrNull(file, content)
    }

    private val sshKeyDetector: Detector = object : Detector(
        maxSize = MAX_SSH_KEY_FILE_SIZE,
        visitsSshDirectory = true,
    ) {
        override fun matches(file: Path): Boolean =
            isSshKeyCandidate(
                name = file.fileName.toString(),
                parentName = file.parent?.fileName?.toString(),
            )

        override fun itemOrNull(file: Path, content: ByteArray): SecretScanItem? =
            sshKeyItemOrNull(file, content)
    }

    private fun detectorOf(kind: SecretScanKind): Detector = when (kind) {
        SecretScanKind.SshKey -> sshKeyDetector
        SecretScanKind.DotEnv -> dotEnvDetector
    }

    private inner class Walk(
        private val collector: FlowCollector<SecretScanEvent>,
        private val detector: Detector,
        private val mounts: Set<String>,
    ) {
        var checked = 0
            private set

        private var entries = 0

        private val visitedDirectories = mutableSetOf<Path>()

        suspend fun walkDirectory(directory: Path, depth: Int) {
            // A root inside an earlier root is not walked twice.
            val children = if (visitedDirectories.add(directory)) {
                runCatchingNonFatal {
                    Files.newDirectoryStream(directory).use { it.toList() }
                }.getOrNull()
            } else {
                null
            }
            for (child in children.orEmpty().sorted()) {
                currentCoroutineContext().ensureActive()
                if (++entries > MAX_ENTRIES) return
                val attrs = runCatchingNonFatal {
                    Files.readAttributes(
                        child,
                        BasicFileAttributes::class.java,
                        LinkOption.NOFOLLOW_LINKS,
                    )
                }.getOrNull() ?: continue
                when {
                    attrs.isDirectory -> if (
                        depth + 1 < MAX_DEPTH &&
                        !isSkippedDirectory(directory, child.fileName.toString())
                    ) {
                        walkDirectory(child, depth + 1)
                    }

                    // Skips symbolic links, FIFOs and other special files.
                    attrs.isRegularFile -> checkFile(child, attrs.size())
                }
            }
        }

        private fun isSkippedDirectory(parent: Path, name: String): Boolean = when {
            name == SSH_DIRECTORY && detector.visitsSshDirectory -> false
            name.startsWith('.') || name in SKIPPED_DIRECTORIES -> true
            // Only the folders of the home folder itself; an added root
            // is never skipped.
            else -> parent == homeDir && name in skippedHomeDirectories(platform)
        }

        private suspend fun checkFile(file: Path, size: Long) {
            checked++
            if (checked % PROGRESS_INTERVAL == 0) {
                collector.emit(SecretScanEvent.Progress(checked))
            }
            // The name first: it rules out most of the files.
            val skip = !detector.matches(file) ||
                file.toString() in mounts ||
                size > detector.maxSize
            if (skip) return

            val item = withFile(file, size) { content ->
                detector.itemOrNull(file, content)
            }
            if (item != null) {
                collector.emit(SecretScanEvent.Found(item))
            }
        }
    }

    private inline fun withFile(
        file: Path,
        size: Long,
        block: (ByteArray) -> SecretScanItem?,
    ): SecretScanItem? = readRegularFile(file, size)?.useWiped(block)

    private fun dotEnvItemOrNull(file: Path, content: ByteArray): SecretScanItem? {
        val definition = runCatchingNonFatal {
            EnvironmentDotenv.parse(content.decodeToString())
        }.getOrNull() ?: return null
        // `null` if no variable has a literal value.
        val literalDigest = definition.secretScanLiteralDigest(cryptoGenerator)
        return literalDigest?.let { digest ->
            SecretScanItem.DotEnv(
                path = file.toString(),
                displayPath = displayPath(file),
                sha256 = content.sha256(),
                variableCount = definition.variables.count { it.secretScanLiteralOrNull() != null },
                literalDigest = digest,
            )
        }
    }

    private fun sshKeyItemOrNull(file: Path, content: ByteArray): SecretScanItem? {
        if (!hasPrivateKeyHeader(content)) return null
        val result = runCatchingNonFatal {
            sshKeyImportService.import(
                SshKeyImportRequest(
                    content = content,
                    fileName = file.fileName?.toString(),
                ),
            )
        }.getOrNull()
        return when (result) {
            is SshKeyImportResult.Success -> {
                result.keyPair.privateKey.encoded.fill(0)
                sshKeyItem(file, content, encrypted = false, fingerprint = result.keyPair.publicKey.fingerprint)
            }

            is SshKeyImportResult.NeedsPassphrase -> sshKeyItem(file, content, encrypted = true, fingerprint = null)
            is SshKeyImportResult.Error,
            null,
            -> null
        }
    }

    private fun sshKeyItem(
        file: Path,
        content: ByteArray,
        encrypted: Boolean,
        fingerprint: String?,
    ) = SecretScanItem.SshKey(
        path = file.toString(),
        displayPath = displayPath(file),
        sha256 = content.sha256(),
        encrypted = encrypted,
        fingerprint = fingerprint,
    )

    private fun ByteArray.sha256(): String = cryptoGenerator.hashSha256(this).toHex()

    companion object {
        /** The deepest level below a root that is listed. */
        private const val MAX_DEPTH = 8

        /** The number of entries a scan visits at most. */
        private const val MAX_ENTRIES = 200_000

        /** The largest SSH key file that is read; the same as the import accepts. */
        private const val MAX_SSH_KEY_FILE_SIZE = 64 * 1024

        private const val PROGRESS_INTERVAL = 100

        private const val HEADER_LENGTH = 64

        private const val SSH_DIRECTORY = ".ssh"

        private val SKIPPED_DIRECTORIES = setOf(
            "node_modules",
            ".git",
            ".hg",
            ".svn",
            ".venv",
            "venv",
            "__pycache__",
            "target",
            "build",
            "dist",
            ".gradle",
            ".m2",
            ".cargo",
            ".rustup",
            ".npm",
            ".pnpm-store",
            ".cache",
            "vendor",
            "Pods",
            "DerivedData",
        )

        /** The folders that macOS protects with TCC: reading them prompts the user. */
        private val MACOS_SKIPPED_HOME_DIRECTORIES = setOf(
            "Library",
            "Documents",
            "Desktop",
            "Downloads",
            "Pictures",
            "Movies",
            "Music",
        )

        private val WINDOWS_SKIPPED_HOME_DIRECTORIES = setOf(
            "AppData",
        )

        private val DOTENV_EXCLUDED_SUFFIXES = listOf(
            "example",
            "sample",
            "template",
            "dist",
            "defaults",
        )

        private val SSH_EXCLUDED_PREFIXES = listOf(
            "known_hosts",
            "authorized_keys",
        )

        private val SSH_KEY_EXTENSIONS = listOf(
            ".pem",
            ".key",
            ".ppk",
        )

        private val PRIVATE_KEY_HEADER = Regex("-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----")

        private const val PUTTY_HEADER = "PuTTY-User-Key-File-"

        // Linux: ~/.local/share/Trash is skipped by the hidden folder rule.
        private fun skippedHomeDirectories(platform: Platform): Set<String> = when (platform) {
            is Platform.Desktop.MacOS -> MACOS_SKIPPED_HOME_DIRECTORIES
            is Platform.Desktop.Windows -> WINDOWS_SKIPPED_HOME_DIRECTORIES
            else -> emptySet()
        }

        internal fun isDotEnvName(name: String): Boolean {
            if (name != ".env" && !name.startsWith(".env.")) return false
            val lowercase = name.lowercase()
            return DOTENV_EXCLUDED_SUFFIXES.none { lowercase.endsWith(it) }
        }

        internal fun isSshKeyCandidate(name: String, parentName: String?): Boolean = when {
            name.endsWith(".pub") -> false
            parentName == SSH_DIRECTORY -> name != "config" &&
                SSH_EXCLUDED_PREFIXES.none { name.startsWith(it) }

            else -> name.startsWith("id_") ||
                SSH_KEY_EXTENSIONS.any { name.endsWith(it, ignoreCase = true) }
        }

        private fun hasPrivateKeyHeader(content: ByteArray): Boolean {
            val head = String(
                content,
                0,
                minOf(content.size, HEADER_LENGTH),
                Charsets.ISO_8859_1,
            )
            return head.startsWith(PUTTY_HEADER) || PRIVATE_KEY_HEADER.containsMatchIn(head)
        }

        /**
         * The size of [path] if it is a regular file of at most [maxSize]
         * bytes, without following symbolic links.
         */
        private fun regularFileSizeOrNull(path: Path, maxSize: Int): Long? = runCatchingNonFatal {
            val attrs = Files.readAttributes(
                path,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
            attrs.size().takeIf { attrs.isRegularFile && it <= maxSize }
        }.getOrNull()

        /**
         * The content of the file at [path], whose [size] was just checked,
         * without following symbolic links. `null` if the size changed.
         */
        private fun readRegularFile(path: Path, size: Long): ByteArray? = runCatchingNonFatal {
            Files.newByteChannel(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
                .use { channel -> readExactly(channel, size.toInt()) }
        }.getOrNull()

        private fun readExactly(channel: SeekableByteChannel, size: Int): ByteArray? {
            // One byte over the size tells a file that grew since the check.
            val buffer = ByteBuffer.allocate(size + 1)
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
                // Keep reading.
            }
            val array = buffer.array()
            return if (buffer.position() != size) {
                array.fill(0)
                null
            } else {
                array.copyOf(size)
                    .also { array.fill(0) }
            }
        }
    }
}

/** Moves files to the system Trash. */
interface SecretScanTrash {
    val supported: Boolean

    /** Moves the [paths] to the Trash; returns the ones that were moved. */
    fun moveToTrash(paths: List<Path>): Set<Path>
}

/**
 * Uses `java.awt.Desktop` where it supports the Trash (macOS, Windows), then
 * `gio trash` on Linux.
 */
class SystemSecretScanTrash(
    private val platform: Platform,
) : SecretScanTrash {
    private val desktopSupported: Boolean by lazy {
        runCatchingNonFatal {
            java.awt.Desktop.isDesktopSupported() &&
                java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.MOVE_TO_TRASH)
        }.getOrDefault(false)
    }

    private val gio: String? by lazy {
        if (platform !is Platform.Desktop.Linux) return@lazy null
        System.getenv("PATH")
            .orEmpty()
            .split(File.pathSeparator)
            .filter { it.isNotEmpty() }
            .map { File(it, GIO) }
            .firstOrNull { it.isFile && it.canExecute() }
            ?.absolutePath
    }

    override val supported: Boolean
        get() = desktopSupported || gio != null

    override fun moveToTrash(paths: List<Path>): Set<Path> {
        if (paths.isEmpty()) return emptySet()
        val gio = gio
        return when {
            desktopSupported -> paths
                .filter { path ->
                    runCatchingNonFatal {
                        java.awt.Desktop.getDesktop().moveToTrash(path.toFile())
                    }.getOrDefault(false)
                }
                .toSet()

            gio != null -> {
                gioTrash(gio, paths)
                // A failed call may still have moved some of the files.
                paths
                    .filterNot { Files.exists(it, LinkOption.NOFOLLOW_LINKS) }
                    .toSet()
            }

            else -> emptySet()
        }
    }

    /** Moves all the [paths] with one `gio trash` call. */
    private fun gioTrash(gio: String, paths: List<Path>) {
        try {
            val command = listOf(gio, "trash", "--") + paths.map(Path::toString)
            val process = ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            if (!process.waitFor(GIO_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
            }
        } catch (_: IOException) {
            // Nothing was moved.
        }
    }

    private companion object {
        private const val GIO = "gio"
        private const val GIO_TIMEOUT_SECONDS = 30L
    }
}
