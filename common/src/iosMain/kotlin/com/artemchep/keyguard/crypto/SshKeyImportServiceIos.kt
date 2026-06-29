package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.service.crypto.SshKeyImportError
import com.artemchep.keyguard.common.service.crypto.SshKeyImportRequest
import com.artemchep.keyguard.common.service.crypto.SshKeyImportResult
import com.artemchep.keyguard.common.service.crypto.SshKeyImportService

object SshKeyImportServiceIos : SshKeyImportService {
    override fun import(
        request: SshKeyImportRequest,
    ): SshKeyImportResult = SshKeyImportResult.Error(
        reason = SshKeyImportError.UnsupportedFormat,
    )
}
