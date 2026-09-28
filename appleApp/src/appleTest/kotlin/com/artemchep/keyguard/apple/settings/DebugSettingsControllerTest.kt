package com.artemchep.keyguard.apple.settings

import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.usecase.GetDebugPremium
import com.artemchep.keyguard.common.usecase.PutDebugPremium
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DebugSettingsControllerTest {
    @Test
    fun observesAndUpdatesTheSavedOverrideAcrossScreenVisits() = runTest {
        val fixture = Fixture(this, saved = true)
        val snapshots = mutableListOf<DebugSettingsSnapshot>()
        val observation = fixture.controller.observe { snapshots += it }
        runCurrent()
        assertEquals(
            DebugSettingsSnapshot(
                loaded = true,
                premiumOverrideAvailable = true,
                premiumOverrideEnabled = true,
            ),
            snapshots.last(),
        )

        fixture.controller.setPremiumOverride(false)
        runCurrent()
        assertFalse(snapshots.last().premiumOverrideEnabled)
        assertEquals(listOf(false), fixture.writes)

        observation.cancel()
        val count = snapshots.size
        fixture.controller.setPremiumOverride(true)
        runCurrent()
        assertEquals(count, snapshots.size)
        assertEquals(listOf(false, true), fixture.writes)

        fixture.controller.observe { snapshots += it }
        runCurrent()
        assertTrue(snapshots.last().premiumOverrideEnabled)
    }

    @Test
    fun releaseDoesNotReadOrWriteTheSavedOverride() = runTest {
        val fixture = Fixture(this, saved = true, release = true)
        val snapshots = mutableListOf<DebugSettingsSnapshot>()
        fixture.controller.observe { snapshots += it }
        fixture.controller.setPremiumOverride(false)
        fixture.controller.setPremiumOverride(true)
        runCurrent()

        assertEquals(listOf(DebugSettingsSnapshot(loaded = true)), snapshots)
        assertEquals(0, fixture.reads)
        assertTrue(fixture.writes.isEmpty())
        assertTrue(fixture.saved.value)
    }

    private class Fixture(
        scope: TestScope,
        saved: Boolean,
        release: Boolean = false,
    ) {
        val saved = MutableStateFlow(saved)
        var reads = 0
        val writes = mutableListOf<Boolean>()
        val controller = DebugSettingsController(
            getDebugPremium = object : GetDebugPremium {
                override fun invoke() = this@Fixture.saved.also { reads++ }
            },
            putDebugPremium = object : PutDebugPremium {
                override fun invoke(premium: Boolean) = ioEffect {
                    writes += premium
                    this@Fixture.saved.value = premium
                }
            },
            scope = scope.backgroundScope,
            release = release,
        )
    }
}
