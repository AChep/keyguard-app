package com.artemchep.keyguard.feature.home.vault.search.engine

internal expect fun searchCompatibilityNormalize(value: String): String

internal expect fun searchDecomposeNormalize(value: String): String

internal expect fun requiresPlatformWordSegmentation(value: String): Boolean

internal expect fun platformWordSegments(value: String): List<String>

internal expect fun forEachPlatformGraphemeCluster(
    value: String,
    block: (startIndex: Int, endIndex: Int) -> Unit,
)
