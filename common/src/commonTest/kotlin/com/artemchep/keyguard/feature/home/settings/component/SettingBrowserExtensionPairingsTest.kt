package com.artemchep.keyguard.feature.home.settings.component

import com.artemchep.keyguard.common.service.browser.BrowserExtensionService
import com.artemchep.keyguard.common.service.browser.BrowserExtensionState
import com.artemchep.keyguard.common.service.browser.BrowserPairing
import com.artemchep.keyguard.common.service.browser.UnsupportedBrowserExtension
import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.feature.confirmation.ConfirmationRouteFactoryDefault
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

@OptIn(ExperimentalCoroutinesApi::class)
class SettingBrowserExtensionPairingsTest {
    private val work = BrowserPairing(
        id = "work",
        name = "Work Chrome",
        origin = "chrome-extension://abcdefghijklmnopabcdefghijklmnop/",
        createdAt = Instant.fromEpochSeconds(1_000),
        lastUsedAt = Instant.fromEpochSeconds(2_000),
    )

    private val home = BrowserPairing(
        id = "home",
        name = "Firefox",
        origin = "keyguard@artemchep.com",
        createdAt = Instant.fromEpochSeconds(3_000),
        lastUsedAt = Instant.fromEpochSeconds(4_000),
    )

    private val stubDateFormatter = object : DateFormatter {
        override fun formatDateTime(instant: Instant): String = instant.toString()
        override fun formatDate(instant: Instant): String = instant.toString()
        override suspend fun formatDateShort(date: LocalDate): String = date.toString()
        override fun formatDateMedium(date: LocalDate): String = date.toString()
        override fun formatTimeShort(time: LocalTime): String = time.toString()
    }

    private fun provider(
        state: MutableStateFlow<BrowserExtensionState>,
    ): SettingComponent {
        val service = object : BrowserExtensionService by UnsupportedBrowserExtension {
            override val state = state
        }
        return settingBrowserExtensionPairingsProvider(
            browserExtensionService = service,
            dateFormatter = stubDateFormatter,
            confirmationRouteFactory = ConfirmationRouteFactoryDefault,
        )
    }

    @Test
    fun `hidden when supported with no paired browsers`() = runTest {
        val state = MutableStateFlow(
            BrowserExtensionState(
                enabled = true,
                supported = true,
                pairings = emptyList(),
            ),
        )
        assertNull(provider(state).first())
    }

    @Test
    fun `hidden when unsupported even if pairings are present`() = runTest {
        val state = MutableStateFlow(
            BrowserExtensionState(
                enabled = true,
                supported = false,
                pairings = listOf(work),
            ),
        )
        assertNull(provider(state).first())
    }

    @Test
    fun `shown when supported with paired browsers`() = runTest {
        val state = MutableStateFlow(
            BrowserExtensionState(
                enabled = true,
                supported = true,
                pairings = listOf(work),
            ),
        )
        val item = assertNotNull(provider(state).first())
        assertEquals(browserExtensionPlatformClasses, item.platformClasses)
        assertNull(item.search)
    }

    @Test
    fun `updates reactively and ignores changes while still non-empty`() = runTest {
        val state = MutableStateFlow(
            BrowserExtensionState(
                enabled = true,
                supported = true,
                pairings = emptyList(),
            ),
        )
        val emissions = mutableListOf<Boolean>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            provider(state).collect { item ->
                emissions += (item != null)
            }
        }

        assertEquals(listOf(false), emissions)

        state.value = state.value.copy(pairings = listOf(work))
        assertEquals(listOf(false, true), emissions)

        // Adding another pairing keeps visibility true and does not re-emit.
        state.value = state.value.copy(pairings = listOf(work, home))
        assertEquals(listOf(false, true), emissions)

        // Removing all pairings hides the section again.
        state.value = state.value.copy(pairings = emptyList())
        assertEquals(listOf(false, true, false), emissions)

        job.cancel()
    }
}
