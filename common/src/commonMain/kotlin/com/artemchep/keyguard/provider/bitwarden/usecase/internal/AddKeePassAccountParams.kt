package com.artemchep.keyguard.provider.bitwarden.usecase.internal

data class AddKeePassAccountParams(
    val mode: Mode,
    val dbUri: String,
    val dbFileName: String,
    val managedByApp: Boolean = false,
    val keyUri: String?,
    val password: String,
    val syncMode: SyncMode = SyncMode.Queued,
) {
    sealed interface Mode {
        data class New(
            val allowOverwrite: Boolean,
        ) : Mode

        data object Open : Mode
    }

    enum class SyncMode {
        Queued,
        Direct,
    }
}
