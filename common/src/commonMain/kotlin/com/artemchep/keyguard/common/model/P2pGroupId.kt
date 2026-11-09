package com.artemchep.keyguard.common.model

/**
 * A device-to-device sync group on this device. Today the only one is the group of
 * linked apps, see [AppLink].
 */
data class P2pGroupId(
    val id: String,
) {
    companion object {
        /** The id of the group of linked apps. */
        const val APP_LINK_ID: String = "app"

        /** The group of linked apps: there is at most one per app. */
        val AppLink: P2pGroupId = P2pGroupId(APP_LINK_ID)
    }
}
