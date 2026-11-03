package com.artemchep.keyguard.common.service.imports

import com.artemchep.keyguard.common.model.Password
import kotlinx.io.Source

/**
 * Parses the export files of other password managers into an [ImportPlan].
 *
 * The service is the single boundary for an untrusted file: every format is
 * parsed behind it, and nothing a file contains can escape it as anything but
 * an [ImportResult.Failure].
 */
interface ImportService {
    /**
     * The supported formats, in the order the import screen lists them.
     */
    val formats: List<ImportFormat>

    /**
     * Opens [input] as [format], without reading it yet: the source holds
     * a single snapshot, read by every [parse][OpenedImportSource.parse].
     * [password] unlocks a password-protected file; without one such a file
     * fails to parse with [ImportError.PasswordRequired].
     *
     * The source's parse throws only for a bug of the importer: a plan with
     * attachments it could not read. Its extraction streams the refs in a
     * single read of the file, and throws if the file can no longer be read
     * or misses one of them.
     */
    fun open(
        format: ImportFormatId,
        input: ImportInput,
        password: Password? = null,
    ): OpenedImportSource
}

/**
 * The file being imported. [open] may be called more than once, each call
 * returning a fresh source the caller closes.
 */
interface ImportInput {
    /**
     * The file name, if known. Display-only.
     */
    val name: String?

    fun open(): Source
}
