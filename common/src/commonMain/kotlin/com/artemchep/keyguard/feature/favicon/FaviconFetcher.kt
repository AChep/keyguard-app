package com.artemchep.keyguard.feature.favicon

data class FaviconUrl(
    val serverId: String? = null,
    val url: String,
)

data class AppIconUrl(
    val packageName: String,
)
