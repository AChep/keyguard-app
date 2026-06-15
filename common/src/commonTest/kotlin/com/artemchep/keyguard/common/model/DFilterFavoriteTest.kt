package com.artemchep.keyguard.common.model

import com.artemchep.keyguard.feature.home.vault.search.createSecret
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.kodein.di.DI
import org.kodein.di.direct

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
            directDI = DI {}.direct,
            ciphers = listOf(favorite, regular),
        )

        assertTrue(predicate(favorite))
        assertFalse(predicate(regular))
    }
}
