package com.artemchep.keyguard.feature.webdav

import kotlin.test.Test
import kotlin.test.assertEquals

class WebDavSettingsStateProducerTest {
    @Test
    fun `accepts valid url only config`() {
        val result = buildSuccess(
            url = "https://example.com/dav/",
            username = "",
            password = "",
        )

        assertEquals("https://example.com/dav/", result.url)
        assertEquals(null, result.username)
        assertEquals(null, result.password)
    }

    @Test
    fun `trims url and username`() {
        val result = buildSuccess(
            url = "  https://example.com/dav/  ",
            username = "  alice  ",
            password = "",
        )

        assertEquals("https://example.com/dav/", result.url)
        assertEquals("alice", result.username)
        assertEquals(null, result.password)
    }

    @Test
    fun `preserves non empty password`() {
        val result = buildSuccess(
            url = "https://example.com/dav/",
            username = "alice",
            password = " secret ",
        )

        assertEquals(" secret ", result.password)
    }

    @Test
    fun `rejects blank url`() {
        val result = buildWebDavSettingsResult(
            url = " ",
            username = "",
            password = "",
        )

        assertEquals(
            WebDavSettingsBuildResult.Failure(
                WebDavSettingsState.Error.UrlRequired,
            ),
            result,
        )
    }

    @Test
    fun `rejects password without username`() {
        val result = buildWebDavSettingsResult(
            url = "https://example.com/dav/",
            username = " ",
            password = "secret",
        )

        assertEquals(
            WebDavSettingsBuildResult.Failure(
                WebDavSettingsState.Error.PasswordRequiresUsername,
            ),
            result,
        )
    }

    @Test
    fun `keepass purpose accepts full database file url`() {
        val result = buildSuccess(
            url = "https://example.com/dav/vault.kdbx",
            username = "",
            password = "",
            purpose = WebDavSettingsRoute.Purpose.KeePassDatabase,
        )

        assertEquals("https://example.com/dav/vault.kdbx", result.url)
    }

    @Test
    fun `keepass purpose rejects collection url`() {
        val result = buildWebDavSettingsResult(
            url = "https://example.com/dav/",
            username = "",
            password = "",
            purpose = WebDavSettingsRoute.Purpose.KeePassDatabase,
        )

        assertEquals(
            WebDavSettingsBuildResult.Failure(
                WebDavSettingsState.Error.FileUrlRequired,
            ),
            result,
        )
    }
}

private fun buildSuccess(
    url: String,
    username: String,
    password: String,
    purpose: WebDavSettingsRoute.Purpose = WebDavSettingsRoute.Purpose.Collection,
): WebDavSettingsResult {
    val result = buildWebDavSettingsResult(
        url = url,
        username = username,
        password = password,
        purpose = purpose,
    )
    if (result !is WebDavSettingsBuildResult.Success) {
        error("Expected successful WebDAV settings build, got $result.")
    }
    return result.result
}
