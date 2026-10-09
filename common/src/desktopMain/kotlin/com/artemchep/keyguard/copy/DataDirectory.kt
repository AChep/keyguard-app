package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.service.dirs.DirsService
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.platform.util.isRelease
import com.artemchep.keyguard.util.io.atomic.AtomicDirectoryPermissions
import com.artemchep.keyguard.util.io.atomic.AtomicFilePermissions
import com.artemchep.keyguard.util.io.atomic.AtomicPathComponent
import com.artemchep.keyguard.util.io.atomic.AtomicPublicationPolicy
import com.artemchep.keyguard.util.io.atomic.AtomicWriteOptions
import com.artemchep.keyguard.util.io.atomic.ExistingParentLinkPolicy
import com.artemchep.keyguard.util.io.atomic.ParentDirectoryPolicy
import com.artemchep.keyguard.util.io.atomic.ReplacementAccessPolicy
import com.artemchep.keyguard.util.io.atomic.SyncLevel
import com.artemchep.keyguard.util.io.atomic.SynchronizationPolicy
import com.artemchep.keyguard.util.io.atomic.writeFileAtomicallySuspending
import com.artemchep.keyguard.util.io.toFileUriString
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.io.Sink
import net.harawata.appdirs.AppDirs
import net.harawata.appdirs.AppDirsFactory

// Exports might hold secrets, so other local users must not read them.
private val EXPORT_ATOMIC_WRITE_OPTIONS = AtomicWriteOptions(
    publication = AtomicPublicationPolicy.Replace(
        access = ReplacementAccessPolicy.UseRequestedPermissions(
            permissions = AtomicFilePermissions.OwnerOnly,
        ),
    ),
    parentDirectories = ParentDirectoryPolicy.CreateMissing(
        permissions = AtomicDirectoryPermissions.ProcessDefault,
    ),
    existingParentLinks = ExistingParentLinkPolicy.Reject,
    synchronization = SynchronizationPolicy.Required(
        SyncLevel.FileSynchronized,
    ),
)

class DataDirectory : DirsService {
    companion object {
        internal val APP_NAME = if (isRelease) "keyguard" else "keyguard-dev"
        internal const val APP_AUTHOR = "ArtemChepurnyi"

        private val appDirs: AppDirs by lazy {
            AppDirsFactory.getInstance()
        }
    }

    fun data(): IO<String> = ioEffect(Dispatchers.IO) {
        dataBlocking()
    }

    fun dataBlocking(): String = appDirectory(dataPlatformBlocking())

    /**
     * The platform directory that contains [dataBlocking].
     */
    internal fun dataPlatformBlocking(): String = appDirs.getUserDataDir(null, null, null)

    fun config(): IO<String> = ioEffect(Dispatchers.IO) {
        appDirs.getUserConfigDir(APP_NAME, null, APP_AUTHOR, true)
    }

    fun cache(): IO<String> = ioEffect(Dispatchers.IO) { cacheBlocking() }

    fun cacheBlocking(): String = appDirs.getUserCacheDir(APP_NAME, null, APP_AUTHOR)

    fun downloadsBlocking(): String = appDirectory(downloadsPlatformBlocking())

    /**
     * The platform directory that contains [downloadsBlocking]. On Linux,
     * each call runs `xdg-user-dir`.
     */
    internal fun downloadsPlatformBlocking(): String = appDirs.getUserDownloadsDir(null, null, null)

    /**
     * The app directory below [platformDirectory], in the layout AppDirs uses.
     */
    internal fun appDirectory(platformDirectory: String): String {
        // AppDirs returns an empty or relative XDG variable as is, and such
        // a path would resolve against the working directory.
        require(Path.of(platformDirectory).isAbsolute) {
            "Platform directory '$platformDirectory' is not an absolute path"
        }
        return when (CurrentPlatform) {
            Platform.Desktop.Windows -> Path.of(platformDirectory, APP_AUTHOR, APP_NAME)
            else -> Path.of(platformDirectory, APP_NAME)
        }.toString()
    }

    override fun saveToDownloads(
        fileName: String,
        write: suspend (Sink) -> Unit,
    ): IO<String?> = ioEffect(Dispatchers.IO) {
        val destination = atomicDownloadsDirectory()
            .resolve(AtomicPathComponent.parse(fileName.sanitizedExportFileName()))
        val job = currentCoroutineContext().job
        writeFileAtomicallySuspending(
            destination = destination,
            options = EXPORT_ATOMIC_WRITE_OPTIONS,
            checkCancellation = job::ensureActive,
            write = write,
        ).receipt.requireCleanupComplete()
        destination.path.toFileUriString()
    }
}
