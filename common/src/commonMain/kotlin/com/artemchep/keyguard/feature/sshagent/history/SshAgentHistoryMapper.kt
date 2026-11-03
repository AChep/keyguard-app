package com.artemchep.keyguard.feature.sshagent.history

internal fun buildSshUsageHistoryDescription(
    request: String,
    key: String?,
    fingerprint: String?,
    caller: String?,
): String = buildList {
    add(request)
    key?.let(::add)
    fingerprint?.takeIf { it != key }?.let(::add)
    caller?.let(::add)
}.joinToString(separator = " • ")
