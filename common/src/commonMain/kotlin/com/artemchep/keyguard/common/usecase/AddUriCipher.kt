package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.AddUriCipherRequest

fun AddUriCipherRequest.isEmpty() =
    applicationId == null &&
            webDomain == null &&
            webScheme == null

interface AddUriCipher : (
    AddUriCipherRequest,
) -> IO<Boolean>
