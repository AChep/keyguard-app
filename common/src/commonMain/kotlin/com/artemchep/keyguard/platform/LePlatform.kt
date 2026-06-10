package com.artemchep.keyguard.platform

expect val CurrentPlatform: Platform

sealed interface Platform {
    sealed interface Mobile : Platform {
        data class Android(
            val isChromebook: Boolean,
            val isWatch: Boolean,
            val sdk: Int,
        ) : Mobile

        data object Ios : Mobile
    }

    sealed interface Desktop : Platform {
        data class Linux(
            val isFlatpak: Boolean,
        ) : Desktop {
            companion object {
                val native = Linux(
                    isFlatpak = false,
                )
            }
        }

        data object Windows : Desktop
        data object MacOS : Desktop
        data object Other : Desktop
    }
}
