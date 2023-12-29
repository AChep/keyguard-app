package com.artemchep.keyguard.provider.bitwarden.api.builder

import com.artemchep.keyguard.provider.bitwarden.ServerEnv

/**
 * @author Artem Chepurnyi
 */
@JvmInline
value class ServerEnvNotifications @Deprecated("Use the [ServerEnv.notifications] property instead.") constructor(
    private val url: String,
) {
    val hub get() = url + "hub"
}

@Suppress("DEPRECATION")
val ServerEnv.notifications
    get() = ServerEnvNotifications(url = buildNotificationsUrl())
