package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.usecase.CipherSnapshot
import com.artemchep.keyguard.common.usecase.GetCipherSnapshots
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn

/**
 * @author Artem Chepurnyi
 */
class GetCiphersImpl internal constructor(
    private val getCipherSnapshots: GetCipherSnapshots,
    private val windowCoroutineScope: WindowCoroutineScope,
) : GetCiphers {
    private val sharedFlow = getCipherSnapshots()
        .map { snapshots ->
            snapshots
                .map(CipherSnapshot::cipher)
        }
        .shareIn(windowCoroutineScope, SharingStarted.WhileSubscribed(5000L), replay = 1)

    override fun invoke(): Flow<List<DSecret>> = sharedFlow
}
