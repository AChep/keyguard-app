package com.artemchep.keyguard.common.service.notification.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.DNotification
import com.artemchep.keyguard.common.model.DNotificationKey
import com.artemchep.keyguard.common.service.notification.NotificationRepository
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotification
import platform.UserNotifications.UNNotificationPresentationOptionBanner
import platform.UserNotifications.UNNotificationPresentationOptionList
import platform.UserNotifications.UNNotificationPresentationOptions
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNUserNotificationCenter
import platform.UserNotifications.UNUserNotificationCenterDelegateProtocol
import platform.darwin.NSObject

/**
 * Watchtower alerts on macOS / iOS, via `UNUserNotificationCenter`.
 */
class NotificationRepositoryApple : NotificationRepository {
    // The notification center holds its delegate weakly.
    private val foregroundPresenter = ForegroundPresenter()

    override fun post(
        notification: DNotification,
    ): IO<DNotificationKey?> = ioEffect {
        // No native screen asks for authorization, so the first alert does.
        // A denial is a normal outcome, not a sync failure.
        if (!AppleNotificationAuthorization.ensureGranted()) {
            return@ioEffect null
        }

        val center = UNUserNotificationCenter.currentNotificationCenter()
        // Without a delegate the system drops notifications while the app is frontmost.
        if (center.delegate == null) {
            center.delegate = foregroundPresenter
        }

        val content = UNMutableNotificationContent().apply {
            setTitle(notification.title)
            notification.text
                ?.takeIf { it.isNotBlank() }
                ?.let { setBody(it) }
            notification.number
                ?.coerceAtLeast(0)
                ?.let { setBadge(NSNumber(int = it)) }
            setSound(UNNotificationSound.defaultSound())
        }
        val key = DNotificationKey(
            id = notification.id.value,
            tag = notification.tag,
        )
        val request = UNNotificationRequest.requestWithIdentifier(
            identifier = key.identifier(),
            content = content,
            // A null trigger delivers immediately.
            trigger = null,
        )
        suspendCancellableCoroutine { continuation ->
            center.addNotificationRequest(request) { error: NSError? ->
                if (error != null) {
                    val message = "Failed to post a notification: " +
                            error.localizedDescription
                    continuation.resumeWithException(IllegalStateException(message))
                } else {
                    continuation.resume(Unit)
                }
            }
        }
        key
    }

    override fun delete(
        key: DNotificationKey,
    ): IO<Unit> = ioEffect {
        val center = UNUserNotificationCenter.currentNotificationCenter()
        val identifiers = listOf(key.identifier())
        center.removePendingNotificationRequestsWithIdentifiers(identifiers)
        center.removeDeliveredNotificationsWithIdentifiers(identifiers)
    }
}

// `UserNotifications` addresses a notification by a single string, so the shared
// `(id, tag)` key is folded into one.
private fun DNotificationKey.identifier(): String = tag
    ?.let { "$it#$id" }
    ?: id.toString()

private class ForegroundPresenter : NSObject(), UNUserNotificationCenterDelegateProtocol {
    override fun userNotificationCenter(
        center: UNUserNotificationCenter,
        willPresentNotification: UNNotification,
        withCompletionHandler: (UNNotificationPresentationOptions) -> Unit,
    ) {
        withCompletionHandler(
            UNNotificationPresentationOptionBanner or UNNotificationPresentationOptionList,
        )
    }
}
