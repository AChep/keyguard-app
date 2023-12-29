package com.artemchep.keyguard.common.service.vault

import com.artemchep.keyguard.common.model.MasterSession
import kotlinx.coroutines.flow.Flow

/**
 * @author Artem Chepurnyi
 */
interface SessionReadRepository {
    fun get(): Flow<MasterSession?>
}
