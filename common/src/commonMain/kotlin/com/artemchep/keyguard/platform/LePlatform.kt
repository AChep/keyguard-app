package com.artemchep.keyguard.platform

expect val CurrentPlatform: Platform

sealed interface Platform {
    sealed interface Mobile : Platform {
        data class Android(
            val isChromebook: Boolean,
        ) : Mobile
    }

    sealed interface Desktop : Platform {
        data object Linux : Desktop
        data object Windows : Desktop
        data object MacOS : Desktop
        data object Other : Desktop
    }
}
