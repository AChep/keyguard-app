package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.service.crypto.SshKeyPkcs8Export
import com.artemchep.keyguard.common.service.crypto.SshKeyPkcs8Exporter
import com.artemchep.keyguard.nativecrypto.NativeCrypto
import com.artemchep.keyguard.nativecrypto.NativeCryptoException

object NativeSshKeyPkcs8Exporter : SshKeyPkcs8Exporter {
    override fun exportPkcs8(
        privateKeyPem: String,
        publicKeyOpenSsh: String,
    ): SshKeyPkcs8Export? = try {
        val result = NativeCrypto.ssh.exportCxf(
            privateKeyPem = privateKeyPem,
            publicKeyOpenSsh = publicKeyOpenSsh,
        )
        SshKeyPkcs8Export(
            type = result.type.toDomain(),
            der = result.privateKeyPkcs8,
        )
    } catch (_: NativeCryptoException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}
