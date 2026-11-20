package com.artemchep.keyguard.apple.settings

import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.AppUpdateDelay
import com.artemchep.keyguard.common.model.AppUpdateLevel
import com.artemchep.keyguard.common.model.DAppUpdate
import com.artemchep.keyguard.common.usecase.GetAppUpdate
import com.artemchep.keyguard.common.usecase.GetAppUpdateDelay
import com.artemchep.keyguard.common.usecase.GetAppUpdateDelayVariants
import com.artemchep.keyguard.common.usecase.GetAppUpdateLevel
import com.artemchep.keyguard.common.usecase.PutAppUpdateDelay
import com.artemchep.keyguard.common.usecase.PutAppUpdateLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

@OptIn(ExperimentalCoroutinesApi::class)
class AppUpdateControllerTest {
    @Test
    fun observesLocalizedOptionsAndPersistsSelection() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val fixture = Fixture(this)
            val snapshots = mutableListOf<AppUpdateSettingsSnapshot>()
            fixture.controller.observeAppUpdateSettings { snapshots += it }
            runCurrent()
            val initial = snapshots.last()
            assertEquals(listOf("off", "major", "minor", "all"), initial.levelOptions.map { it.id })
            assertEquals("all", initial.levelOptions.single { it.selected }.id)
            assertEquals("Tout", initial.levelOptions.single { it.selected }.title)
            assertEquals(
                AppUpdateDelay.variants.map { it.inWholeMilliseconds.toString() },
                initial.delayOptions.map { it.id },
            )
            assertEquals("Immédiatement", initial.delayOptions.first().title)
            assertEquals(7.days.inWholeMilliseconds.toString(), initial.delayOptions.single { it.selected }.id)
            assertEquals("7d", initial.delayOptions.single { it.selected }.title)

            fixture.controller.setLevel("major")
            fixture.controller.setDelay("0")
            runCurrent()
            assertEquals(listOf(AppUpdateLevel.MAJOR), fixture.levelWrites)
            assertEquals(listOf(Duration.ZERO), fixture.delayWrites)
            assertEquals("Majeures", snapshots.last().levelOptions.single { it.selected }.title)
            assertEquals("Immédiatement", snapshots.last().delayOptions.single { it.selected }.title)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun invalidIdsDoNotWrite() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val fixture = Fixture(this)
            // Before the first snapshot there are no known delay options.
            fixture.controller.setDelay("0")
            fixture.controller.observeAppUpdateSettings { }
            runCurrent()
            listOf("", "ALL", "MAJOR", "unknown").forEach(fixture.controller::setLevel)
            listOf("", "1", "7d", "86400").forEach(fixture.controller::setDelay)
            runCurrent()
            assertTrue(fixture.levelWrites.isEmpty())
            assertTrue(fixture.delayWrites.isEmpty())
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun observesTheUpdateOrItsAbsence() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val fixture = Fixture(this)
            val updates = mutableListOf<AppUpdateSnapshot?>()
            fixture.controller.observeAppUpdate { updates += it }
            runCurrent()
            assertNull(updates.single())

            fixture.update.value = DAppUpdate(version = "3.9.0", url = null, eligibleTag = "r20261120")
            runCurrent()
            assertEquals(AppUpdateSnapshot(version = "3.9.0", url = null), updates.last())

            val url = "https://github.com/AChep/keyguard-app/releases/tag/r20261120"
            fixture.update.value = DAppUpdate(version = "3.9.0", url = url, eligibleTag = "r20261120")
            runCurrent()
            assertEquals(AppUpdateSnapshot(version = "3.9.0", url = url), updates.last())
        } finally {
            Dispatchers.resetMain()
        }
    }

    private class Fixture(scope: TestScope) {
        val update = MutableStateFlow<DAppUpdate?>(null)
        val level = MutableStateFlow(AppUpdateLevel.ALL)
        val delay = MutableStateFlow(AppUpdateDelay.DEFAULT)
        val levelWrites = mutableListOf<AppUpdateLevel>()
        val delayWrites = mutableListOf<Duration>()
        private val levelTitles = mapOf(
            AppUpdateLevel.OFF.title to "Désactivé",
            AppUpdateLevel.MAJOR.title to "Majeures",
            AppUpdateLevel.MINOR.title to "Mineures",
            AppUpdateLevel.ALL.title to "Tout",
        )
        val controller = AppUpdateController(
            getAppUpdate = object : GetAppUpdate {
                override fun invoke() = update
            },
            getAppUpdateLevel = object : GetAppUpdateLevel {
                override fun invoke() = level
            },
            putAppUpdateLevel = object : PutAppUpdateLevel {
                override fun invoke(value: AppUpdateLevel) = ioEffect {
                    levelWrites += value
                    level.value = value
                }
            },
            getAppUpdateDelay = object : GetAppUpdateDelay {
                override fun invoke() = delay
            },
            getAppUpdateDelayVariants = object : GetAppUpdateDelayVariants {
                override fun invoke() = flowOf(AppUpdateDelay.variants)
            },
            putAppUpdateDelay = object : PutAppUpdateDelay {
                override fun invoke(value: Duration) = ioEffect {
                    delayWrites += value
                    delay.value = value
                }
            },
            scope = scope.backgroundScope,
            text = { levelTitles.getValue(it) },
            formatDelay = { if (it == Duration.ZERO) "Immédiatement" else it.toString() },
        )
    }
}
