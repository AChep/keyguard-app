package com.artemchep.keyguard.common.service.sshagent.bookmarks

import com.artemchep.keyguard.common.io.runCatchingNonFatal
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.create.CreateRequest
import io.ktor.http.decodeURLPart
import kotlinx.collections.immutable.toPersistentList

private const val SSH_BOOKMARK_SCHEME = "ssh://"

private const val SSH_BOOKMARK_PORT_MAX = 65535

/**
 * An `ssh://[user@]host[:port]` URL, as OpenSSH reads it: the user ends at
 * the first `@`, connection parameters after a `;` are ignored, and an empty
 * path is allowed. Unlike OpenSSH, it accepts only the host names, aliases and
 * addresses that a `Match` line can hold as is: no patterns, list separators,
 * quotes or whitespace, and no leading `-` that reads as an option.
 */
private val SSH_BOOKMARK_REGEX = Regex(
    "^ssh://" +
        "(?:([^@/;]+)(?:;[^@/]*)?@)?" +
        "(\\[[0-9a-f.]*:[0-9a-f.:]*\\]|[a-z0-9._][a-z0-9._-]*)" +
        "(?::([0-9]{1,5}))?" +
        "/?$",
    RegexOption.IGNORE_CASE,
)

private val SSH_BOOKMARK_USER_REGEX = Regex("^[A-Za-z0-9._][A-Za-z0-9._-]*$")

/**
 * A host that the key of an SSH key item is for, read from an
 * `ssh://[user@]host[:port]` custom field.
 */
data class SshBookmark(
    /** A host name, an alias or an IP address, in lower case. */
    val host: String,
    val user: String?,
    val port: Int?,
)

/**
 * The name of a new bookmark field. Keyguard ignores it, but
 * KeePass drops a field without a name, so never leave it out.
 */
const val SSH_BOOKMARK_FIELD_NAME = "SSH bookmark"

/**
 * Whether a text field with the [value] is an SSH bookmark of an SSH key item.
 * Any `ssh://` value counts, so that the bookmarks that users add in other apps
 * do too. Hidden fields never count: the bookmarks end up in a plain text file.
 */
fun isSshBookmarkValue(value: String): Boolean =
    value.trim().startsWith(SSH_BOOKMARK_SCHEME, ignoreCase = true)

/** The bookmark that a custom field of an SSH key item holds, if any. */
fun DSecret.Field.toSshBookmarkOrNull(): DSecret.SshKey.Bookmark? {
    val url = value
        ?.takeIf { type == DSecret.Field.Type.Text && isSshBookmarkValue(it) }
        ?: return null
    return DSecret.SshKey.Bookmark(
        name = name,
        url = url,
    )
}

/**
 * Moves the `ssh://` text fields of an SSH key into its bookmarks, as a sync
 * does, for example the fields of an imported item.
 */
fun CreateRequest.withSshBookmarkFields(): CreateRequest {
    val (bookmarkFields, otherFields) = fields
        .partition { type == DSecret.Type.SshKey && it.toSshBookmarkOrNull() != null }
    if (bookmarkFields.isEmpty()) {
        return this
    }
    return copy(
        fields = otherFields.toPersistentList(),
        sshKey = sshKey.copy(
            bookmarks = sshKey.bookmarks.addAll(bookmarkFields.mapNotNull { it.toSshBookmarkOrNull() }),
        ),
    )
}

/** The text field that the bookmark is synced as. */
fun DSecret.SshKey.Bookmark.toField(): DSecret.Field = DSecret.Field(
    name = name,
    value = url,
    type = DSecret.Field.Type.Text,
)

/**
 * Adds the `ssh://` scheme to a value that has none, so
 * that `user@host` is saved as a bookmark.
 */
fun normalizeSshBookmarkValue(value: String): String {
    val text = value.trim()
    return if (text.isEmpty() || "://" in text) text else SSH_BOOKMARK_SCHEME + text
}

/** The host, with brackets around an IPv6 address that a port follows. */
private fun SshBookmark.displayHost(): String =
    if (port != null && ':' in host) "[$host]" else host

/** Where the host is in [toDisplayString]. */
fun SshBookmark.displayHostRange(): IntRange {
    val start = user?.let { it.length + 1 } ?: 0
    return start until start + displayHost().length
}

/** The bookmark as `user@host:port`. */
fun SshBookmark.toDisplayString(): String = buildString {
    if (user != null) {
        append(user)
        append('@')
    }
    append(displayHost())
    if (port != null) {
        append(':')
        append(port)
    }
}

/**
 * The `ssh` command that connects to the host. The parser accepts
 * only users and hosts that a shell reads as is, so nothing is quoted.
 */
fun SshBookmark.toSshCommand(): String = buildString {
    append("ssh")
    if (port != null) {
        append(" -p ")
        append(port)
    }
    append(' ')
    if (user != null) {
        append(user)
        append('@')
    }
    append(host)
}

/**
 * Parses an `ssh://[user@]host[:port]` URL, or returns `null` if it is not
 * one, or if it names something that can not be written to an SSH
 * configuration as is: a pattern, an option or a path.
 */
fun parseSshBookmark(value: String): SshBookmark? {
    val match = SSH_BOOKMARK_REGEX.matchEntire(value.trim())
        ?: return null
    val userGroup = match.groups[1]
    val portGroup = match.groups[3]
    val user = userGroup?.value?.let(::decodeSshBookmarkUser)
    val port = portGroup?.value?.toInt()
    val validUser = userGroup == null || user != null
    val validPort = port == null || port in 1..SSH_BOOKMARK_PORT_MAX
    return SshBookmark(
        host = match.groupValues[2]
            .removeSurrounding("[", "]")
            .lowercase(),
        user = user,
        port = port,
    ).takeIf { validUser && validPort }
}

private fun decodeSshBookmarkUser(encoded: String): String? =
    runCatchingNonFatal { encoded.decodeURLPart() }
        .getOrNull()
        ?.takeIf(SSH_BOOKMARK_USER_REGEX::matches)
