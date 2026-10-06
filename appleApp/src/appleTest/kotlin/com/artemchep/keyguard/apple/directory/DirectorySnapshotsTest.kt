package com.artemchep.keyguard.apple.directory

import com.artemchep.keyguard.common.service.passkey.PassKeyServiceInfo
import kotlin.test.Test
import kotlin.test.assertEquals

class DirectorySnapshotsTest {
    private val titles = DirectoryLinkTitles(
        website = "Website",
        documentation = "Documentation",
        setup = "Setup",
        email = "Email",
    )

    @Test
    fun `passkey links to one page collapse into the documentation link`() {
        val snapshot = passkey(
            documentation = "https://example.com/passkeys",
            setup = "https://example.com/passkeys",
        ).toServiceDirectoryDetailSnapshot(titles)

        assertEquals(
            listOf(ServiceDirectoryLinkSnapshot("Documentation", "https://example.com/passkeys")),
            snapshot.links,
        )
    }

    @Test
    fun `passkey links to different pages are both kept in order`() {
        val snapshot = passkey(
            documentation = "https://example.com/docs",
            setup = "https://example.com/setup",
        ).toServiceDirectoryDetailSnapshot(titles)

        assertEquals(
            listOf(
                ServiceDirectoryLinkSnapshot("Documentation", "https://example.com/docs"),
                ServiceDirectoryLinkSnapshot("Setup", "https://example.com/setup"),
            ),
            snapshot.links,
        )
    }

    private fun passkey(
        documentation: String?,
        setup: String?,
    ) = PassKeyServiceInfo(
        id = "example",
        name = "Example",
        domain = "example.com",
        documentation = documentation,
        setup = setup,
    )
}
