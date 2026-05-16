package com.artemchep.keyguard.platform

expect object LeLocale {
    val languageTag: String
    val iso3Language: String

    fun displayName(languageTag: String): String
}
