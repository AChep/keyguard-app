@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.artemchep.keyguard.feature.home.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.artemchep.keyguard.common.io.bindBlocking
import com.artemchep.keyguard.common.model.AppUpdateLevel
import com.artemchep.keyguard.common.model.DAppUpdate
import com.artemchep.keyguard.common.service.keyvalue.impl.JsonKeyValueStore
import com.artemchep.keyguard.common.service.settings.impl.SettingsRepositoryImpl
import com.artemchep.keyguard.common.service.text.impl.Base64ServiceImpl
import com.artemchep.keyguard.common.usecase.GetAppUpdate
import com.artemchep.keyguard.common.usecase.GetAppVersion
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.common.usecase.impl.GetAppUpdateDelayImpl
import com.artemchep.keyguard.common.usecase.impl.GetAppUpdateDelayVariantsImpl
import com.artemchep.keyguard.common.usecase.impl.GetAppUpdateLevelImpl
import com.artemchep.keyguard.common.usecase.impl.PutAppUpdateDelayImpl
import com.artemchep.keyguard.common.usecase.impl.PutAppUpdateLevelImpl
import com.artemchep.keyguard.feature.home.settings.component.settingAboutAppProvider
import com.artemchep.keyguard.feature.home.settings.component.settingAppUpdateDelayProvider
import com.artemchep.keyguard.feature.home.settings.component.settingAppUpdateLevelProvider
import com.artemchep.keyguard.feature.home.vault.apple.RecordingNavigationController
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.ui.advanceTimeBy
import com.artemchep.keyguard.ui.mergedNodes
import com.artemchep.keyguard.ui.nodeWithText
import com.artemchep.keyguard.ui.texts
import com.artemchep.keyguard.ui.withScene
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

class AppUpdateSettingsTest {
    private val settings = SettingsRepositoryImpl(JsonKeyValueStore(), Json, Base64ServiceImpl())

    private val windowCoroutineScope = object : WindowCoroutineScope {
        override val coroutineContext = Dispatchers.Unconfined
    }

    private val url = "https://github.com/AChep/keyguard-app/releases/tag/r20261116"

    @Test
    fun `level picker saves the chosen level`() {
        val provider = settingAppUpdateLevelProvider(
            getAppUpdateLevel = GetAppUpdateLevelImpl(settings),
            putAppUpdateLevel = PutAppUpdateLevelImpl(settings),
            windowCoroutineScope = windowCoroutineScope,
        )
        val initial = runBlocking { assertNotNull(provider.first()) }
        assertTrue("update" in initial.search!!.tokens)
        withScene({ initial.content() }) {
            nodeWithText("All releases")
            clickText("Notify about updates")
            advanceTimeBy(1_000L)
            clickText("Major releases")
        }
        assertEquals(AppUpdateLevel.MAJOR, runBlocking { settings.getAppUpdateLevel().first() })

        val updated = runBlocking { assertNotNull(provider.first()) }
        withScene({ updated.content() }) {
            nodeWithText("Major releases")
        }
    }

    @Test
    fun `delay picker shows immediately for no delay and saves the chosen delay`() {
        settings.setAppUpdateDelay(Duration.ZERO).bindBlocking()
        val provider = settingAppUpdateDelayProvider(
            getAppUpdateDelay = GetAppUpdateDelayImpl(settings),
            getAppUpdateDelayVariants = GetAppUpdateDelayVariantsImpl(),
            putAppUpdateDelay = PutAppUpdateDelayImpl(settings),
            windowCoroutineScope = windowCoroutineScope,
            context = LeContext(),
        )
        val initial = runBlocking { assertNotNull(provider.first()) }
        withScene({ initial.content() }) {
            nodeWithText("Immediately")
            clickText("Wait after a release")
            advanceTimeBy(1_000L)
            clickText("3 days")
        }
        assertEquals(3.days, runBlocking { settings.getAppUpdateDelay().first() })
    }

    @Test
    fun `app version row shows no update without one`() {
        val item = appVersionItem(update = null)
        withScene({ item.content() }) {
            nodeWithText("3.7.0")
            val hasUpdate = mergedNodes().any { node ->
                node.texts().any { it.startsWith("Update available") }
            }
            assertTrue(!hasUpdate)
            assertNull(clickActionOrNull("App version"))
        }
    }

    @Test
    fun `app version row opens the link of an update`() {
        val item = appVersionItem(update = DAppUpdate(version = "3.8.2", url = url, eligibleTag = "r20261116"))
        val controller = RecordingNavigationController(CoroutineScope(Dispatchers.Unconfined))
        withScene({
            CompositionLocalProvider(LocalNavigationController provides controller) { item.content() }
        }) {
            nodeWithText("3.7.0")
            nodeWithText("Update available: 3.8.2")
            assertTrue(assertNotNull(clickActionOrNull("App version")).invoke())
        }
        val intent = assertNotNull(controller.intents.singleOrNull() as? NavigationIntent.NavigateToBrowser)
        assertEquals(url, intent.url)
    }

    @Test
    fun `app version row without a link is not clickable`() {
        val item = appVersionItem(update = DAppUpdate(version = "3.8.2", url = null, eligibleTag = "r20261116"))
        withScene({ item.content() }) {
            nodeWithText("Update available: 3.8.2")
            assertNull(clickActionOrNull("App version"))
        }
    }

    @Test
    fun `settings card opens the link only if there is one`() {
        val controller = RecordingNavigationController(CoroutineScope(Dispatchers.Unconfined))
        val linked = SettingsUpdateItem(
            id = "app_update",
            update = DAppUpdate(version = "3.8.2", url = url, eligibleTag = "r20261116"),
        )
        withScene({
            CompositionLocalProvider(LocalNavigationController provides controller) { SettingListUpdateItem(linked) }
        }) {
            nodeWithText("Keyguard 3.8.2")
            assertTrue(assertNotNull(clickActionOrNull("Update available")).invoke())
        }
        assertEquals(url, (controller.intents.single() as NavigationIntent.NavigateToBrowser).url)

        val unlinked = linked.copy(update = linked.update.copy(url = null))
        withScene({ SettingListUpdateItem(unlinked) }) {
            nodeWithText("Keyguard 3.8.2")
            assertNull(clickActionOrNull("Update available"))
        }
    }

    private fun appVersionItem(update: DAppUpdate?) = runBlocking {
        val provider = settingAboutAppProvider(
            getAppVersion = object : GetAppVersion {
                override fun invoke() = flowOf("3.7.0")
            },
            getAppUpdate = object : GetAppUpdate {
                override fun invoke() = flowOf(update)
            },
        )
        assertNotNull(provider.first())
    }
}

private fun ImageComposeScene.clickText(label: String) {
    assertTrue(assertNotNull(clickActionOrNull(label), "No click action for $label").invoke())
}

/** The click action of the row with the [label], or `null` if the row is not clickable. */
private fun ImageComposeScene.clickActionOrNull(label: String): (() -> Boolean)? =
    nodeWithText(label).config
        // A row without a click handler is a disabled clickable.
        .takeUnless { SemanticsProperties.Disabled in it }
        ?.getOrNull(SemanticsActions.OnClick)
        ?.action
