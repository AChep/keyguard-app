package com.artemchep.keyguard.common.service.file

import com.artemchep.keyguard.util.io.LocalPath
import com.artemchep.keyguard.util.io.atomic.AtomicFileDestination
import com.artemchep.keyguard.util.io.atomic.AtomicFilePermissions
import com.artemchep.keyguard.util.io.atomic.AtomicPathComponent
import com.artemchep.keyguard.util.io.atomic.AtomicPublicationPolicy
import com.artemchep.keyguard.util.io.atomic.AtomicRelativePath
import com.artemchep.keyguard.util.io.atomic.AtomicWriteOptions
import com.artemchep.keyguard.util.io.atomic.ExistingParentLinkPolicy
import com.artemchep.keyguard.util.io.atomic.ParentDirectoryPolicy
import com.artemchep.keyguard.util.io.atomic.ReplacementAccessPolicy
import com.artemchep.keyguard.util.io.atomic.SyncLevel
import com.artemchep.keyguard.util.io.atomic.SynchronizationPolicy
import com.artemchep.keyguard.util.io.atomic.writeFileAtomically
import com.artemchep.keyguard.util.io.lastModifiedMillis
import com.artemchep.keyguard.util.io.toFileUriString
import com.artemchep.keyguard.util.io.toLocalPathFromFileUriOrNull
import kotlinx.io.IOException
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.time.Instant

class FileServiceImpl : FileService {
    override fun exists(
        uri: String,
    ): Boolean = SystemFileSystem
        .exists(
            path = uri
                .toFilePath(action = FileAccessAction.Read),
        )

    override fun metadata(
        uri: String,
        accessToken: FileAccessToken?,
    ): FileMetadata? = runCatching {
        val localPath = uri.toLocalPathFromFileUriOrNull()
            ?: return@runCatching null
        val metadata = SystemFileSystem.metadataOrNull(
            path = Path(localPath.value),
        ) ?: return@runCatching null
        FileMetadata(
            lastModified = localPath.lastModifiedMillis()
                ?.let(Instant::fromEpochMilliseconds),
            size = metadata.size,
        )
    }.getOrNull()

    override fun readFromFile(
        uri: String,
    ): Source = SystemFileSystem
        .source(
            path = uri
                .toFilePath(action = FileAccessAction.Read),
        )
        .buffered()

    override fun writeToFile(
        uri: String,
    ): Sink = SystemFileSystem
        .sink(
            path = uri
                .toFilePath(action = FileAccessAction.Write),
        )
        .buffered()

    override fun atomicWriteToFile(
        uri: String,
        accessToken: FileAccessToken?,
        write: (Sink) -> Unit,
    ): AtomicFileWriteOutcome {
        val atomicDestination = uri.toAtomicFileDestinationOrNull()
            ?: return AtomicFileWriteOutcome.Unsupported
        val result = writeFileAtomically(
            destination = atomicDestination,
            options = AtomicWriteOptions(
                publication = AtomicPublicationPolicy.Replace(
                    access = ReplacementAccessPolicy.PreserveExistingBasicPermissions(
                        ifDestinationMissing = AtomicFilePermissions.ProcessDefault,
                    ),
                ),
                parentDirectories = ParentDirectoryPolicy.RequireExisting,
                existingParentLinks = ExistingParentLinkPolicy.Reject,
                synchronization = SynchronizationPolicy.Prefer(
                    preferred = SyncLevel.FileAndNamespaceSynchronized,
                    minimum = SyncLevel.FileSynchronized,
                ),
            ),
        ) { sink ->
            write(sink)
        }
        return AtomicFileWriteOutcome.Published(result.receipt)
    }

    override fun delete(uri: String): Boolean = runCatching {
        SystemFileSystem
            .delete(
                path = uri
                    .toFilePath(action = FileAccessAction.Write),
                mustExist = false,
            )
        true
    }.getOrDefault(false)
}

private fun String.toAtomicFileDestinationOrNull(): AtomicFileDestination? {
    val destinationPath = toLocalPathFromFileUriOrNull()
        ?.let { localPath -> Path(localPath.value) }
        ?.takeIf { path -> path.isAbsolute }
    val destinationParent = destinationPath?.parent
    val destinationName = destinationPath
        ?.name
        ?.let { name ->
            runCatching {
                AtomicPathComponent.parse(name)
            }.getOrNull()
        }
    return if (destinationParent != null && destinationName != null) {
        AtomicFileDestination(
            root = LocalPath(destinationParent.toString()),
            relativePath = AtomicRelativePath.fromComponents(
                destinationName,
            ),
        )
    } else {
        null
    }
}

private enum class FileAccessAction(
    val errorVerb: String,
) {
    Read(errorVerb = "read from"),
    Write(errorVerb = "write to"),
}

private fun String.toFilePath(action: FileAccessAction): Path =
    toLocalPathFromFileUriOrNull()
        ?.let { Path(it.value) }
        ?: run {
            val msg = "Unsupported URI protocol, could not ${action.errorVerb} '$this'."
            throw IllegalStateException(msg)
        }
