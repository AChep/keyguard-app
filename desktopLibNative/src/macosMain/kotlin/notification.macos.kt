import platform.Foundation.NSUUID.Companion.UUID
import platform.UserNotifications.*
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.experimental.ExperimentalNativeApi

@OptIn(ExperimentalNativeApi::class)
@CName("postNotification")
public actual fun postNotification(
    id: Int,
    title: String,
    text: String,
): Boolean {
    dispatch_async(dispatch_get_main_queue()) {
        val center = UNUserNotificationCenter.currentNotificationCenter()
        center.requestAuthorizationWithOptions(
            options = UNAuthorizationOptionAlert or UNAuthorizationOptionBadge or UNAuthorizationOptionSound,
            completionHandler = { granted, error ->
                if (granted) {
                    val content = UNMutableNotificationContent().apply {
                        setTitle(title)
                        setBody(text)
                        setSound(UNNotificationSound.defaultSound())
                        setInterruptionLevel(UNNotificationInterruptionLevel.UNNotificationInterruptionLevelActive)
                    }

                    val requestId = UUID().UUIDString()
                    val request = UNNotificationRequest.requestWithIdentifier(
                        requestId,
                        content,
                        null,
                    )
                    center.addNotificationRequest(request, null)
                }
            },
        )
    }

    return true
}
