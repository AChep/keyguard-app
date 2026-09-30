package com.artemchep.keyguard.apple.settings

import com.artemchep.keyguard.common.model.AppVersionLog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class AppInformationTest {
    @Test
    fun freshInstallAndSingleBuildHaveNoChangelog() = runTest {
        for (refs in listOf(emptyList(), listOf("abc123"))) {
            val snapshot = snapshot(refs)
            assertFalse(AppInformationSnapshot.empty.loaded)
            assertTrue(snapshot.loaded)
            assertEquals("Sep 16, 2026", snapshot.buildDate)
            assertEquals("abc123", snapshot.buildRef)
            assertEquals("https://github.com/AChep/keyguard-app/tree/abc123", snapshot.buildRefUrl)
            assertNull(snapshot.changelogText)
            assertNull(snapshot.changelogUrl)
        }
    }

    @Test
    fun comparisonRunsFromPreviousBuildToNewestBuild() = runTest {
        val snapshot = snapshot(listOf("abc123", "def456", "789abc"))
        assertEquals("abc123...def456", snapshot.changelogText)
        assertEquals("https://github.com/AChep/keyguard-app/compare/def456...abc123", snapshot.changelogUrl)
    }

    @Test
    fun ignoresUnavailableAndDuplicateHistoryRefs() = runTest {
        val snapshot = snapshot(listOf("", "unknown", "abc123", "abc123", " ", "UNKNOWN", "def456"))
        assertEquals("https://github.com/AChep/keyguard-app/compare/def456...abc123", snapshot.changelogUrl)
        assertNull(snapshot(listOf("abc123", "abc123", "unknown")).changelogUrl)
    }

    @Test
    fun unavailableBuildRefsDoNotCreateRevisionLinks() = runTest {
        for (ref in listOf("", " ", "unknown", "UNKNOWN")) {
            val snapshot = appInformationFlow(flowOf("date"), flowOf(ref), flowOf(emptyList())).first()
            assertNull(snapshot.buildRef)
            assertNull(snapshot.buildRefUrl)
        }
    }

    @Test
    fun startupHistoryUpdatesReachAnAlreadyOpenScreen() = runTest {
        val log = MutableStateFlow(listOf(entry("def456")))
        val snapshots = mutableListOf<AppInformationSnapshot>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            appInformationFlow(flowOf("date"), flowOf("abc123"), log).collect { snapshots += it }
        }
        runCurrent()
        assertNull(snapshots.last().changelogUrl)
        log.value = listOf(entry("abc123"), entry("def456"))
        runCurrent()
        assertEquals("https://github.com/AChep/keyguard-app/compare/def456...abc123", snapshots.last().changelogUrl)

        // Repeated startup of the same build leaves the shared history unchanged.
        val count = snapshots.size
        log.value = listOf(entry("abc123"), entry("def456"))
        runCurrent()
        assertEquals(count, snapshots.size)
    }

    private suspend fun snapshot(refs: List<String>) = appInformationFlow(
        buildDate = flowOf("Sep 16, 2026"),
        buildRef = flowOf("abc123"),
        versionLog = flowOf(refs.map(::entry)),
    ).first()

    private fun entry(ref: String) = AppVersionLog(
        version = "3.2.1",
        ref = ref,
        timestamp = Instant.fromEpochSeconds(0),
    )
}
