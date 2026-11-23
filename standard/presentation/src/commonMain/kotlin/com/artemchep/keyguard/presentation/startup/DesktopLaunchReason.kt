package com.artemchep.keyguard.presentation.startup

/** Why this process started. Only a user launch should reveal an existing instance. */
enum class DesktopLaunchReason {
    USER,
    LOGIN,
    BACKGROUND,
    PASSKEY,
    ;

    val activatesExistingInstance: Boolean
        get() = this == USER

    /**
     * A restored session needs its lock timer before any window opens.
     */
    val locksWithoutWindow: Boolean
        get() = this != USER

    /** Keeps the process alive without a window, through the tray, until the user quits it. */
    val keepsProcessAlive: Boolean
        get() = this == LOGIN || this == BACKGROUND

    /** Null keeps the main scene unconstructed until the login preference is available. */
    fun showsMainWindow(startInBackground: Boolean?): Boolean? = when (this) {
        USER -> true
        LOGIN -> startInBackground?.not()
        BACKGROUND, PASSKEY -> false
    }

    companion object {
        const val BACKGROUND_ARGUMENT = "--background"
        const val LOGIN_ARGUMENT = "--launch-at-login"

        fun fromArguments(
            arguments: List<String>,
            passkeyActivation: Boolean = false,
            loginActivation: Boolean = false,
        ): DesktopLaunchReason = when {
            // Passkey COM activation wins: that process must answer Windows.
            passkeyActivation -> PASSKEY
            BACKGROUND_ARGUMENT in arguments -> BACKGROUND
            loginActivation || LOGIN_ARGUMENT in arguments -> LOGIN
            else -> USER
        }
    }
}
