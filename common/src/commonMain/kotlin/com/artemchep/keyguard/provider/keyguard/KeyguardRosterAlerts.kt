package com.artemchep.keyguard.provider.keyguard

import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.DNotification
import com.artemchep.keyguard.common.model.DNotificationChannel
import com.artemchep.keyguard.common.model.DNotificationId
import com.artemchep.keyguard.common.usecase.ShowNotification
import com.artemchep.keyguard.core.store.bitwarden.P2pMembership
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.feature.p2psync.crypto.P2pCrypto
import com.artemchep.keyguard.feature.p2psync.model.Roster
import com.artemchep.keyguard.feature.p2psync.roster.RosterChange
import com.artemchep.keyguard.feature.p2psync.roster.RosterValidator
import com.artemchep.keyguard.feature.p2psync.roster.rosterChanges
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.app_link_alert_added_text
import com.artemchep.keyguard.res.app_link_alert_added_title
import com.artemchep.keyguard.res.app_link_alert_left_text
import com.artemchep.keyguard.res.app_link_alert_removed_by_former_text
import com.artemchep.keyguard.res.app_link_alert_removed_text
import com.artemchep.keyguard.res.app_link_alert_removed_this_by_former_text
import com.artemchep.keyguard.res.app_link_alert_removed_this_text
import com.artemchep.keyguard.res.app_link_alert_removed_title
import com.artemchep.keyguard.res.app_link_alert_removed_with_text

/**
 * Tells the user when a sync brings a change of who is in a sync group that another device
 * made: an added device, or a removed one. Such a change the user did not make may be the
 * first sign that a device was stolen.
 */
fun interface KeyguardRosterAlerts {
    /** The roster of the group of [membership] changed from [before] to [after]. */
    suspend fun onRosterChanged(
        membership: P2pMembership,
        before: Roster,
        after: Roster,
    )

    companion object {
        /** Tells no one. */
        val NONE = KeyguardRosterAlerts { _, _, _ -> }
    }
}

/** Shows a system notification for each change, see [KeyguardRosterAlerts]. */
class KeyguardRosterAlertsImpl(
    private val crypto: P2pCrypto,
    private val context: LeContext,
    private val showNotification: ShowNotification,
) : KeyguardRosterAlerts {
    override suspend fun onRosterChanged(
        membership: P2pMembership,
        before: Roster,
        after: Roster,
    ) {
        val validator = RosterValidator(crypto, membership.groupId)
        val validated = validator.validate(after)
        val self = membership.device
        val changes = rosterChanges(
            before = validator.validate(before),
            after = validated,
            selfId = self.deviceId,
            hasher = crypto::sha256,
        )
        fun labelOf(id: String): String = if (id == self.deviceId) {
            self.label
        } else {
            validated.devices.firstOrNull { it.id == id }?.label ?: id.take(DEVICE_ID_PREFIX)
        }
        changes.forEach { change ->
            val notification = DNotification(
                id = DNotificationId.KEYGUARD_DEVICES,
                tag = "${membership.groupId}:${change.deviceId}",
                title = title(change),
                text = text(change, self.deviceId, ::labelOf),
                channel = DNotificationChannel.KEYGUARD_DEVICES,
            )
            showNotification(notification)
                .attempt()
                .bind()
        }
    }

    private suspend fun title(change: RosterChange): String = when (change) {
        is RosterChange.Added -> textResource(Res.string.app_link_alert_added_title, context)
        is RosterChange.Removed -> textResource(Res.string.app_link_alert_removed_title, context)
    }

    private suspend fun text(
        change: RosterChange,
        selfId: String,
        labelOf: (String) -> String,
    ): String {
        val device = labelOf(change.deviceId)
        return when (change) {
            is RosterChange.Added ->
                textResource(Res.string.app_link_alert_added_text, context, device, labelOf(change.by))

            is RosterChange.Removed -> removedText(change, selfId, device, labelOf)
        }
    }

    /** The text of the removal of [device]. */
    private suspend fun removedText(
        change: RosterChange.Removed,
        selfId: String,
        device: String,
        labelOf: (String) -> String,
    ): String {
        val self = change.deviceId == selfId
        val by = change.by
        return when {
            // Only devices that are not active removed it; none of them is named.
            change.byRemoved && self ->
                textResource(Res.string.app_link_alert_removed_this_by_former_text, context)

            change.byRemoved -> textResource(Res.string.app_link_alert_removed_by_former_text, context, device)
            by == null -> textResource(Res.string.app_link_alert_removed_with_text, context, device)
            by == change.deviceId -> textResource(Res.string.app_link_alert_left_text, context, device)
            self -> textResource(Res.string.app_link_alert_removed_this_text, context, labelOf(by))
            else -> textResource(Res.string.app_link_alert_removed_text, context, device, labelOf(by))
        }
    }

    private companion object {
        /** How much of the id names a device whose record is gone. */
        const val DEVICE_ID_PREFIX = 8
    }
}
