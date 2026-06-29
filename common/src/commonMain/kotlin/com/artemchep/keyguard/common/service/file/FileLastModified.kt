package com.artemchep.keyguard.common.service.file

/**
 * Returns the last-modified time of the local file at [path] (a filesystem
 * path, not a URI) as Unix epoch milliseconds, or `null` if it cannot be
 * determined.
 *
 * kotlinx-io's file metadata does not expose a modification time, so each
 * platform reads it through its own native file API.
 */
internal expect fun fileLastModifiedMillis(path: String): Long?
