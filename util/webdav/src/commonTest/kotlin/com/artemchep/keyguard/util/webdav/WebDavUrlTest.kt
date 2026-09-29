package com.artemchep.keyguard.util.webdav

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebDavUrlTest {
    @Test
    fun `collection url accepts absolute http and https destinations`() {
        listOf(
            "https://example.com",
            "http://localhost:8080/backups/",
            "https://example.com/remote.php/dav/files/vault/",
            "https://example.com/my%20backups?directory=vault",
            "https://192.168.1.2:8443/backups",
            "https://[::1]:8443/backups",
            "HTTPS://EXAMPLE.COM/backups",
            " \nhttps://example.com/backups\t ",
            "https://example.com/backups%23folder",
        ).forEach { assertTrue(isValidWebDavCollectionUrl(it), it) }
    }

    @Test
    fun `collection url rejects empty relative and unsupported destinations`() {
        listOf(
            "",
            " \n\t ",
            "example.com/backups",
            "/backups",
            "//example.com/backups",
            "file:///backups",
            "ftp://example.com/backups",
            "https:",
            "https:///backups",
            "https://",
        ).forEach { assertFalse(isValidWebDavCollectionUrl(it), it) }
    }

    @Test
    fun `collection url rejects malformed urls without escaping them`() {
        listOf(
            "https://exa mple.com/backups",
            "https://example.com/my backups",
            "https://example.com/%invalid",
            "https://example.com/%",
            "https://[::1/backups",
            "https://example.com:invalid/backups",
        ).forEach { assertFalse(isValidWebDavCollectionUrl(it), it) }
    }

    @Test
    fun `collection url requires credentials in separate fields`() {
        listOf(
            "https://user:secret@example.com/backups",
            "https://user@example.com/backups",
            "https://:secret@example.com/backups",
            "https://@example.com/backups",
        ).forEach { assertFalse(isValidWebDavCollectionUrl(it), it) }
    }

    @Test
    fun `collection url rejects fragments including an empty fragment`() {
        listOf(
            "https://example.com/backups#folder",
            "https://example.com/backups#",
        ).forEach { assertFalse(isValidWebDavCollectionUrl(it), it) }
    }
}
