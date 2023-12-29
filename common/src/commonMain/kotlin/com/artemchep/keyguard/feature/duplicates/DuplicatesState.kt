package com.artemchep.keyguard.feature.duplicates

import com.artemchep.keyguard.common.usecase.CipherDuplicatesCheck
import com.artemchep.keyguard.feature.home.vault.model.VaultItem2
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.Selection
import kotlinx.coroutines.flow.StateFlow

data class DuplicatesState(
    val items: List<VaultItem2>,
    val sensitivity: CipherDuplicatesCheck.Sensitivity,
    val sensitivities: List<FlatItemAction>,
    val selectionStateFlow: StateFlow<Selection?>,
)
