package com.artemchep.keyguard.apple.settings

import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.titleH
import com.artemchep.keyguard.common.usecase.GetAutofillCopyTotp
import com.artemchep.keyguard.common.usecase.GetAutofillDefaultMatchDetection
import com.artemchep.keyguard.common.usecase.GetAutofillSaveRequest
import com.artemchep.keyguard.common.usecase.GetAutofillSaveUri
import com.artemchep.keyguard.common.usecase.PutAutofillCopyTotp
import com.artemchep.keyguard.common.usecase.PutAutofillDefaultMatchDetection
import com.artemchep.keyguard.common.usecase.PutAutofillSaveRequest
import com.artemchep.keyguard.common.usecase.PutAutofillSaveUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AutofillSettingsControllerTest {
    @Test
    fun observesLocalizedOptionsAndPersistsSelection() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val fixture = Fixture(this)
            val snapshots = mutableListOf<AutofillSettingsSnapshot>()
            fixture.controller.observeAutofillSettings { snapshots += it }
            runCurrent()
            val initial = snapshots.last()
            assertTrue(initial.loaded)
            assertEquals(
                listOf("Domain", "Host", "StartsWith", "Exact", "RegularExpression", "Never"),
                initial.defaultMatchDetectionOptions.map { it.id },
            )
            assertEquals(fixture.titles, initial.defaultMatchDetectionOptions.map { it.title })
            assertEquals("Domain", initial.defaultMatchDetectionOptions.single { it.selected }.id)

            fixture.controller.setDefaultMatchDetection("Never")
            runCurrent()
            assertEquals(listOf(DSecret.Uri.MatchType.Never), fixture.writes)
            assertEquals("Never", snapshots.last().defaultMatchDetectionOptions.single { it.selected }.id)
            assertEquals("Jamais", snapshots.last().defaultMatchDetectionTitle)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun invalidIdsDoNotWriteOrResetThePreference() = runTest {
        val fixture = Fixture(this)
        listOf("", "Default", "host", "unknown", "1").forEach(fixture.controller::setDefaultMatchDetection)
        runCurrent()
        assertTrue(fixture.writes.isEmpty())
        assertEquals(DSecret.Uri.MatchType.Domain, fixture.saved.value)
    }

    private class Fixture(scope: TestScope) {
        val saved = MutableStateFlow(DSecret.Uri.MatchType.Domain)
        val writes = mutableListOf<DSecret.Uri.MatchType>()
        val titles = listOf("Domaine", "Hôte", "Commence par", "Exact", "Expression régulière", "Jamais")
        private val translations = DSecret.Uri.MatchType.entries.map { it.titleH() }.zip(titles).toMap()
        val controller = AutofillSettingsController(
            getAutofillCopyTotp = object : GetAutofillCopyTotp {
                override fun invoke() = MutableStateFlow(true)
            },
            putAutofillCopyTotp = object : PutAutofillCopyTotp {
                override fun invoke(value: Boolean) = ioEffect<Unit> { error("Unexpected write") }
            },
            getAutofillSaveRequest = object : GetAutofillSaveRequest {
                override fun invoke() = MutableStateFlow(true)
            },
            putAutofillSaveRequest = object : PutAutofillSaveRequest {
                override fun invoke(value: Boolean) = ioEffect<Unit> { error("Unexpected write") }
            },
            getAutofillSaveUri = object : GetAutofillSaveUri {
                override fun invoke() = MutableStateFlow(true)
            },
            putAutofillSaveUri = object : PutAutofillSaveUri {
                override fun invoke(value: Boolean) = ioEffect<Unit> { error("Unexpected write") }
            },
            getAutofillDefaultMatchDetection = object : GetAutofillDefaultMatchDetection {
                override fun invoke() = saved
            },
            putAutofillDefaultMatchDetection = object : PutAutofillDefaultMatchDetection {
                override fun invoke(value: DSecret.Uri.MatchType) = ioEffect {
                    writes += value
                    saved.value = value
                }
            },
            scope = scope.backgroundScope,
            text = { translations.getValue(it) },
        )
    }
}
