package com.artemchep.keyguard.feature.home.settings.component

import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserAutofillIntegrationTest {
    @Test
    fun `browser targets use supported packages in stable order`() {
        assertEquals(
            listOf(
                "Brave" to "com.brave.browser",
                "Chrome" to "com.android.chrome",
                "Chrome Beta" to "com.chrome.beta",
                "Edge" to "com.microsoft.emmx",
                "Vivaldi" to "com.vivaldi.browser",
            ),
            BrowserAutofillTarget.entries.map { it.displayName to it.packageName },
        )
    }

    @Test
    fun `provider URIs use each browser package`() {
        BrowserAutofillTarget.entries.forEach { target ->
            assertEquals(
                "content://${target.packageName}" +
                    ".AutofillThirdPartyModeContentProvider/autofill_third_party_mode",
                target.providerUri(),
            )
        }
    }

    @Test
    fun `provider state parser handles enabled disabled and unavailable results`() {
        assertFalse(
            queryBrowserAutofillState {
                BrowserAutofillProviderSnapshot(
                    hasFirstRow = true,
                    columnValue = 0,
                )
            }!!,
        )
        assertTrue(
            queryBrowserAutofillState {
                BrowserAutofillProviderSnapshot(
                    hasFirstRow = true,
                    columnValue = 1,
                )
            }!!,
        )
        assertTrue(
            queryBrowserAutofillState {
                BrowserAutofillProviderSnapshot(
                    hasFirstRow = true,
                    columnValue = -1,
                )
            }!!,
        )
        assertNull(queryBrowserAutofillState { null })
        assertNull(
            queryBrowserAutofillState {
                BrowserAutofillProviderSnapshot(
                    hasFirstRow = false,
                    columnValue = 1,
                )
            },
        )
        assertNull(
            queryBrowserAutofillState {
                BrowserAutofillProviderSnapshot(
                    hasFirstRow = true,
                    columnValue = null,
                )
            },
        )
        assertNull(
            queryBrowserAutofillState {
                throw IllegalStateException("Provider query failed")
            },
        )
    }

    @Test
    fun `status loader filters unavailable browsers and preserves target order`() = runTest {
        val states = mapOf(
            BrowserAutofillTarget.BRAVE_STABLE to true,
            BrowserAutofillTarget.CHROME_STABLE to null,
            BrowserAutofillTarget.CHROME_BETA to false,
            BrowserAutofillTarget.VIVALDI_STABLE to true,
        )

        assertEquals(
            listOf(
                BrowserAutofillStatus(BrowserAutofillTarget.BRAVE_STABLE, true),
                BrowserAutofillStatus(BrowserAutofillTarget.CHROME_BETA, false),
                BrowserAutofillStatus(BrowserAutofillTarget.VIVALDI_STABLE, true),
            ),
            loadBrowserAutofillStatuses { states[it] },
        )
        assertEquals(
            emptyList<BrowserAutofillStatus>(),
            loadBrowserAutofillStatuses { null },
        )
    }

    @Test
    fun `status observer emits initially refreshes and deduplicates unchanged states`() = runTest {
        val initialStatus = listOf(
            BrowserAutofillStatus(BrowserAutofillTarget.CHROME_STABLE, false),
        )
        val updatedStatus = listOf(
            BrowserAutofillStatus(BrowserAutofillTarget.CHROME_STABLE, true),
        )
        var loadCount = 0

        val states = observeBrowserAutofillStatuses(
            refreshEvents = flowOf(Unit, Unit),
            loadStatuses = {
                when (loadCount++) {
                    0, 1 -> initialStatus
                    else -> updatedStatus
                }
            },
        ).toList()

        assertEquals(listOf(initialStatus, updatedStatus), states)
        assertEquals(
            listOf(emptyList<BrowserAutofillStatus>()),
            observeBrowserAutofillStatuses(
                refreshEvents = emptyFlow(),
                loadStatuses = { emptyList() },
            ).toList(),
        )
    }

    @Test
    fun `settings requests target browser autofill preferences`() {
        BrowserAutofillTarget.entries.forEach { target ->
            assertEquals(
                BrowserAutofillSettingsRequest(
                    action = "android.intent.action.APPLICATION_PREFERENCES",
                    categories = setOf(
                        "android.intent.category.DEFAULT",
                        "android.intent.category.APP_BROWSER",
                        "android.intent.category.PREFERENCE",
                    ),
                    packageName = target.packageName,
                ),
                target.settingsRequest(),
            )
        }
    }
}
