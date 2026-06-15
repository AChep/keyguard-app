package com.artemchep.keyguard.common.service.backup

import com.artemchep.keyguard.util.webdav.KtorWebDavClient
import com.artemchep.keyguard.util.webdav.WebDavAuthorization
import com.artemchep.keyguard.util.webdav.WebDavByteRange
import com.artemchep.keyguard.util.webdav.WebDavClient
import com.artemchep.keyguard.util.webdav.WebDavClientConfig
import com.artemchep.keyguard.util.webdav.WebDavException
import com.artemchep.keyguard.util.webdav.WebDavOperation
import com.artemchep.keyguard.util.webdav.WebDavResource
import com.artemchep.keyguard.util.webdav.WebDavWriteMode
import io.ktor.client.HttpClient
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.buffered

class WebDavBackupObjectStore(
    private val client: WebDavClient,
) : BackupObjectStore {
    override val capabilities: BackupObjectStoreCapabilities = BackupObjectStoreCapabilities(
        atomicWholeObjectWrite = true,
        atomicReplace = false,
        rangeRead = true,
        strongReadAfterWrite = true,
        strongListAfterWrite = true,
    )

    override suspend fun stat(
        key: BackupObjectKey,
    ): BackupObjectInfo? = translate(
        operation = BackupObjectStoreOperation.Stat,
        key = key,
    ) {
        client.stat(key.value)
            ?.takeUnless { resource -> resource.isCollection }
            ?.toBackupObjectInfo(key)
    }

    override suspend fun read(
        key: BackupObjectKey,
        range: BackupByteRange?,
    ): Source = translate(
        operation = BackupObjectStoreOperation.Read,
        key = key,
        range = range,
    ) {
        client.read(
            path = key.value,
            range = range?.toWebDavByteRange(),
        ).translateReadSource(
            operation = BackupObjectStoreOperation.Read,
            key = key,
            range = range,
        )
    }

    override suspend fun write(
        key: BackupObjectKey,
        mode: BackupWriteMode,
        write: suspend (Sink) -> Unit,
    ): BackupObjectInfo = translate(
        operation = BackupObjectStoreOperation.Write,
        key = key,
    ) {
        client.write(
            path = key.value,
            mode = mode.toWebDavWriteMode(),
            contentLength = null,
            write = write,
        ).toBackupObjectInfo(key)
    }

    override suspend fun list(
        prefix: BackupObjectKeyPrefix,
        cursor: BackupListCursor?,
    ): BackupObjectListPage = translate(
        operation = BackupObjectStoreOperation.List,
        key = null,
    ) {
        BackupObjectListPage(
            items = client
                .list(prefix.value)
                .filterNot { resource -> resource.isCollection }
                .map { resource ->
                    resource.toBackupObjectInfo(
                        key = BackupObjectKey(resource.path),
                    )
                }
                .sortedBy { info -> info.key.value },
        )
    }

    override suspend fun delete(
        key: BackupObjectKey,
    ) {
        translate(
            operation = BackupObjectStoreOperation.Delete,
            key = key,
        ) {
            client.delete(key.value)
        }
    }

    override suspend fun close() {
        translate(
            operation = BackupObjectStoreOperation.Close,
            key = null,
        ) {
            client.close()
        }
    }

    private inline fun <T> translate(
        operation: BackupObjectStoreOperation,
        key: BackupObjectKey?,
        range: BackupByteRange? = null,
        block: () -> T,
    ): T = try {
        block()
    } catch (e: WebDavException.NotFound) {
        throw key
            ?.let {
                BackupObjectStoreException.NotFound(
                    key = it,
                    operation = operation,
                    cause = e,
                )
            }
            ?: BackupObjectStoreException.PermissionDenied(
                operation = operation,
                cause = e,
            )
    } catch (e: WebDavException.AlreadyExists) {
        throw key
            ?.let {
                BackupObjectStoreException.AlreadyExists(
                    key = it,
                    cause = e,
                )
            }
            ?: BackupObjectStoreException.PermissionDenied(
                operation = operation,
                cause = e,
            )
    } catch (e: WebDavException.InvalidRange) {
        throw BackupObjectStoreException.InvalidRange(
            key = requireNotNull(key) {
                "Invalid range errors must be tied to a backup object key."
            },
            range = requireNotNull(range) {
                "Invalid range errors must include the requested range."
            },
            cause = e,
        )
    } catch (e: WebDavException.AuthenticationFailed) {
        throw BackupObjectStoreException.AuthenticationFailed(
            operation = operation,
            cause = e,
        )
    } catch (e: WebDavException.Transient) {
        throw BackupObjectStoreException.Transient(
            operation = operation,
            key = key,
            cause = e,
        )
    } catch (e: WebDavException) {
        if (e.retryable) {
            throw BackupObjectStoreException.Transient(
                operation = operation,
                key = key,
                cause = e,
            )
        }
        throw BackupObjectStoreException.PermissionDenied(
            operation = operation,
            key = key,
            cause = e,
        )
    }

    private fun WebDavResource.toBackupObjectInfo(
        key: BackupObjectKey,
    ): BackupObjectInfo = BackupObjectInfo(
        key = key,
        size = size,
        updatedAt = lastModified,
    )

    private fun BackupByteRange.toWebDavByteRange(): WebDavByteRange = WebDavByteRange(
        offset = offset,
        length = length,
    )

    private fun BackupWriteMode.toWebDavWriteMode(): WebDavWriteMode = when (this) {
        BackupWriteMode.Create -> WebDavWriteMode.Create
        BackupWriteMode.CreateOrReplace -> WebDavWriteMode.CreateOrReplace
    }

    private fun Source.translateReadSource(
        operation: BackupObjectStoreOperation,
        key: BackupObjectKey,
        range: BackupByteRange?,
    ): Source {
        val upstream = this
        return object : RawSource {
            override fun readAtMostTo(
                sink: Buffer,
                byteCount: Long,
            ): Long = translate(
                operation = operation,
                key = key,
                range = range,
            ) {
                upstream.readAtMostTo(sink, byteCount)
            }

            override fun close() {
                translate(
                    operation = operation,
                    key = key,
                    range = range,
                ) {
                    upstream.close()
                }
            }
        }.buffered()
    }
}

class WebDavBackupObjectStoreFactory(
    private val httpClient: HttpClient,
    private val authorization: WebDavAuthorization? = null,
    private val userAgent: String? = null,
) : BackupObjectStoreFactory {
    override suspend fun open(
        store: BackupStoreConfig,
    ): BackupObjectStore {
        val webDavStore = requireNotNull(store as? BackupStoreConfig.WebDav) {
            "Backup WebDAV store configuration is required."
        }
        val repositoryPath = requireNotNull(webDavStore.url) {
            "Backup WebDAV repository URL is not configured."
        }
        val client = KtorWebDavClient(
            httpClient = httpClient,
            config = WebDavClientConfig(
                baseUrl = repositoryPath,
                authorization = authorization ?: webDavStore.toWebDavAuthorization(),
                userAgent = userAgent,
            ),
        )
        try {
            client.open()
            return WebDavBackupObjectStore(client)
        } catch (e: WebDavException) {
            throw e.toBackupOpenException()
        }
    }

    private fun WebDavException.toBackupOpenException(): BackupObjectStoreException = when (this) {
        is WebDavException.AuthenticationFailed -> BackupObjectStoreException.AuthenticationFailed(
            operation = BackupObjectStoreOperation.Open,
            cause = this,
        )
        is WebDavException.Transient -> BackupObjectStoreException.Transient(
            operation = BackupObjectStoreOperation.Open,
            cause = this,
        )
        else -> BackupObjectStoreException.PermissionDenied(
            operation = BackupObjectStoreOperation.Open,
            cause = this,
        )
    }
}

internal fun BackupStoreConfig.WebDav.toWebDavAuthorization(): WebDavAuthorization? {
    val username = username
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: return null
    return WebDavAuthorization.Basic(
        username = username,
        password = password?.value.orEmpty(),
    )
}
