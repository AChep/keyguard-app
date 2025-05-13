package com.artemchep.keyguard.common.util

const val PROTOCOL_ANDROID_APP = "androidapp://"
const val PROTOCOL_IOS_APP = "iosapp://"

val REGEX_ANDROID_APP = "androidapp://([a-zA-Z]+[a-zA-Z0-9_]*)(\\.[a-zA-Z]+[a-zA-Z0-9_]*)*".toRegex()
