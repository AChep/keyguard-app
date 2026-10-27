package com.artemchep.keyguard.feature.cli.ui

data class CliSettingsState(
    val enabled: Boolean,
    /** `null` if this build serves no command of the CLI. */
    val path: String?,
    /** How to get the CLI's own help. */
    val usage: String,
    val busy: Boolean,
    val failed: Boolean,
    /** `null` while an operation is running. */
    val onEnabledChange: ((Boolean) -> Unit)?,
)
