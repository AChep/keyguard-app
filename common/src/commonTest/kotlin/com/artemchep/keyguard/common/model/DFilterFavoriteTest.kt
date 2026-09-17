package com.artemchep.keyguard.common.model

import com.artemchep.keyguard.feature.home.vault.search.createSecret
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class DFilterFavoriteTest {
    @Test
    fun `favorite filter matches only favorite items`() = runTest {
        val favorite = createSecret(
            id = "favorite",
            favorite = true,
        )
        val regular = createSecret(
            id = "regular",
            favorite = false,
        )

        val predicate = DFilter.ByFavorite.prepare(
            context = testCipherFilterContext(),
            ciphers = listOf(favorite, regular),
        )

        assertTrue(predicate(favorite))
        assertFalse(predicate(regular))
    }
}
