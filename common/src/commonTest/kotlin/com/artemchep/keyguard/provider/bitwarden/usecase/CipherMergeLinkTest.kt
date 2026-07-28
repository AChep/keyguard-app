package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.feature.home.vault.search.createSecret
import kotlin.test.Test
import kotlin.test.assertEquals

class CipherMergeLinkTest {
    @Test
    fun `merge canonicalizes and collapses links while preserving order`() {
        val first = createSecret(id = "first").copy(
            links = listOf(
                DSecret.Link(TARGET_REMOTE_ID.uppercase()),
                DSecret.Link(TARGET_REMOTE_ID),
                DSecret.Link(OTHER_REMOTE_ID),
            ),
        )
        val second = createSecret(id = "second").copy(
            links = listOf(
                DSecret.Link(OTHER_REMOTE_ID),
                DSecret.Link(THIRD_REMOTE_ID),
            ),
        )

        val merged = CipherMergeImpl()(listOf(first, second))

        assertEquals(
            listOf(
                DSecret.Link(TARGET_REMOTE_ID),
                DSecret.Link(OTHER_REMOTE_ID),
                DSecret.Link(THIRD_REMOTE_ID),
            ),
            merged.links,
        )
    }
}

private const val TARGET_REMOTE_ID = "b0eebc99-9c0b-4ef8-bb6d-6bb9bd380a12"
private const val OTHER_REMOTE_ID = "c0eebc99-9c0b-4ef8-bb6d-6bb9bd380a13"
private const val THIRD_REMOTE_ID = "d0eebc99-9c0b-4ef8-bb6d-6bb9bd380a14"
