package com.artemchep.keyguard.common.service.license

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.license.model.License

interface LicenseService {
    fun get(): IO<List<License>>
}
