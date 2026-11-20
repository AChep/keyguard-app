package com.artemchep.keyguard.common.model

data class DNotification(
    val id: DNotificationId,
    val tag: String? = null,
    val title: String,
    val text: String? = null,
    val channel: DNotificationChannel,
    val number: Int? = null,
    /**
     * A web page to open on a tap, instead of the app. Not
     * every platform supports it.
     */
    val url: String? = null,
) {

}
