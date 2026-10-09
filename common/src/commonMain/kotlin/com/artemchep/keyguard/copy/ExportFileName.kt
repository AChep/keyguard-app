package com.artemchep.keyguard.copy

/**
 * Makes a caller-supplied export name safe to use as a single file name.
 */
internal fun String.sanitizedExportFileName(): String =
    substringAfterLast('/')
        .substringAfterLast('\\')
        .map { character ->
            when (character) {
                ':', '\u0000' -> '_'
                else -> character
            }
        }
        .joinToString(separator = "")
        .takeIf { fileName ->
            fileName.isNotBlank() &&
                fileName != "." &&
                fileName != ".."
        }
        ?: "export"
