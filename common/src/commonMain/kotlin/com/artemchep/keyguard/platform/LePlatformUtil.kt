package com.artemchep.keyguard.platform

fun Platform.hasShareFeature(): Boolean = when (this) {
    is Platform.Mobile -> true
    is Platform.Desktop -> false
}
