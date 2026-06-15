package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.flatten
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.model.AddPrivilegedAppRequest
import com.artemchep.keyguard.common.model.DPrivilegedApp
import com.artemchep.keyguard.common.service.gpmprivapps.UserPrivilegedAppRepository
import com.artemchep.keyguard.common.usecase.AddPrivilegedApp
import kotlin.time.Clock
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * @author Artem Chepurnyi
 */
class AddPrivilegedAppImpl(
    private val userPrivilegedAppRepository: UserPrivilegedAppRepository,
) : AddPrivilegedApp {
    companion object {
        private const val TAG = "AddPrivilegedApp"
    }

    constructor(directDI: DirectDI) : this(
        userPrivilegedAppRepository = directDI.instance(),
    )

    override fun invoke(
        request: AddPrivilegedAppRequest,
    ): IO<Boolean> = ioEffect {
        val now = Clock.System.now()
        val model = DPrivilegedApp(
            name = null,
            packageName = request.packageName,
            certFingerprintSha256 = request.certFingerprintSha256,
            createdDate = now,
            source = DPrivilegedApp.Source.USER,
        )
        userPrivilegedAppRepository.put(model)
            .map { true }
    }.flatten()
}
