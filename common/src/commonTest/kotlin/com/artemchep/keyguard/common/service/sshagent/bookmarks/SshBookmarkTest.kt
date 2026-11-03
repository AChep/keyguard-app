package com.artemchep.keyguard.common.service.sshagent.bookmarks

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.create.CreateRequest
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class SshBookmarkTest {
    @Test
    fun `parses the user and the host and the port`() {
        val cases = listOf(
            "ssh://example.com" to SshBookmark(host = "example.com", user = null, port = null),
            "ssh://git@github.com" to SshBookmark(host = "github.com", user = "git", port = null),
            "ssh://deploy@build.example.com:2222" to
                SshBookmark(host = "build.example.com", user = "deploy", port = 2222),
            "  SSH://Admin@Example.COM/  " to SshBookmark(host = "example.com", user = "Admin", port = null),
            "ssh://my_alias" to SshBookmark(host = "my_alias", user = null, port = null),
            "ssh://root@192.0.2.10:22" to SshBookmark(host = "192.0.2.10", user = "root", port = 22),
            "ssh://root@[2001:DB8::1]:22" to SshBookmark(host = "2001:db8::1", user = "root", port = 22),
            "ssh://[::ffff:192.0.2.1]" to SshBookmark(host = "::ffff:192.0.2.1", user = null, port = null),
            // OpenSSH ignores the connection parameters of the user.
            "ssh://git;fingerprint=SHA256-abc@github.com" to
                SshBookmark(host = "github.com", user = "git", port = null),
            "ssh://us%65r@example.com" to SshBookmark(host = "example.com", user = "user", port = null),
        )
        cases.forEach { (value, expected) ->
            assertEquals(expected, parseSshBookmark(value), value)
        }
    }

    @Test
    fun `rejects what a config can not hold as is`() {
        val cases = listOf(
            "https://example.com",
            "ssh:/example.com",
            "ssh://",
            "ssh://@example.com",
            "ssh://user@",
            "ssh://example.com:",
            "ssh://example.com:0",
            "ssh://example.com:65536",
            "ssh://example.com:+22",
            "ssh://example.com/path",
            "ssh://example.com//",
            "ssh://*.example.com",
            "ssh://example?.com",
            "ssh://!example.com",
            "ssh://a.com,b.com",
            "ssh://exa mple.com",
            "ssh://\"example.com\"",
            "ssh://-oProxyCommand=evil",
            "ssh://-l@example.com",
            "ssh://user name@example.com",
            "ssh://us%20er@example.com",
            "ssh://us%zzer@example.com",
            "ssh://[2001:db8::1",
            "ssh://[2001:db8::1]x",
            "ssh://[fe80::1%25eth0]",
            "ssh://[example.com]",
        )
        cases.forEach { value ->
            assertNull(parseSshBookmark(value), value)
        }
    }

    @Test
    fun `reads bookmarks from text fields only`() {
        val cases = listOf(
            DSecret.Field(name = "SSH", value = "ssh://git@github.com", type = DSecret.Field.Type.Text) to
                DSecret.SshKey.Bookmark(name = "SSH", url = "ssh://git@github.com"),
            DSecret.Field(name = null, value = " SSH://example.com ", type = DSecret.Field.Type.Text) to
                DSecret.SshKey.Bookmark(name = null, url = " SSH://example.com "),
            DSecret.Field(name = "Hidden", value = "ssh://secret.example.com", type = DSecret.Field.Type.Hidden) to
                null,
            DSecret.Field(name = "Web", value = "https://example.com", type = DSecret.Field.Type.Text) to null,
            DSecret.Field(name = "Empty", value = null, type = DSecret.Field.Type.Text) to null,
        )
        cases.forEach { (field, expected) ->
            assertEquals(expected, field.toSshBookmarkOrNull(), field.name)
            // A bookmark syncs as the field it came from.
            expected?.let { assertEquals(field, it.toField()) }
        }
    }

    @Test
    fun `moves the ssh text fields of an SSH key into its bookmarks`() {
        val note = DSecret.Field(name = "Note", value = "ssh://note.example.com", type = DSecret.Field.Type.Hidden)
        val github = DSecret.Field(name = "GitHub", value = "ssh://git@github.com", type = DSecret.Field.Type.Text)
        val request = CreateRequest(
            type = DSecret.Type.SshKey,
            fields = persistentListOf(note, github),
            sshKey = CreateRequest.SshKey(
                bookmarks = persistentListOf(DSecret.SshKey.Bookmark(name = "Prod", url = "ssh://prod.example.com")),
            ),
            now = Instant.fromEpochSeconds(0),
        )

        val moved = request.withSshBookmarkFields()
        assertEquals(listOf(note), moved.fields)
        assertEquals(
            listOf(
                DSecret.SshKey.Bookmark(name = "Prod", url = "ssh://prod.example.com"),
                DSecret.SshKey.Bookmark(name = "GitHub", url = "ssh://git@github.com"),
            ),
            moved.sshKey.bookmarks,
        )

        // Other items keep their fields.
        val login = request.copy(type = DSecret.Type.Login)
        assertEquals(login, login.withSshBookmarkFields())
    }

    @Test
    fun `adds the scheme to a value without one`() {
        val cases = listOf(
            "deploy@example.com:2222" to "ssh://deploy@example.com:2222",
            "  example.com " to "ssh://example.com",
            "ssh://example.com" to "ssh://example.com",
            " SSH://example.com" to "SSH://example.com",
            "https://example.com" to "https://example.com",
            "   " to "",
        )
        cases.forEach { (value, expected) ->
            assertEquals(expected, normalizeSshBookmarkValue(value), value)
        }
    }

    @Test
    fun `formats the bookmark and its command`() {
        val cases = listOf(
            SshBookmark(host = "example.com", user = null, port = null) to
                ("example.com" to "ssh example.com"),
            SshBookmark(host = "example.com", user = "deploy", port = 2222) to
                ("deploy@example.com:2222" to "ssh -p 2222 deploy@example.com"),
            SshBookmark(host = "2001:db8::1", user = "root", port = 22) to
                ("root@[2001:db8::1]:22" to "ssh -p 22 root@2001:db8::1"),
            SshBookmark(host = "2001:db8::1", user = null, port = null) to
                ("2001:db8::1" to "ssh 2001:db8::1"),
        )
        cases.forEach { (bookmark, expected) ->
            assertEquals(expected.first, bookmark.toDisplayString())
            assertEquals(expected.second, bookmark.toSshCommand())
        }
    }

    @Test
    fun `finds the host in the formatted bookmark`() {
        val cases = listOf(
            SshBookmark(host = "example.com", user = null, port = null) to "example.com",
            SshBookmark(host = "example.com", user = "deploy", port = 2222) to "example.com",
            SshBookmark(host = "2001:db8::1", user = "root", port = 22) to "[2001:db8::1]",
        )
        cases.forEach { (bookmark, expected) ->
            assertEquals(expected, bookmark.toDisplayString().substring(bookmark.displayHostRange()))
        }
    }
}
