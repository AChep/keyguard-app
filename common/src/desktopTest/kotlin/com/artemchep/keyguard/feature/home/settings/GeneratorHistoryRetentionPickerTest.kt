@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.artemchep.keyguard.feature.home.settings

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.use
import com.artemchep.keyguard.common.service.keyvalue.impl.JsonKeyValueStore
import com.artemchep.keyguard.common.service.settings.impl.SettingsRepositoryImpl
import com.artemchep.keyguard.common.service.text.impl.Base64ServiceImpl
import com.artemchep.keyguard.common.usecase.GetGeneratorHistoryRetention
import com.artemchep.keyguard.common.usecase.GetHistoryRetentionVariants
import com.artemchep.keyguard.common.usecase.PutGeneratorHistoryRetention
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.common.usecase.impl.GetGeneratorHistoryRetentionImpl
import com.artemchep.keyguard.common.usecase.impl.GetHistoryRetentionVariantsImpl
import com.artemchep.keyguard.common.usecase.impl.PutGeneratorHistoryRetentionImpl
import com.artemchep.keyguard.feature.home.settings.component.settingGeneratorHistoryRetentionProvider
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.ui.wizard.WizardPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.koin.core.Koin
import org.koin.core.qualifier.named
import org.koin.dsl.module
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days

class GeneratorHistoryRetentionPickerTest {
    @Test
    fun `searchable security picker shows the saved period after selection`() {
        val settings = SettingsRepositoryImpl(JsonKeyValueStore(), Json, Base64ServiceImpl())
        val koin = Koin()
        val fixture = module {
            single<GetGeneratorHistoryRetention> { GetGeneratorHistoryRetentionImpl(settings) }
            single<PutGeneratorHistoryRetention> { PutGeneratorHistoryRetentionImpl(settings) }
            single<GetHistoryRetentionVariants> { GetHistoryRetentionVariantsImpl() }
            single { LeContext() }
            single<WindowCoroutineScope> {
                object : WindowCoroutineScope {
                    override val coroutineContext = Dispatchers.Unconfined
                }
            }
        }
        koin.loadModules(listOf(fixture))
        try {
            val scope = koin.createScope("test", named<GeneratorHistoryRetentionPickerTest>())
            val provider = settingGeneratorHistoryRetentionProvider(scope)
            val initial = runBlocking { assertNotNull(provider.first()) }
            assertEquals("security", initial.search?.group)
            assertTrue("generator" in initial.search!!.tokens)
            SwingUtilities.invokeAndWait {
                ImageComposeScene(width = 600, height = 600) { WizardPreview { initial.content() } }.use { scene ->
                    scene.settle()
                    scene.nodeWithText("Never")
                    scene.clickText("Delete generator history after")
                    scene.settle(1_000_000_000L)
                    scene.clickText("1 month")
                }
            }
            val updated = runBlocking {
                assertEquals(30.days, settings.getGeneratorHistoryRetention().first())
                assertNotNull(provider.first())
            }
            SwingUtilities.invokeAndWait {
                ImageComposeScene(width = 600, height = 600) { WizardPreview { updated.content() } }.use { scene ->
                    scene.settle()
                    scene.nodeWithText("1 month")
                }
            }
        } finally {
            koin.close()
        }
    }

    private fun ImageComposeScene.settle(startNanos: Long = 0L) {
        repeat(4) { frame -> render(startNanos + frame * 200_000_000L).close() }
    }

    private fun ImageComposeScene.clickText(label: String) {
        val node = nodeWithText(label)
        val click = generateSequence(node) { it.parent }
            .firstNotNullOf { it.config.getOrNull(SemanticsActions.OnClick)?.action }
        assertTrue(click())
    }

    private fun ImageComposeScene.nodeWithText(label: String): SemanticsNode {
        fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
        return semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }.first {
            it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == label } == true
        }
    }
}
