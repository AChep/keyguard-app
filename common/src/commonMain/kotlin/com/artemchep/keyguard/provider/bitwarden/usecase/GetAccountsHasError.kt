package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.model.DMeta
import com.artemchep.keyguard.common.usecase.GetAccountsHasError
import com.artemchep.keyguard.common.usecase.GetMetas
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * @author Artem Chepurnyi
 */
class GetAccountsHasErrorImpl(
    private val getMetas: GetMetas,
) : GetAccountsHasError {
    companion object {
        private const val TAG = "GetAccountsHasError.bitwarden"
    }

    override fun invoke(): Flow<Boolean> = getMetas()
        .map { metas ->
            metas
                .any { it.lastSyncResult is DMeta.LastSyncResult.Failure }
        }
        .distinctUntilChanged()
}
