package com.artemchep.keyguard.util.dns

private const val MAX_LABEL_LENGTH = 63
private const val MAX_NAME_LENGTH = 253

private val LABEL_REGEX = "[a-z0-9_-]{1,$MAX_LABEL_LENGTH}".toRegex()

/**
 * Validates [name] as an ASCII DNS name and returns it lowercased without the
 * trailing root dot. Labels may only contain letters, digits, hyphens and
 * underscores, which keeps the value free of any resolver escape syntax.
 */
internal fun requireValidDnsName(name: String): String {
    val normalized = name
        .removeSuffix(".")
        .lowercase()
    require(normalized.isNotEmpty() && normalized.length <= MAX_NAME_LENGTH) {
        "DNS name must be 1..$MAX_NAME_LENGTH characters long."
    }
    require(normalized.split('.').all(LABEL_REGEX::matches)) {
        "DNS name contains an invalid label."
    }
    return normalized
}
