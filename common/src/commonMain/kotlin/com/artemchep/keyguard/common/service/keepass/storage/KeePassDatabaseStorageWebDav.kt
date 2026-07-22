package com.artemchep.keyguard.common.service.keepass.storage

import com.artemchep.keyguard.common.exception.KeePassDatabaseModifiedExternallyException
import com.artemchep.keyguard.common.exception.KeePassFileAlreadyExistsException
import com.artemchep.keyguard.common.service.keepass.StagedDatabase
import com.artemchep.keyguard.common.service.webdav.WebDavClientFactory
import com.artemchep.keyguard.common.service.webdav.WebDavKeePassFileUrl
import com.artemchep.keyguard.common.service.webdav.takeFileResourceOrNull
import com.artemchep.keyguard.util.webdav.WebDavAuthorization
import com.artemchep.keyguard.util.webdav.WebDavClientConfig
import com.artemchep.keyguard.util.webdav.WebDavException
import com.artemchep.keyguard.util.webdav.WebDavResource
import com.artemchep.keyguard.util.webdav.WebDavWriteMode
import com.artemchep.keyguard.util.webdav.WebDavWritePrecondition
import com.artemchep.keyguard.util.webdav.WebDavWriteStrategy
import com.artemchep.keyguard.util.webdav.isRetryableRead
import kotlinx.io.Source

internal class KeePassDatabaseStorageWebDav(
    private val location: WebDavKeePassFileUrl,
    authorization: WebDavAuthorization?,
    webDavClientFactory: WebDavClientFactory,
) : KeePassDatabaseStorage {
    override val decodeReadAttempts: Int = 2

    override fun isRetryableReadFailure(e: Exception): Boolean =
        e is WebDavException && e.isRetryableRead

    private val client = webDavClientFactory.create(
        WebDavClientConfig(
            baseUrl = location.baseUrl,
            authorization = authorization,
            noCache = true,
            writeStrategy = WebDavWriteStrategy.AllowLossy,
        ),
    )
    private var opened = false

    override suspend fun exists(): Boolean = stat() != null

    override suspend fun stat(): KeePassDatabaseMetadata? {
        ensureOpen()
        return client.stat(location.path)
            ?.takeFileResourceOrNull()
            ?.toKeePassDatabaseMetadata()
    }

    override suspend fun read(): Source {
        ensureOpen()
        return client.read(location.path)
    }

    override suspend fun publish(
        mode: KeePassDatabaseWriteMode,
        staged: StagedDatabase,
        expected: KeePassDatabaseMetadata?,
    ): KeePassDatabaseMetadata? {
        ensureOpen()
        // Prefer verified temp upload followed by MOVE. Some KeePass WebDAV
        // servers do not implement MOVE, so this client may fall back to a
        // direct PUT; the staged database is replayable for that second upload.
        return try {
            client.write(
                path = location.path,
                mode = when (mode) {
                    KeePassDatabaseWriteMode.Create -> WebDavWriteMode.Create
                    KeePassDatabaseWriteMode.CreateOrReplace -> WebDavWriteMode.CreateOrReplace
                },
                contentLength = staged.size,
                precondition = expected
                    ?.etag
                    ?.takeUnless { it.isBlank() }
                    ?.let(::WebDavWritePrecondition),
                write = staged::replayTo,
            ).toKeePassDatabaseMetadata()
        } catch (e: WebDavException.PreconditionFailed) {
            throw KeePassDatabaseModifiedExternallyException(
                message = "KeePass database was modified externally while publishing.",
                cause = e,
            )
        } catch (e: WebDavException.AlreadyExists) {
            if (mode == KeePassDatabaseWriteMode.Create) {
                throw KeePassFileAlreadyExistsException(e)
            }
            throw e
        }
    }

    private suspend fun ensureOpen() {
        if (!opened) {
            client.open()
            opened = true
        }
    }
}

private fun WebDavResource.toKeePassDatabaseMetadata(): KeePassDatabaseMetadata =
    KeePassDatabaseMetadata(
        etag = etag,
        lastModified = lastModified,
        size = size,
    )
