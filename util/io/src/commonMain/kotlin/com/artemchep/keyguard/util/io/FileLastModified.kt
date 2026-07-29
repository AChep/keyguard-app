package com.artemchep.keyguard.util.io

/**
 * Returns the last-modified time of the local file as Unix epoch
 * milliseconds, or `null` if it cannot be determined.
 *
 * kotlinx-io's file metadata does not expose a modification time, so each
 * platform reads it through its own native file API.
 */
expect fun LocalPath.lastModifiedMillis(): Long?
