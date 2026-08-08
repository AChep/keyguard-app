package com.artemchep.keyguard.feature.gpgagent.history

import com.artemchep.keyguard.common.service.gpgagent.GpgAgentMessages
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GpgAgentHistoryMapperTest {
    @Test
    fun `build caller info shows Android app label and package`() {
        val caller =
            GpgAgentMessages.CallerIdentity(
                pid = 123,
                uid = 456,
                appName = "Example Client",
                appBundlePath = "com.example.client",
            )

        val info = buildGpgUsageHistoryCallerInfo(Json.encodeToString(caller), Json)

        assertEquals("Example Client", info?.primaryLabel)
        assertEquals("com.example.client", info?.secondaryLabel)
    }

    @Test
    fun `build caller info falls back to package then process`() {
        val packageCaller =
            GpgAgentMessages.CallerIdentity(
                appBundlePath = "com.example.client",
            )
        val processCaller =
            GpgAgentMessages.CallerIdentity(
                processName = "gpg",
            )

        assertEquals(
            "com.example.client",
            buildGpgUsageHistoryCallerInfo(Json.encodeToString(packageCaller), Json)?.primaryLabel,
        )
        assertEquals(
            "gpg",
            buildGpgUsageHistoryCallerInfo(Json.encodeToString(processCaller), Json)?.primaryLabel,
        )
    }

    @Test
    fun `build caller info sanitizes persisted metadata at the display boundary`() {
        val caller =
            GpgAgentMessages.CallerIdentity(
                appName = "Example\u202E Client 😀",
                appBundlePath = "com.example\u2028client",
                processName = "g".repeat(300),
            )

        val info = buildGpgUsageHistoryCallerInfo(Json.encodeToString(caller), Json)

        assertEquals("Example\\u202e Client 😀", info?.primaryLabel)
        assertEquals(
            "com.example\\u2028client • ${"g".repeat(255)}…",
            info?.secondaryLabel,
        )
    }

    @Test
    fun `build caller info returns null for malformed json`() {
        assertNull(buildGpgUsageHistoryCallerInfo("{not-json", Json))
    }
}
