package com.artemchep.keyguard.common.model

import com.artemchep.keyguard.core.store.bitwarden.P2pNetwork

/** Whether this app is linked to the apps on the user's other devices. */
sealed interface DAppLinkState {
    /** This app is not linked. */
    data object NotLinked : DAppLinkState

    /** This app started a link that no other device joined yet. */
    data object Alone : DAppLinkState

    /** This app is linked to [peers] other active devices. */
    data class Linked(
        val peers: Int,
    ) : DAppLinkState

    /** Another device removed this one from the link. */
    data object Removed : DAppLinkState
}

/**
 * This device as its linked apps know it.
 *
 * @property label the name of this device.
 * @property network how this device reaches the other ones.
 */
data class DAppLinkThisDevice(
    val label: String,
    val network: P2pNetwork,
)

/**
 * What linked apps sync. Each device turns each one on or off for itself: what is off
 * stays on the device, and what the other devices change does not reach it.
 *
 * @property key names the switch of the category in the settings of the device; it never
 *   changes.
 */
enum class DAppLinkSyncCategory(
    val key: String,
) {
    /** The email forwarders of the password generator, with their API keys. */
    EMAIL_FORWARDERS(key = "emailForwarders"),

    /** The wordlists of the password generator, with their words. */
    WORDLISTS(key = "wordlists"),

    /** The URL overrides, except the built-in one, which each device keeps for itself. */
    URL_OVERRIDES(key = "urlOverrides"),

    /** The blocked URIs of autofill. */
    URL_BLOCKS(key = "urlBlocks"),
}
