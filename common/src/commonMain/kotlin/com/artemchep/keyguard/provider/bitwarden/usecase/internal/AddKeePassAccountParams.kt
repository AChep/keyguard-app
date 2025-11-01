package com.artemchep.keyguard.provider.bitwarden.usecase.internal

data class AddKeePassAccountParams(
    val mode: Mode,
    val dbUri: String,
    val dbFileName: String,
    val keyUri: String?,
    val password: String,
) {
    sealed interface Mode {
        data class New(
            val allowOverwrite: Boolean,
        ) : Mode

        data object Open : Mode
    }
}
