package com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.codec

import app.keemobile.kotpass.models.Entry
import com.artemchep.keyguard.common.service.sshagent.bookmarks.SSH_BOOKMARK_FIELD_NAME
import com.artemchep.keyguard.common.service.sshagent.bookmarks.isSshBookmarkValue
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
 * | `ssh_certificate`  | both      | OpenSSH certificate, plain text.   |
 * | any other key      | both      | `ssh://` bookmark, plain text.     |
 *
 * A bookmark keeps the key of its field, so that KeePass apps show it as
 * before; [decodeBookmarks] takes only the fields that nothing else claimed.
 *
 * Type detection is intentionally broad: any field whose key starts with
 * `ssh_` selects the SSH-key parser, even when one or more of the
 * canonical fields are missing.
 */
internal class KeePassSshKeyCodec {
    fun encode(sshKey: BitwardenCipher.SshKey): List<KeePassFieldWrite> = buildList {
        addConcealed(KeePassFieldKey.SSH_PRIVATE_KEY, sshKey.privateKey)
        addPlain(KeePassFieldKey.SSH_PUBLIC_KEY, sshKey.publicKey)
        addPlain(KeePassFieldKey.SSH_FINGERPRINT, sshKey.fingerprint)
        addPlain(KeePassFieldKey.SSH_CERTIFICATE, sshKey.certificate)
    }

    fun decode(scope: DecodeToCipherScope): BitwardenCipher.SshKey =
        BitwardenCipher.SshKey(
            privateKey = scope.consumeFieldAndReturnContent(KeePassFieldKey.SSH_PRIVATE_KEY),
            publicKey = scope.consumeFieldAndReturnContent(KeePassFieldKey.SSH_PUBLIC_KEY),
            fingerprint = scope.consumeFieldAndReturnContent(KeePassFieldKey.SSH_FINGERPRINT),
            certificate = scope.consumeFieldAndReturnContent(KeePassFieldKey.SSH_CERTIFICATE),
        )

    fun encodeBookmarks(bookmarks: List<BitwardenCipher.SshKey.Bookmark>): List<KeePassFieldWrite> =
        buildList {
            bookmarks.forEach { bookmark ->
                // KeePass has no field without a key.
                val key = bookmark.name
                    ?.takeIf { it.isNotEmpty() }
                    ?: SSH_BOOKMARK_FIELD_NAME
                addPlain(key, bookmark.url)
            }
        }

    /** Takes the plain `ssh://` fields that are still available in the [scope]. */
    fun decodeBookmarks(scope: DecodeToCipherScope): List<BitwardenCipher.SshKey.Bookmark> =
        scope.getAvailableFields()
            .filter { (_, value) -> !value.isConcealed() && isSshBookmarkValue(value.content) }
            .map { (key, value) ->
                scope.consumeField(key)
                BitwardenCipher.SshKey.Bookmark(
                    name = key,
                    url = value.content,
                )
            }

    /** Whether [remote] carries Keyguard SSH-key custom fields. */
    fun detects(remote: Entry): Boolean =
        remote.fields.keys.any { it.startsWith(FIELD_PREFIX) }

    private companion object {
        const val FIELD_PREFIX = "ssh_"
    }
}
