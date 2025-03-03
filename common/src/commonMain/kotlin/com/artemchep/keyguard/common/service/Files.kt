package com.artemchep.keyguard.common.service

enum class Files(
    val filename: String,
) {
    KEY("master_key"),
    FINGERPRINT("fingerprint"),
    DEVICE_ID("device_id"),
    UI_STATE("ui_state"),
    WINDOW_STATE("window_state"),
    SESSION_METADATA("session_metadata"),
    SETTINGS("settings"),
    BREACHES("breaches"),
    REVIEW("review"),
    NOTIFICATIONS("notifications"),
}
