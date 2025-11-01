package com.artemchep.keyguard.common.model

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Wifi
import com.artemchep.keyguard.feature.auth.common.util.REGEX_EMAIL
import com.artemchep.keyguard.feature.auth.common.util.REGEX_PHONE_NUMBER

enum class UsernameVariation {
    USERNAME,
    EMAIL,
    PHONE,
    SSID,
    ;

    companion object {
        val default get() = USERNAME

        fun of(username: String) = when {
            REGEX_EMAIL.matches(username) -> EMAIL
            REGEX_PHONE_NUMBER.matches(username) -> PHONE
            else -> default
        }
    }
}

val UsernameVariation.icon
    get() = when (this) {
        UsernameVariation.EMAIL -> Icons.Outlined.Email
        UsernameVariation.PHONE -> Icons.Outlined.Call
        UsernameVariation.SSID -> Icons.Outlined.Wifi
        UsernameVariation.USERNAME -> Icons.Outlined.AlternateEmail
    }
