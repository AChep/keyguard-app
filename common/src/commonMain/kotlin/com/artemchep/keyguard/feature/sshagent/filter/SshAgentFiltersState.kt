package com.artemchep.keyguard.feature.sshagent.filter

import androidx.compose.runtime.Immutable
import com.artemchep.keyguard.feature.home.vault.model.FilterItem

@Immutable
data class SshAgentFiltersState(
    val count: Int? = null,
    val filters: List<FilterItem> = emptyList(),
    val onSave: (() -> Unit)? = null,
    val onReset: (() -> Unit)? = null,
)

