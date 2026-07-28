package com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.codec

import app.keemobile.kotpass.models.Entry
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher

/**
 * Parser contract for Keyguard GPG-key custom fields.
 *
 * Fields consumed or written:
 *
 * | KeePass field             | Direction | Parser use                         |
 * |---------------------------|-----------|------------------------------------|
 * | `gpg_privateKeyArmored`   | both      | Armored private key, concealed.    |
 * | `gpg_publicKeyArmored`    | both      | Armored public key, plain text.    |
 * | `gpg_fingerprint`         | both      | Primary fingerprint, plain text.   |
 */
internal class KeePassGpgKeyCodec {
    fun encode(gpgKey: BitwardenCipher.GpgKey): List<KeePassFieldWrite> = buildList {
        addConcealed(KeePassFieldKey.GPG_PRIVATE_KEY_ARMORED, gpgKey.privateKeyArmored)
        addPlain(KeePassFieldKey.GPG_PUBLIC_KEY_ARMORED, gpgKey.publicKeyArmored)
        addPlain(KeePassFieldKey.GPG_FINGERPRINT, gpgKey.fingerprint)
    }

    fun decode(scope: DecodeToCipherScope): BitwardenCipher.GpgKey? {
        val privateKeyArmored = consumeFirstContent(
            scope = scope,
            keys = listOf(KeePassFieldKey.GPG_PRIVATE_KEY_ARMORED),
        )
        val publicKeyArmored = consumeFirstContent(
            scope = scope,
            keys = listOf(KeePassFieldKey.GPG_PUBLIC_KEY_ARMORED),
        )
        val fingerprint = consumeFirstContent(
            scope = scope,
            keys = listOf(KeePassFieldKey.GPG_FINGERPRINT),
        )

        return BitwardenCipher.GpgKey(
            privateKeyArmored = privateKeyArmored,
            publicKeyArmored = publicKeyArmored,
            fingerprint = fingerprint,
        ).takeUnlessEmpty()
    }

    fun detects(remote: Entry): Boolean =
        remote.fields.keys.any { key ->
            key in FIELD_KEYS
        }

    private fun consumeFirstContent(
        scope: DecodeToCipherScope,
        keys: List<String>,
    ): String? {
        keys.forEach { key ->
            val value = scope.consumeFieldAndReturnContent(key)
            if (value != null) {
                return value
            }
        }
        return null
    }

    private fun BitwardenCipher.GpgKey.takeUnlessEmpty(): BitwardenCipher.GpgKey? =
        takeIf {
            privateKeyArmored?.isNotBlank() == true ||
                    publicKeyArmored?.isNotBlank() == true ||
                    fingerprint?.isNotBlank() == true
        }

    private companion object {
        val FIELD_KEYS = setOf(
            KeePassFieldKey.GPG_PRIVATE_KEY_ARMORED,
            KeePassFieldKey.GPG_PUBLIC_KEY_ARMORED,
            KeePassFieldKey.GPG_FINGERPRINT,
        )
    }
}
