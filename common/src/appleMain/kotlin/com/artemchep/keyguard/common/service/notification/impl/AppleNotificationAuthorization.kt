package com.artemchep.keyguard.common.service.notification.impl

import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSError
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNAuthorizationStatus
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusEphemeral
import platform.UserNotifications.UNAuthorizationStatusNotDetermined
import platform.UserNotifications.UNAuthorizationStatusProvisional
import platform.UserNotifications.UNUserNotificationCenter

/**
 * Notification authorization, shared by the poster and the permission service.
 */
internal object AppleNotificationAuthorization {
    // Requesting without `Badge` silently drops the badge of a posted notification.
    private val options: ULong = UNAuthorizationOptionAlert or
            UNAuthorizationOptionBadge or
            UNAuthorizationOptionSound

    suspend fun status(): UNAuthorizationStatus = suspendCancellableCoroutine { continuation ->
        UNUserNotificationCenter.currentNotificationCenter()
            .getNotificationSettingsWithCompletionHandler { settings ->
                continuation.resume(
                    settings?.authorizationStatus ?: UNAuthorizationStatusNotDetermined,
                )
            }
    }

    /**
     * Shows the system alert only while the status is `notDetermined`; otherwise
     * resolves with the standing answer.
     */
    suspend fun request(): Boolean = suspendCancellableCoroutine { continuation ->
        UNUserNotificationCenter.currentNotificationCenter()
            .requestAuthorizationWithOptions(
                options = options,
            ) { granted: Boolean, _: NSError? ->
                continuation.resume(granted)
            }
    }

    fun isGranted(status: UNAuthorizationStatus): Boolean = when (status) {
        UNAuthorizationStatusAuthorized,
        UNAuthorizationStatusProvisional,
        UNAuthorizationStatusEphemeral,
        -> true

        else -> false
    }

    suspend fun ensureGranted(): Boolean {
        val status = status()
        if (status == UNAuthorizationStatusNotDetermined) {
            return request()
        }
        return isGranted(status)
    }
}
