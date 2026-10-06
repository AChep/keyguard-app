package com.artemchep.keyguard.common.model

import com.artemchep.keyguard.test.createSend
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.test.runTest

class DSendFilterPresenceTest {
    @Test
    fun `existsIn matches full-scan any for every primitive`() = runTest {
        val sends = listOf(
            createSend(
                id = "text-a",
                accountId = "account-a",
                type = DSend.Type.Text,
            ),
            createSend(
                id = "file-b",
                accountId = "account-b",
                type = DSend.Type.File,
            ),
        )
        val presence = DSendFilterPresence.of(sends) { it }

        val primitives = listOf(
            // ById / ACCOUNT.
            DSendFilter.ById("account-a", DSendFilter.ById.What.ACCOUNT),
            DSendFilter.ById("account-b", DSendFilter.ById.What.ACCOUNT),
            DSendFilter.ById("account-missing", DSendFilter.ById.What.ACCOUNT),
            DSendFilter.ById(null, DSendFilter.ById.What.ACCOUNT),
            // ByType.
            DSendFilter.ByType(DSend.Type.Text),
            DSendFilter.ByType(DSend.Type.File),
            DSendFilter.ByType(DSend.Type.None),
        )

        primitives.forEach { primitive ->
            val predicate = primitive.prepare(sends)
            val expected = sends.any(predicate)
            assertEquals(
                expected = expected,
                actual = primitive.existsIn(presence),
                message = "existsIn mismatch for $primitive",
            )
        }
    }

    @Test
    fun `existsIn is false for every primitive on an empty list`() = runTest {
        val presence = DSendFilterPresence.of(emptyList<DSend>()) { it }

        val primitives = listOf(
            DSendFilter.ById("account-a", DSendFilter.ById.What.ACCOUNT),
            DSendFilter.ById(null, DSendFilter.ById.What.ACCOUNT),
            DSendFilter.ByType(DSend.Type.Text),
            DSendFilter.ByType(DSend.Type.File),
        )

        primitives.forEach { primitive ->
            assertFalse(
                actual = primitive.existsIn(presence) == true,
                message = "existsIn should be false for $primitive on an empty index",
            )
        }
    }
}
