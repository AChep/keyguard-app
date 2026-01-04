package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.service.gpmprivapps.UserPrivilegedAppRepository
import com.artemchep.keyguard.common.usecase.RemovePrivilegedAppById
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * @author Artem Chepurnyi
 */
class RemovePrivilegedAppByIdImpl(
    private val userPrivilegedAppRepository: UserPrivilegedAppRepository,
) : RemovePrivilegedAppById {
    constructor(directDI: DirectDI) : this(
        userPrivilegedAppRepository = directDI.instance(),
    )

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
