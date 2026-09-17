package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.service.gpmprivapps.UserPrivilegedAppRepository
import com.artemchep.keyguard.common.usecase.RemovePrivilegedAppById

/**
 * @author Artem Chepurnyi
 */
class RemovePrivilegedAppByIdImpl(
    private val userPrivilegedAppRepository: UserPrivilegedAppRepository,
) : RemovePrivilegedAppById {
    override fun invoke(
        urlBlockIds: Set<String>,
    ): IO<Unit> = performRemovePrivilegedAppBlock(
        privilegedAppIds = urlBlockIds,
    ).map { Unit }

    private fun performRemovePrivilegedAppBlock(
        privilegedAppIds: Set<String>,
    ) = userPrivilegedAppRepository
        .removeByIds(privilegedAppIds)
}
