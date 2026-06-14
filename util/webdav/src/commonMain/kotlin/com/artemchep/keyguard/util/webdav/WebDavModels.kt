package com.artemchep.keyguard.util.webdav

import kotlin.time.Instant

data class WebDavClientConfig(
    val baseUrl: String,
    val authorization: WebDavAuthorization? = null,
    val userAgent: String? = null,
)

sealed interface WebDavAuthorization {
    data class Basic(
        val username: String,
        val password: String,
    ) : WebDavAuthorization

    data class Bearer(
        val token: String,
    ) : WebDavAuthorization

    data class Header(
        val value: String,
    ) : WebDavAuthorization
}

data class WebDavOpenResult(
    val dav: String?,
    val allow: String?,
)

data class WebDavByteRange(
    val offset: Long,
    val length: Long? = null,
) {
    init {
        require(offset >= 0L) {
            "WebDAV read offset must not be negative."
        }
        require(length == null || length > 0L) {
            "WebDAV read length must be positive."
        }
    }
}

enum class WebDavWriteMode {
    Create,
    CreateOrReplace,
}

data class WebDavResource(
    val path: String,
    val isCollection: Boolean,
    val size: Long?,
    val lastModified: Instant?,
    val etag: String?,
)

enum class WebDavOperation {
    Open,
    Options,
    Stat,
    Read,
    Write,
    List,
    Delete,
    Mkcol,
    Move,
    Close,
}

sealed class WebDavException(
    val operation: WebDavOperation,
    val path: String?,
    val statusCode: Int?,
    val retryable: Boolean,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    class NotFound(
        operation: WebDavOperation,
        path: String?,
        statusCode: Int? = null,
        cause: Throwable? = null,
    ) : WebDavException(
        operation = operation,
        path = path,
        statusCode = statusCode,
        retryable = false,
        message = webDavMessage(operation, path, statusCode, "resource was not found"),
        cause = cause,
    )

    class AlreadyExists(
        operation: WebDavOperation,
        path: String?,
        statusCode: Int? = null,
        cause: Throwable? = null,
    ) : WebDavException(
        operation = operation,
        path = path,
        statusCode = statusCode,
        retryable = false,
        message = webDavMessage(operation, path, statusCode, "resource already exists"),
        cause = cause,
    )

    class InvalidRange(
        operation: WebDavOperation,
        path: String?,
        statusCode: Int? = null,
        cause: Throwable? = null,
    ) : WebDavException(
        operation = operation,
        path = path,
        statusCode = statusCode,
        retryable = false,
        message = webDavMessage(operation, path, statusCode, "resource does not contain requested range"),
        cause = cause,
    )

    class AuthenticationFailed(
        operation: WebDavOperation,
        statusCode: Int? = null,
        cause: Throwable? = null,
    ) : WebDavException(
        operation = operation,
        path = null,
        statusCode = statusCode,
        retryable = false,
        message = webDavMessage(operation, null, statusCode, "authentication failed"),
        cause = cause,
    )

    class PermissionDenied(
        operation: WebDavOperation,
        path: String?,
        statusCode: Int? = null,
        cause: Throwable? = null,
    ) : WebDavException(
        operation = operation,
        path = path,
        statusCode = statusCode,
        retryable = false,
        message = webDavMessage(operation, path, statusCode, "permission denied"),
        cause = cause,
    )

    class InsufficientStorage(
        operation: WebDavOperation,
        path: String?,
        statusCode: Int? = null,
        cause: Throwable? = null,
    ) : WebDavException(
        operation = operation,
        path = path,
        statusCode = statusCode,
        retryable = false,
        message = webDavMessage(operation, path, statusCode, "insufficient remote storage"),
        cause = cause,
    )

    class Transient(
        operation: WebDavOperation,
        path: String?,
        statusCode: Int? = null,
        cause: Throwable? = null,
    ) : WebDavException(
        operation = operation,
        path = path,
        statusCode = statusCode,
        retryable = true,
        message = webDavMessage(operation, path, statusCode, "transient failure"),
        cause = cause,
    )

    class Protocol(
        operation: WebDavOperation,
        path: String?,
        statusCode: Int? = null,
        message: String,
        cause: Throwable? = null,
    ) : WebDavException(
        operation = operation,
        path = path,
        statusCode = statusCode,
        retryable = false,
        message = webDavMessage(operation, path, statusCode, message),
        cause = cause,
    )
}

private fun webDavMessage(
    operation: WebDavOperation,
    path: String?,
    statusCode: Int?,
    reason: String,
): String {
    val target = path?.let { " for '$it'" }.orEmpty()
    val status = statusCode?.let { " (HTTP $it)" }.orEmpty()
    return "WebDAV ${operation.name} failed$target$status: $reason."
}
