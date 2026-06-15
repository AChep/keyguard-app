package com.artemchep.keyguard.common.model

enum class AllowScreenshots(
    val key: String,
) {
    DISABLED(key = "disabled"),
    LIMITED(key = "limited"),
    FULL(key = "full"),
}
