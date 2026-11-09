package com.artemchep.keyguard.apple.applink

/**
 * The linked devices settings: whether this app is linked to the apps on the user's other
 * devices, this device as they know it, and what it syncs with them. This device and what
 * it syncs are the same linked or not, so the user sets them before the first link. Texts
 * are localized.
 */
data class AppLinkSnapshot(
    /** `false` until the producer emits. */
    val loaded: Boolean,
    /** `true` once this app is linked to another device, or was removed from the link. */
    val linked: Boolean,
    /** `true` if linking a device needs Premium. */
    val premiumRequired: Boolean,
    /** Links a device, scanning or showing a code; not linked only. */
    val canLink: Boolean,
    /** The name of this device. */
    val deviceLabel: String,
    /** The relay: "Keyguard relay", or the host of a custom one. */
    val relayText: String,
    /** Says that the devices may not connect on the local network; `null` if they may. */
    val lanText: String?,
    /** What this device syncs with its linked apps. */
    val categories: List<Category>,
) {
    /** What this device syncs, while [checked]; [enabled] is `false` while a change is being saved. */
    data class Category(
        /** The name of the category, for example `EMAIL_FORWARDERS` or `WORDLISTS`. */
        val key: String,
        val title: String,
        val checked: Boolean,
        val enabled: Boolean,
    )

    companion object {
        val empty = AppLinkSnapshot(
            loaded = false,
            linked = false,
            premiumRequired = false,
            canLink = false,
            deviceLabel = "",
            relayText = "",
            lanText = null,
            categories = emptyList(),
        )
    }
}
