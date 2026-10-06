package com.artemchep.keyguard.apple.settings

data class AppInformationSnapshot(
    val loaded: Boolean = false,
    val buildDate: String = "",
    val buildRef: String? = null,
    val buildRefUrl: String? = null,
    val changelogText: String? = null,
    val changelogUrl: String? = null,
) {
    companion object {
        val empty = AppInformationSnapshot()
    }
}
