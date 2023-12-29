package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.GetCanWrite
import com.artemchep.keyguard.common.usecase.PutAutofillSaveUri
import com.artemchep.keyguard.common.usecase.premium
import org.kodein.di.DirectDI
import org.kodein.di.instance

class PutAutofillSaveUriImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
    private val getCanWrite: GetCanWrite,
) : PutAutofillSaveUri {
    constructor(directDI: DirectDI) : this(
        settingsReadWriteRepository = directDI.instance(),
        getCanWrite = directDI.instance(),
    )

    override fun invoke(saveUri: Boolean): IO<Unit> = settingsReadWriteRepository
        .setAutofillSaveUri(saveUri)
        .premium(
            getPurchased = getCanWrite,
        ) {
            // Enabling a save request feature requires write access.
            @Suppress("UnnecessaryVariable")
            val needsPremium = saveUri
            needsPremium
        }
}
