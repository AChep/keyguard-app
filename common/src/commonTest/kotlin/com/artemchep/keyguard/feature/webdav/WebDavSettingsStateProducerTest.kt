package com.artemchep.keyguard.feature.webdav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

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

    @Test
    fun `keepass purpose rejects encoded path separator`() {
        val result = buildWebDavSettingsResult(
            url = "https://example.com/dav/a%2Fb.kdbx",
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

    @Test
    fun `collection browse starts at entered root`() {
        val result = assertIs<WebDavPickerArgsBuildResult.Success>(
            buildWebDavPickerArgs(
                url = "https://example.com/dav/root",
                username = " alice ",
                password = "secret",
                purpose = WebDavSettingsRoute.Purpose.Collection,
                keePassMode = WebDavSettingsRoute.KeePassMode.Open,
                browseRootUrl = null,
            ),
        )

        assertEquals("https://example.com/dav/root/", result.rootUrl)
        assertEquals(WebDavPickerRoute.Mode.SelectCollection, result.args.mode)
        assertEquals("alice", result.args.username)
        assertEquals("", result.args.initialPath)
    }

    @Test
    fun `keepass browse derives parent root and file name`() {
        val result = assertIs<WebDavPickerArgsBuildResult.Success>(
            buildWebDavPickerArgs(
                url = "https://example.com/dav/root/vault%20file.kdbx",
                username = "",
                password = "",
                purpose = WebDavSettingsRoute.Purpose.KeePassDatabase,
                keePassMode = WebDavSettingsRoute.KeePassMode.Create,
                browseRootUrl = null,
            ),
        )

        assertEquals("https://example.com/dav/root/", result.rootUrl)
        assertEquals(
            WebDavPickerRoute.Mode.CreateKeePassDatabase,
            result.args.mode,
        )
        assertEquals("vault file.kdbx", result.args.initialFileName)
    }

    @Test
    fun `keepass browse accepts a collection root without a file name`() {
        val result = assertIs<WebDavPickerArgsBuildResult.Success>(
            buildWebDavPickerArgs(
                url = "https://example.com/dav/root/",
                username = "",
                password = "",
                purpose = WebDavSettingsRoute.Purpose.KeePassDatabase,
                keePassMode = WebDavSettingsRoute.KeePassMode.Open,
                browseRootUrl = null,
            ),
        )

        assertEquals("https://example.com/dav/root/", result.rootUrl)
        assertEquals("", result.args.initialFileName)
    }

    @Test
    fun `repeat browse preserves root and opens selected nested folder`() {
        val result = assertIs<WebDavPickerArgsBuildResult.Success>(
            buildWebDavPickerArgs(
                url = "https://example.com/dav/root/one/two/",
                username = "",
                password = "",
                purpose = WebDavSettingsRoute.Purpose.Collection,
                keePassMode = WebDavSettingsRoute.KeePassMode.Open,
                browseRootUrl = "https://example.com/dav/root/",
            ),
        )

        assertEquals("https://example.com/dav/root/", result.rootUrl)
        assertEquals("one/two", result.args.initialPath)
    }

    @Test
    fun `browse rejects blank malformed and password only input`() {
        assertEquals(
            WebDavSettingsState.Error.UrlRequired,
            assertIs<WebDavPickerArgsBuildResult.Failure>(
                buildWebDavPickerArgs(
                    url = " ",
                    username = "",
                    password = "",
                    purpose = WebDavSettingsRoute.Purpose.Collection,
                    keePassMode = WebDavSettingsRoute.KeePassMode.Open,
                    browseRootUrl = null,
                ),
            ).error,
        )
        assertEquals(
            WebDavSettingsState.Error.InvalidUrl,
            assertIs<WebDavPickerArgsBuildResult.Failure>(
                buildWebDavPickerArgs(
                    url = "not a url",
                    username = "",
                    password = "",
                    purpose = WebDavSettingsRoute.Purpose.Collection,
                    keePassMode = WebDavSettingsRoute.KeePassMode.Open,
                    browseRootUrl = null,
                ),
            ).error,
        )
        assertEquals(
            WebDavSettingsState.Error.PasswordRequiresUsername,
            assertIs<WebDavPickerArgsBuildResult.Failure>(
                buildWebDavPickerArgs(
                    url = "https://example.com/dav/",
                    username = "",
                    password = "secret",
                    purpose = WebDavSettingsRoute.Purpose.Collection,
                    keePassMode = WebDavSettingsRoute.KeePassMode.Open,
                    browseRootUrl = null,
                ),
            ).error,
        )
    }

    @Test
    fun `create mode tests parent collection`() {
        val file = buildSuccess(
            url = "https://example.com/dav/root/vault.kdbx",
            username = "alice",
            password = "secret",
            purpose = WebDavSettingsRoute.Purpose.KeePassDatabase,
        ).location

        val location = webDavConnectionTestLocation(
            location = file,
            purpose = WebDavSettingsRoute.Purpose.KeePassDatabase,
            keePassMode = WebDavSettingsRoute.KeePassMode.Create,
        )

        assertEquals(
            "https://example.com/dav/root/",
            assertIs<com.artemchep.keyguard.common.model.WebDavLocation.Collection>(
                location,
            ).url,
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
