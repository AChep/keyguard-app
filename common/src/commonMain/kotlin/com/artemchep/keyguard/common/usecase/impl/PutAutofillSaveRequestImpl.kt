package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.GetCanWrite
import com.artemchep.keyguard.common.usecase.PutAutofillSaveRequest
import com.artemchep.keyguard.common.usecase.premium

class PutAutofillSaveRequestImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
    private val getCanWrite: GetCanWrite,
) : PutAutofillSaveRequest {
    override fun invoke(saveRequest: Boolean): IO<Unit> = settingsReadWriteRepository
        .setAutofillSaveRequest(saveRequest)
        .premium(
            getPurchased = getCanWrite,
        ) {
            // Enabling a save request feature requires write access.
            @Suppress("UnnecessaryVariable")
            val needsPremium = saveRequest
            needsPremium
        }
}
