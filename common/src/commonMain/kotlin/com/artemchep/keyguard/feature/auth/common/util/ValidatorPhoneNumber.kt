package com.artemchep.keyguard.feature.auth.common.util

val REGEX_PHONE_NUMBER =
    "^(\\+\\d{1,2}\\s?)?1?\\-?\\.?\\s?\\(?\\d{3}\\)?[\\s.-]?\\d{3}[\\s.-]?\\d{4}\$".toRegex()
