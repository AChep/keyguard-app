package com.artemchep.keyguard.feature.applink

import androidx.compose.runtime.Immutable
import com.artemchep.keyguard.common.model.DAppLinkSyncCategory
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.ui.ContextItem
import kotlinx.collections.immutable.ImmutableList

/**
 * The linked devices screen. This device, what it syncs, and how it works are the same
 * whether the app is linked or not, so the user sets them up before the first pairing.
 */
@Immutable
data class AppLinkState(
    val content: Content,
    val thisDevice: ThisDevice,
    /** What this device syncs with its linked apps. */
    val categories: ImmutableList<Category>,
    /** `true` if linking a device needs Premium. */
    val premiumRequired: Boolean,
) {
    sealed interface Content {
        /** This app is linked to no other device yet. */
        @Immutable
        data class Unlinked(
            /** Links a device, scanning or showing a code; empty without a purchase or custom relay. */
            val linkActions: ImmutableList<ContextItem>,
        ) : Content

        /** This app is linked to other devices, or was removed from their link. */
        @Immutable
        data object Linked : Content
    }

    /** This device as its linked apps know it; the same for every link. */
    @Immutable
    data class ThisDevice(
        val label: String,
        /** The platform this device runs on, see `keyguardPlatformName`. */
        val platform: String,
        /** The relay: "Keyguard relay", or the host of a custom one. */
        val relayText: String,
        /** Says that the devices may not connect on the local network; `null` if they may. */
        val lanText: String?,
        /** Asks for a new name. */
        val onRename: () -> Unit,
        /** Opens the network settings. */
        val onConnection: () -> Unit,
    )

    /** What this device syncs with its linked apps. */
    @Immutable
    data class Category(
        val category: DAppLinkSyncCategory,
        val title: TextHolder,
        val checked: Boolean,
        /** `null` while a change is being saved. */
        val onCheckedChange: ((Boolean) -> Unit)?,
    )
}
