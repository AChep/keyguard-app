package com.artemchep.keyguard.provider.bitwarden.api

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

internal data class NotificationsHubMessage(
    val type: NotificationsHubEventType?,
    val rawType: Int,
    val contextId: String?,
)

internal enum class NotificationsHubEventType(
    val code: Int,
) {
    SYNC_CIPHER_UPDATE(0),
    SYNC_CIPHER_CREATE(1),
    SYNC_LOGIN_DELETE(2),
    SYNC_FOLDER_DELETE(3),
    SYNC_CIPHERS(4),
    SYNC_VAULT(5),
    SYNC_ORG_KEYS(6),
    SYNC_FOLDER_CREATE(7),
    SYNC_FOLDER_UPDATE(8),
    SYNC_CIPHER_DELETE(9),
    SYNC_SETTINGS(10),
    LOG_OUT(11),
    SYNC_SEND_CREATE(12),
    SYNC_SEND_UPDATE(13),
    SYNC_SEND_DELETE(14),
    AUTH_REQUEST(15),
    AUTH_REQUEST_RESPONSE(16),
    SYNC_ORGANIZATIONS(17),
    SYNC_ORGANIZATION_STATUS_CHANGED(18),
    SYNC_ORGANIZATION_COLLECTION_SETTING_CHANGED(19),
    NOTIFICATION(20),
    NOTIFICATION_STATUS(21),
    REFRESH_SECURITY_TASKS(22),
    ORGANIZATION_BANK_ACCOUNT_VERIFIED(23),
    PROVIDER_BANK_ACCOUNT_VERIFIED(24),
    POLICY_CHANGED(25),
    AUTO_CONFIRM(26),
    PREMIUM_STATUS_CHANGED(27),
    ;

    companion object {
        private val byCode = entries.associateBy { it.code }

        fun of(code: Int): NotificationsHubEventType? = byCode[code]
    }
}

internal enum class NotificationsHubMessageAction {
    SYNC,
    LOG_OUT,
    IGNORE,
}

internal fun resolveNotificationsHubMessageAction(
    message: Any,
    currentDeviceId: String?,
): NotificationsHubMessageAction {
    val parsed = parseNotificationsHubMessage(message)
        ?: return NotificationsHubMessageAction.SYNC

    if (parsed.isFromCurrentDevice(currentDeviceId)) {
        return NotificationsHubMessageAction.IGNORE
    }

    return when (parsed.type) {
        NotificationsHubEventType.LOG_OUT -> NotificationsHubMessageAction.LOG_OUT
        in ignoredEventTypes -> NotificationsHubMessageAction.IGNORE
        else -> NotificationsHubMessageAction.SYNC
    }
}

internal fun parseNotificationsHubMessage(
    message: Any,
): NotificationsHubMessage? = when (message) {
    is JsonObject -> parseNotificationsHubMessage(message::findFieldIgnoreCase)
    is Map<*, *> -> parseNotificationsHubMessage(message::findFieldIgnoreCase)
    else -> null
}

private fun parseNotificationsHubMessage(
    findField: (String) -> Any?,
): NotificationsHubMessage? {
    val rawType = findField(TYPE_FIELD)
        .toNotificationTypeCodeOrNull()
        ?: return null
    val contextId = findField(CONTEXT_ID_FIELD)
        .toNotificationContextIdOrNull()
    return NotificationsHubMessage(
        type = NotificationsHubEventType.of(rawType),
        rawType = rawType,
        contextId = contextId,
    )
}

private fun NotificationsHubMessage.isFromCurrentDevice(
    currentDeviceId: String?,
): Boolean = contextId != null &&
    currentDeviceId != null &&
    contextId.equals(currentDeviceId, ignoreCase = true)

private val ignoredEventTypes = setOf(
    NotificationsHubEventType.AUTH_REQUEST,
    NotificationsHubEventType.AUTH_REQUEST_RESPONSE,
    NotificationsHubEventType.NOTIFICATION,
    NotificationsHubEventType.NOTIFICATION_STATUS,
)

private fun Map<*, *>.findFieldIgnoreCase(
    key: String,
): Any? = entries
    .firstOrNull { (entryKey, _) ->
        entryKey
            ?.toString()
            ?.equals(key, ignoreCase = true) == true
    }
    ?.value

private fun JsonObject.findFieldIgnoreCase(
    key: String,
): JsonElement? = entries
    .firstOrNull { (entryKey, _) ->
        entryKey.equals(key, ignoreCase = true)
    }
    ?.value

private fun Any?.toNotificationTypeCodeOrNull(): Int? = when (this) {
    is JsonElement -> toNotificationTypeCodeOrNull()
    is Number -> toInt()
    is String -> toIntOrNull(radix = 10)
    else -> null
}

private fun JsonElement?.toNotificationTypeCodeOrNull(): Int? = (this as? JsonPrimitive)
    ?.let { primitive ->
        primitive.intOrNull
            ?: primitive.contentOrNull?.toIntOrNull(radix = 10)
    }

private fun Any?.toNotificationContextIdOrNull(): String? = when (this) {
    is JsonElement -> toNotificationContextIdOrNull()
    is String -> takeIf { it.isNotBlank() }
    else -> null
}

private fun JsonElement?.toNotificationContextIdOrNull(): String? = (this as? JsonPrimitive)
    ?.contentOrNull
    ?.takeIf { it.isNotBlank() }

private const val TYPE_FIELD = "Type"
private const val CONTEXT_ID_FIELD = "ContextId"
