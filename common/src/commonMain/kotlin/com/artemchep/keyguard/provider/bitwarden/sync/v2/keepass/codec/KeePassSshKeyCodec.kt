package com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.codec

import app.keemobile.kotpass.models.Entry
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher

/**
 * Parser contract for Keyguard SSH-key custom fields.
 *
 * Fields consumed or written:
 *
 * | KeePass field      | Direction | Parser use                         |
 * |--------------------|-----------|------------------------------------|
 * | `ssh_privateKey`   | both      | Private key, always concealed.     |
 * | `ssh_publicKey`    | both      | Public key, plain text.            |
 * | `ssh_fingerprint`  | both      | Fingerprint, plain text.           |
 *
 * Type detection is intentionally broad: any field whose key starts with
 * `ssh_` selects the SSH-key parser, even when one or more of the three
 * canonical fields are missing.
 */
internal class KeePassSshKeyCodec {
    fun encode(sshKey: BitwardenCipher.SshKey): List<KeePassFieldWrite> = buildList {
        addConcealed(KeePassFieldKey.SSH_PRIVATE_KEY, sshKey.privateKey)
        addPlain(KeePassFieldKey.SSH_PUBLIC_KEY, sshKey.publicKey)
        addPlain(KeePassFieldKey.SSH_FINGERPRINT, sshKey.fingerprint)
    }

    fun decode(scope: DecodeToCipherScope): BitwardenCipher.SshKey =
        BitwardenCipher.SshKey(
            privateKey = scope.consumeFieldAndReturnContent(KeePassFieldKey.SSH_PRIVATE_KEY),
            publicKey = scope.consumeFieldAndReturnContent(KeePassFieldKey.SSH_PUBLIC_KEY),
            fingerprint = scope.consumeFieldAndReturnContent(KeePassFieldKey.SSH_FINGERPRINT),
        )

    /** Whether [remote] carries Keyguard SSH-key custom fields. */
    fun detects(remote: Entry): Boolean =
        remote.fields.keys.any { it.startsWith(FIELD_PREFIX) }

    private companion object {
        const val FIELD_PREFIX = "ssh_"
    }
}
