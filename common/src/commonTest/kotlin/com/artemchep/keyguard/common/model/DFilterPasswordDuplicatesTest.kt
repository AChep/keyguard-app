package com.artemchep.keyguard.common.model

import com.artemchep.keyguard.feature.home.vault.search.TEST_INSTANT
import com.artemchep.keyguard.feature.home.vault.search.createSecret
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DFilterPasswordDuplicatesTest {
    @Test
    fun `archived and trashed copies do not make an active password reused`() = runTest {
        val active = createSecret("active", login = DSecret.Login(password = "shared"))
        val archived = active.copy(id = "archived", archivedDate = TEST_INSTANT)
        val trashed = active.copy(id = "trashed", deletedDate = TEST_INSTANT)
        val filter = DFilter.ByPasswordDuplicates
        val context = testCipherFilterContext()
        val ciphers = listOf(active, archived, trashed)

        assertEquals(0, filter.count(context, ciphers))
        assertEquals(emptyList(), ciphers.filter(filter.prepare(context, ciphers)))

        val restored = listOf(active, archived.copy(archivedDate = null), trashed)
        assertEquals(2, filter.count(context, restored))
        assertEquals(
            listOf("active", "archived"),
            restored.filter(filter.prepare(context, restored)).map { it.id },
        )
    }

    @Test
    fun `ignored active copies still establish reuse but are not counted`() = runTest {
        val active = createSecret("active", login = DSecret.Login(password = "shared"))
        val ignored = active.copy(
            id = "ignored",
            ignoredAlerts = mapOf(DWatchtowerAlertType.REUSED_PASSWORD to TEST_INSTANT),
        )
        val archived = active.copy(id = "archived", archivedDate = TEST_INSTANT)
        val ciphers = listOf(active, ignored, archived)
        val context = testCipherFilterContext()

        assertEquals(1, DFilter.ByPasswordDuplicates.count(context, ciphers))
        assertEquals(
            listOf(active),
            ciphers.filter(DFilter.ByPasswordDuplicates.prepare(context, ciphers)),
        )
    }
}
