package com.artemchep.keyguard.feature.androidipc

import com.artemchep.keyguard.common.model.ShapeState
import com.artemchep.keyguard.common.util.UniqueKeyBuilder
import com.artemchep.keyguard.feature.search.search.mapListShape
import kotlin.test.Test
import kotlin.test.assertEquals

class ConnectedCryptoAppsStateProducerTest {
    @Test
    fun `unique package names are used as keys`() {
        assertEquals(
            listOf(
                "com.example.alpha",
                "com.example.beta",
            ),
            connectedCryptoAppKeysForTest(
                packageNames = listOf(
                    "com.example.alpha",
                    "com.example.beta",
                ),
            ),
        )
    }

    @Test
    fun `duplicate package names receive unique deterministic suffixes`() {
        assertEquals(
            listOf(
                "com.example.client",
                "com.example.client#1",
                "com.example.other",
                "com.example.client#2",
            ),
            connectedCryptoAppKeysForTest(
                packageNames = listOf(
                    "com.example.client",
                    "com.example.client",
                    "com.example.other",
                    "com.example.client",
                ),
            ),
        )
    }

    @Test
    fun `app list receives grouped item shapes`() {
        val apps = listOf(
            connectedCryptoApp("com.example.alpha"),
            connectedCryptoApp("com.example.beta"),
            connectedCryptoApp("com.example.gamma"),
        ).mapListShape()

        assertEquals(
            listOf(ShapeState.START, ShapeState.CENTER, ShapeState.END),
            apps.map { it.shapeState },
        )
    }

    @Test
    fun `single app receives a complete shape`() {
        val apps = listOf(
            connectedCryptoApp("com.example.alpha"),
        ).mapListShape()

        assertEquals(ShapeState.ALL, apps.single().shapeState)
    }
}

private fun connectedCryptoApp(packageName: String) =
    ConnectedCryptoAppsState.App(
        key = packageName,
        packageName = packageName,
        label = packageName,
        signer = "signer",
        registeredAt = "registered",
        lastUsedAt = "last used",
        installed = true,
        signerMismatch = false,
        onRevoke = {},
    )

private fun connectedCryptoAppKeysForTest(
    packageNames: List<String>,
): List<String> {
    val keyBuilder = UniqueKeyBuilder()
    return packageNames.map(keyBuilder::build)
}
