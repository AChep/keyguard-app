package com.artemchep.keyguard.common.service.gpgagent

fun String.normalizeGpgFingerprint(): String =
    filter(Char::isLetterOrDigit).uppercase()

fun String.chunkedGpgFingerprint(): String =
    chunked(4).joinToString(separator = " ")

fun String.normalizeGpgKeygrip(): String =
    trim().uppercase()
