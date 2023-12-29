package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.service.relays.repo.GeneratorEmailRelayRepository
import com.artemchep.keyguard.common.usecase.RemoveEmailRelayById
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * @author Artem Chepurnyi
 */
class RemoveEmailRelayByIdImpl(
    private val generatorEmailRelayRepository: GeneratorEmailRelayRepository,
) : RemoveEmailRelayById {
    companion object {
        private const val TAG = "RemoveEmailRelayById.bitwarden"
    }

    constructor(directDI: DirectDI) : this(
        generatorEmailRelayRepository = directDI.instance(),
    )

    override fun invoke(
        emailRelayIds: Set<String>,
    ): IO<Unit> = performRemoveEmailRelay(
        emailRelayIds = emailRelayIds,
    ).map { Unit }

    private fun performRemoveEmailRelay(
        emailRelayIds: Set<String>,
    ) = generatorEmailRelayRepository
        .removeByIds(emailRelayIds)
}
