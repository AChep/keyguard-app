package com.artemchep.keyguard.provider.bitwarden.usecase.internal

import com.artemchep.keyguard.common.model.WebDavLocation

data class AddKeePassAccountParams(
    val mode: Mode,
    val dbUri: String,
    val dbFileName: String,
    val managedByApp: Boolean = false,
    val webDav: WebDavLocation.File? = null,
    val dbAccessToken: String? = null,
    val keyUri: String?,
    val keyAccessToken: String? = null,
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
