package com.artemchep.keyguard.feature.keyguardsync.devices

import com.artemchep.keyguard.common.model.P2pGroupId
import com.artemchep.keyguard.feature.navigation.Route
import org.jetbrains.compose.resources.StringResource

/**
 * The sync group a devices screen shows, such as the group of linked apps.
 *
 * @property addDeviceRoute where adding a device starts; this device scans the code of the
 *   other one if `scan`, or shows its own.
 */
data class KeyguardDevicesTarget(
    val groupId: P2pGroupId,
    val addDeviceRoute: (scan: Boolean) -> Route,
    val texts: KeyguardDevicesTexts,
)

/** The texts of a devices screen that depend on what the group syncs. */
data class KeyguardDevicesTexts(
    val addDeviceTitle: StringResource,
    /** Takes the name of the device. */
    val removeConfirmText: StringResource,
    val leaveTitle: StringResource,
    val leaveText: StringResource,
    val leaveConfirmText: StringResource,
)
