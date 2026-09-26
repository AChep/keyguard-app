package com.artemchep.keyguard.common.model

enum class GpgKeyVersion(val key: String) {
    V4("v4"),
    V6("v6");

    companion object {
        val default get() = V4

        fun getOrDefault(key: String?): GpgKeyVersion =
            entries.firstOrNull { it.key == key } ?: default
    }
}
