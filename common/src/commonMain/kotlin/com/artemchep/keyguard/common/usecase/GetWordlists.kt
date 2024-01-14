package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.DGeneratorWordlist
import kotlinx.coroutines.flow.Flow

/**
 * Provides a list of all available
 * passphrase wordlists.
 */
interface GetWordlists : () -> Flow<List<DGeneratorWordlist>>
