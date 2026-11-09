package com.artemchep.keyguard.common.model

import kotlin.time.Instant

/**
 * A device in a sync group, such as the one of linked apps.
 *
 * @property fingerprint the first 16 hex digits of the SHA-256 of the device's Noise
 *   public key, in groups of four. It is the same on every device of the group.
 * @property addedAt when the inviter admitted the device.
 * @property addedBy the id of the device that admitted it, or `null` for the founder.
 * @property isThisDevice `true` for the device the app runs on.
 * @property revoked `true` once the device is no longer an active device of the group.
 * @property removedByRemoved `true` if only devices that are no longer active removed it:
 *   a removed device that still knows the master password, or a device removed later.
 */
data class DKeyguardDevice(
    val id: String,
    val label: String,
    val platform: String,
    val fingerprint: String,
    val addedAt: Instant,
    val addedBy: String? = null,
    val isThisDevice: Boolean,
    val revoked: Boolean,
    val removedByRemoved: Boolean = false,
)
