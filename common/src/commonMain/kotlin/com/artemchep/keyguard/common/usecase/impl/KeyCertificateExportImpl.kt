package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.service.crypto.SSH_CERTIFICATE_FILE_SUFFIX
import com.artemchep.keyguard.common.service.dirs.DirsService
import com.artemchep.keyguard.common.service.sshagent.extractSshKeyType
import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.common.usecase.KeyCertificateExport
import com.artemchep.keyguard.util.io.writeText
import kotlin.time.Clock

class KeyCertificateExportImpl(
    private val dirsService: DirsService,
    private val dateFormatter: DateFormatter,
) : KeyCertificateExport {
    override fun invoke(
        certificate: String,
    ): IO<String?> = ioEffect {
        // ssh-ed25519-cert-v01@openssh.com → id_ed25519
        val keyType = extractSshKeyType(certificate)
            ?.removePrefix("ssh-")
            ?.substringBefore("-cert-")
            ?.takeIf { it.isNotEmpty() && it.all { char -> char.isLetterOrDigit() || char == '-' } }
            ?: "key"
        val fileName = kotlin.run {
            val now = Clock.System.now()
            val dt = dateFormatter.formatDateTimeMachine(now)
            "id_${keyType}_$dt$SSH_CERTIFICATE_FILE_SUFFIX"
        }
        dirsService.saveToDownloads(fileName) { os ->
            os.writeText(certificate.trim() + "\n")
        }.bind()
    }
}
