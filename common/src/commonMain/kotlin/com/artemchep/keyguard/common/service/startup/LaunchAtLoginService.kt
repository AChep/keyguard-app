package com.artemchep.keyguard.common.service.startup

import kotlinx.coroutines.flow.StateFlow

enum class LaunchAtLoginStatus {
    ENABLED,
    DISABLED,
    REQUIRES_APPROVAL,
    DISABLED_BY_USER,
    UNAVAILABLE,
    STALE,
    UNKNOWN,
    ENABLED_BY_POLICY,
    DISABLED_BY_POLICY,
    ;

    val canChange: Boolean
        get() = when (this) {
            UNAVAILABLE, DISABLED_BY_USER, ENABLED_BY_POLICY, DISABLED_BY_POLICY -> false
            else -> true
        }

    /** The app is registered, even if the OS still waits for the user's approval. */
    val isEnabled: Boolean
        get() = this == ENABLED || this == REQUIRES_APPROVAL || this == ENABLED_BY_POLICY

    /** Only the OS settings can resolve this status. */
    val opensSystemSettings: Boolean
        get() = this == REQUIRES_APPROVAL || this == DISABLED_BY_USER
}

data class LaunchAtLoginState(
    /** `null` until the first successful read. A later failed read keeps the last status. */
    val status: LaunchAtLoginStatus? = null,
    val busy: Boolean = false,
    val failed: Boolean = false,
) {
    /**
     * Settings show the option once it is read and this install can launch at login.
     * A failed first read stays visible, so its error and Retry show.
     */
    val isShown: Boolean
        get() = status != LaunchAtLoginStatus.UNAVAILABLE && (status != null || failed)
}

/** OS registration is the source of truth; a preference is never proof of registration. */
interface LaunchAtLoginService {
    val state: StateFlow<LaunchAtLoginState>

    /** Re-reads the OS status without reporting [LaunchAtLoginState.busy]. Never changes the registration. */
    suspend fun refresh()

    /**
     * Like [refresh], then re-registers a [LaunchAtLoginStatus.STALE] entry (one that points at a
     * missing executable). Startup only. A failure keeps the stale status and sets
     * [LaunchAtLoginState.failed].
     */
    suspend fun repairStale()

    /** Like [refresh], but only while someone collects [state]; for app foreground events. */
    suspend fun refreshIfObserved()

    suspend fun setEnabled(enabled: Boolean)

    suspend fun openSettings()

    /**
     * Disables a registered or stale entry before data is erased, without repairing it. Returns
     * `false` only if the removal failed; an unreadable or unchangeable entry returns `true`.
     */
    suspend fun removeForClearData(): Boolean
}

/** Platform access to the OS login item registration. Calls may block. */
interface LaunchAtLoginRegistration {
    suspend fun status(): LaunchAtLoginStatus

    suspend fun setEnabled(enabled: Boolean): LaunchAtLoginStatus

    suspend fun openSettings()
}
