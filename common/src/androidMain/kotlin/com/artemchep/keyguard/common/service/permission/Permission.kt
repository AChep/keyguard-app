package com.artemchep.keyguard.common.service.permission

import android.Manifest
import android.annotation.SuppressLint

@SuppressLint("InlinedApi")
actual enum class Permission(
    val permission: String,
    val minSdk: Int = 0,
    val maxSdk: Int = Int.MAX_VALUE,
) {
    POST_NOTIFICATIONS(Manifest.permission.POST_NOTIFICATIONS, minSdk = 33),
    WRITE_EXTERNAL_STORAGE(Manifest.permission.WRITE_EXTERNAL_STORAGE, maxSdk = 29),
}
