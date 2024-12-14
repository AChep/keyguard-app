package com.artemchep.keyguard.platform

fun Platform.hasShareFeature(): Boolean = when (this) {
    is Platform.Mobile -> true
    is Platform.Desktop -> false
}

fun Platform.hasAutofillInlineSuggestions(): Boolean = when (this) {
    is Platform.Mobile.Android -> sdk >= 30 // Android R
    is Platform.Desktop -> false
}
