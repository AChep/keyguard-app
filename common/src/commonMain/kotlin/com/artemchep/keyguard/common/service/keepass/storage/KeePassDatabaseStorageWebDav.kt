package com.artemchep.keyguard.common.service.keepass.storage

import com.artemchep.keyguard.common.exception.KeePassDatabaseModifiedExternallyException
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
import kotlinx.io.Source

internal class KeePassDatabaseStorageWebDav(
    private val location: WebDavKeePassFileUrl,
    authorization: WebDavAuthorization?,
    webDavClientFactory: WebDavClientFactory,
) : KeePassDatabaseStorage {
    private val client = webDavClientFactory.create(
        WebDavClientConfig(
            baseUrl = location.baseUrl,
            authorization = authorization,
            noCache = true,
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
        // KtorWebDavClient.write performs a temp-path PUT followed by an
        // atomic server-side MOVE, so the destination is never left torn.
        return try {
            client.write(
                path = location.path,
                mode = when (mode) {
                    KeePassDatabaseWriteMode.Create -> WebDavWriteMode.Create
                    KeePassDatabaseWriteMode.CreateOrReplace -> WebDavWriteMode.CreateOrReplace
                },
                bytes = staged.bytes,
                precondition = expected
                    ?.etag
                    ?.takeUnless { it.isBlank() }
                    ?.let(::WebDavWritePrecondition),
            ).toKeePassDatabaseMetadata()
        } catch (e: WebDavException.PreconditionFailed) {
            throw KeePassDatabaseModifiedExternallyException(
                message = "KeePass database was modified externally while publishing.",
                cause = e,
            )
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
