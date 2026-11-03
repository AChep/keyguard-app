package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.GlobalShortcutsConfig
import kotlinx.coroutines.flow.Flow

interface GetGlobalShortcuts : () -> Flow<GlobalShortcutsConfig>
