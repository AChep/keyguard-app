package com.artemchep.keyguard.platform

import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AppleStorageResolverTest {

    @Test
    fun `missing or unresolved group configuration never guesses an identifier`() {
        for (identifier in listOf(null, "", " ", "$(KEYGUARD_APP_GROUP_ID)")) {
            val resolver = resolver(identifier = identifier)
            assertEquals(
                "storage_missing_configuration",
                assertFailsWith<AppleStorageException> { resolver.resolve() }.message,
            )
        }
    }

    @Test
    fun `unavailable group can be resolved after recovery`() {
        var path: String? = null
        val resolver = resolver(group = { path })
        assertEquals(
            "storage_unavailable",
            assertFailsWith<AppleStorageException> { resolver.resolve() }.message,
        )
        path = "/group"
        assertEquals("/group", resolver.resolve())
    }

    @Test
    fun `unwritable group can be retried and the successful root stays fixed`() {
        var writable = false
        var path = "/group"
        var probes = 0
        val resolver = resolver(group = { path }, probe = {
            probes++
            if (!writable) throw IOException("unwritable")
        })
        assertEquals(
            "storage_unwritable",
            assertFailsWith<AppleStorageException> { resolver.resolve() }.message,
        )
        writable = true
        val root = resolver.resolve()
        path = "/another-group"
        assertEquals(root, resolver.resolve())
        assertEquals("/group", root)
        assertEquals(2, probes)
    }

    private fun resolver(
        identifier: String? = "group.test",
        group: (String) -> String? = { "/group" },
        probe: (String) -> Unit = {},
    ) = AppleStorageResolver(
        groupIdentifier = { identifier },
        groupContainer = group,
        probe = probe,
    )
}
