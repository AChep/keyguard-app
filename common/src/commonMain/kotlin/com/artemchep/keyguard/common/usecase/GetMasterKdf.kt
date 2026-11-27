package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.MasterKdf
import kotlinx.coroutines.flow.Flow

/**
 * Returns the key derivation of the vault, or `null`
 * if there is no vault.
 */
interface GetMasterKdf : () -> Flow<MasterKdf?>
