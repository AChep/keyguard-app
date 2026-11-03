package com.artemchep.keyguard.provider.bitwarden.crypto

import com.artemchep.keyguard.common.service.sshagent.bookmarks.isSshBookmarkValue
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher

/**
 * Carries the bookmarks of an SSH key in Bitwarden text custom fields: the
 * Bitwarden SSH key has no field for them, and drops the URIs of an item that
 * is not a login. Unlike other transport fields, the names are the user's: any
 * text field with an `ssh://` value is a bookmark, wherever it was added.
 */
internal object BitwardenSshBookmarkFieldCodec {
    /**
     * Only SSH keys have bookmarks. Other types must keep
     * user-created fields with matching values untouched.
     */
    fun appliesTo(type: BitwardenCipher.Type): Boolean =
        type == BitwardenCipher.Type.SshKey

    fun encode(bookmarks: List<BitwardenCipher.SshKey.Bookmark>): List<BitwardenCipher.Field> =
        bookmarks.map { bookmark ->
            BitwardenCipher.Field(
                name = bookmark.name,
                value = bookmark.url,
                type = BitwardenCipher.Field.Type.Text,
            )
        }

    /** Returns `null` if the [field] is not a bookmark. */
    fun decode(field: BitwardenCipher.Field): BitwardenCipher.SshKey.Bookmark? {
        val url = field.value
            ?.takeIf {
                field.type == BitwardenCipher.Field.Type.Text &&
                        field.linkedId == null &&
                        isSshBookmarkValue(it)
            }
            ?: return null
        return BitwardenCipher.SshKey.Bookmark(
            name = field.name,
            url = url,
        )
    }
}
