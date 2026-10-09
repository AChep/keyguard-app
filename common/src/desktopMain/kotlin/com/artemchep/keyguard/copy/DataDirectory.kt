package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.service.dirs.DirsService
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.io.Sink
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
        private val APP_NAME = if (isRelease) "keyguard" else "keyguard-dev"
        private val APP_AUTHOR = "ArtemChepurnyi"
    }

    fun data(): IO<String> = ioEffect(Dispatchers.IO) {
        dataBlocking()
    }

    fun dataBlocking(): String = run {
        val appDirs = AppDirsFactory.getInstance()
        appDirs.getUserDataDir(APP_NAME, null, APP_AUTHOR)
    }

    fun config(): IO<String> = ioEffect(Dispatchers.IO) {
        val appDirs = AppDirsFactory.getInstance()
        appDirs.getUserConfigDir(APP_NAME, null, APP_AUTHOR, true)
    }

    fun cache(): IO<String> = ioEffect(Dispatchers.IO) { cacheBlocking() }

    fun cacheBlocking(): String = run {
        val appDirs = AppDirsFactory.getInstance()
        appDirs.getUserCacheDir(APP_NAME, null, APP_AUTHOR)
    }

    fun downloadsBlocking(): String = kotlin.run {
        val appDirs = AppDirsFactory.getInstance()
        appDirs.getUserDownloadsDir(APP_NAME, null, APP_AUTHOR)
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
