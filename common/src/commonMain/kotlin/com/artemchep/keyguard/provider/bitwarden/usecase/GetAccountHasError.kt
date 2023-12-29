package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.model.DMeta
import com.artemchep.keyguard.common.usecase.GetAccountHasError
import com.artemchep.keyguard.common.usecase.GetMetas
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * @author Artem Chepurnyi
 */
class GetAccountHasErrorImpl(
    private val getMetas: GetMetas,
) : GetAccountHasError {
    companion object {
        private const val TAG = "GetAccountHasError.bitwarden"
    }

    constructor(directDI: DirectDI) : this(
        getMetas = directDI.instance(),
    )

    override fun invoke(accountId: AccountId): Flow<Boolean> = getMetas()
        .map { metas ->
            metas
                .any {
                    it.accountId == accountId &&
                            it.lastSyncResult is DMeta.LastSyncResult.Failure
                }
        }
        .distinctUntilChanged()
}
