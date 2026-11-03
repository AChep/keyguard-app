package com.artemchep.keyguard.feature.cliagent.history

internal fun buildCliAgentUsageHistoryDescription(
    request: String,
    environment: String?,
    clientName: String?,
    caller: String?,
    errorCode: String?,
): String = buildList {
    add(request)
    environment?.let(::add)
    clientName?.let(::add)
    caller?.let(::add)
    errorCode?.let(::add)
}.joinToString(separator = " • ")

internal fun buildCliAgentUsageHistoryDetails(
    command: String?,
    path: String?,
): String? = listOfNotNull(command, path)
    .takeIf { it.isNotEmpty() }
    ?.joinToString(separator = "\n")
