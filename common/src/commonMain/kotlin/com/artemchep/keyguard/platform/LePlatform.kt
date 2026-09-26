package com.artemchep.keyguard.platform

expect val CurrentPlatform: Platform

sealed interface Platform {
    sealed interface Apple

    sealed interface Mobile : Platform {
        data class Android(
            val isChromebook: Boolean,
            val isWatch: Boolean,
            val sdk: Int,
        ) : Mobile

        sealed interface Ios : Mobile {
            data object Jvm : Ios
            data object Native : Ios, Apple
        }
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
        sealed interface MacOS : Desktop {
            data object Jvm : MacOS
            data object Native : MacOS, Apple
        }

        data object Other : Desktop
    }
}
