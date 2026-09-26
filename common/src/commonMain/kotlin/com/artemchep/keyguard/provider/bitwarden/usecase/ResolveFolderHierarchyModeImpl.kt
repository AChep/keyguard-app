package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.model.FolderHierarchyMode
import com.artemchep.keyguard.common.usecase.ResolveFolderHierarchyMode
import com.artemchep.keyguard.core.store.bitwarden.BitwardenToken
import com.artemchep.keyguard.core.store.bitwarden.KeePassToken
import com.artemchep.keyguard.provider.bitwarden.repository.ServiceTokenRepository

class ResolveFolderHierarchyModeImpl(
    private val tokenRepository: ServiceTokenRepository,
) : ResolveFolderHierarchyMode {
    override fun invoke(accountId: AccountId): IO<FolderHierarchyMode> =
        tokenRepository
            .getById(accountId)
            .map { token ->
                when (token) {
                    is BitwardenToken -> FolderHierarchyMode.Path
                    is KeePassToken -> FolderHierarchyMode.ParentId
                    null -> error("Account ${accountId.id} does not exist.")
                }
            }
}
