package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.service.urloverride.UrlOverrideRepository
import com.artemchep.keyguard.common.usecase.RemoveUrlOverrideById

/**
 * @author Artem Chepurnyi
 */
class RemoveUrlOverrideByIdImpl(
    private val urlOverrideRepository: UrlOverrideRepository,
) : RemoveUrlOverrideById {
    override fun invoke(
        urlOverrideIds: Set<String>,
    ): IO<Unit> = performRemoveUriOverride(
        urlOverrideIds = urlOverrideIds,
    ).map { Unit }

    private fun performRemoveUriOverride(
        urlOverrideIds: Set<String>,
    ) = urlOverrideRepository
        .removeByIds(urlOverrideIds)
}
