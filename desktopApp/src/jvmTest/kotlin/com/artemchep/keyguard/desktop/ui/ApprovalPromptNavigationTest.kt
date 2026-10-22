package com.artemchep.keyguard.desktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import com.artemchep.keyguard.common.model.NavAnimation
import com.artemchep.keyguard.common.service.keyboard.KeyboardShortcutsServiceHost
import com.artemchep.keyguard.common.service.keyvalue.impl.JsonKeyValueStore
import com.artemchep.keyguard.common.usecase.GetNavAnimation
import com.artemchep.keyguard.feature.navigation.BackHandler
import com.artemchep.keyguard.feature.navigation.DialogRoute
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.NavigationRestoreRepository
import com.artemchep.keyguard.feature.navigation.NavigationRouterBackHandler
import com.artemchep.keyguard.feature.navigation.NavigationStackPersistence
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.platform.WindowId
import java.lang.reflect.InvocationTargetException
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.Json
import org.koin.compose.KoinContext
import org.koin.core.Koin
import org.koin.dsl.module

@OptIn(ExperimentalComposeUiApi::class)
class ApprovalPromptNavigationTest {
    @Test
    fun `standalone approval renders and can open an access verification dialog`() {
        try {
            SwingUtilities.invokeAndWait {
                renderApproval()
            }
        } catch (error: InvocationTargetException) {
            throw error.targetException
        }
    }

    @Test
    fun `queued approval replaces the previous request and its verification dialog`() {
        try {
            SwingUtilities.invokeAndWait {
                renderApproval(replaceRequest = true)
            }
        } catch (error: InvocationTargetException) {
            throw error.targetException
        }
    }

    private fun renderApproval(replaceRequest: Boolean = false) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val koin = Koin().apply {
            loadModules(listOf(TestModule(scope).module))
        }
        val backHandler = BackHandler()
        var navigation: NavigationController? = null
        val onNavigation = { controller: NavigationController -> navigation = controller }
        var request by mutableStateOf<Pair<String, Route>?>(
            "test-request" to approvalRoute("Environment approval", onNavigation),
        )
        try {
            ImageComposeScene(
                width = 320,
                height = 420,
                density = Density(1f),
                content = {
                    ApprovalContent(koin, scope, backHandler, request)
                },
            ).use { scene ->
                scene.render(0).close()
                assertTrue(scene.hasText("Environment approval"))

                val verification = object : DialogRoute {
                    @Composable
                    override fun Content() {
                        Text("Verify environment access")
                    }
                }
                assertNotNull(navigation).queue(NavigationIntent.NavigateToRoute(verification))
                scene.render(1_000_000_000).close()
                scene.render(2_000_000_000).close()
                assertTrue(scene.hasText("Verify environment access"))

                if (replaceRequest) {
                    // Advance directly to the next pending request. The approval window
                    // remains in the composition when the queue never becomes empty.
                    request = "next-request" to approvalRoute("Next environment approval", onNavigation)
                    scene.render(3_000_000_000).close()
                    scene.render(4_000_000_000).close()
                    assertTrue(scene.hasText("Next environment approval"))
                    assertFalse(scene.hasText("Environment approval"))
                    assertFalse(scene.hasText("Verify environment access"))

                    assertNotNull(navigation).queue(NavigationIntent.NavigateToRoute(verification))
                    scene.render(5_000_000_000).close()
                    scene.render(6_000_000_000).close()
                    assertTrue(scene.hasText("Verify environment access"))

                    request = null
                    scene.render(7_000_000_000).close()
                    assertFalse(scene.hasText("Next environment approval"))
                    assertFalse(scene.hasText("Verify environment access"))

                    request = "later-request" to approvalRoute("Later environment approval", onNavigation)
                    scene.render(8_000_000_000).close()
                    assertTrue(scene.hasText("Later environment approval"))
                    assertNotNull(navigation).queue(NavigationIntent.NavigateToRoute(verification))
                    scene.render(9_000_000_000).close()
                    scene.render(10_000_000_000).close()
                    assertTrue(scene.hasText("Verify environment access"))
                }
            }
        } finally {
            scope.cancel()
            koin.close()
        }
    }

    @Composable
    private fun ApprovalContent(
        koin: Koin,
        scope: CoroutineScope,
        backHandler: BackHandler,
        request: Pair<String, Route>?,
    ) {
        KoinContext(koin) {
            MaterialTheme {
                NavigationRouterBackHandler(handler = backHandler) {
                    NavigationController(scope = scope, canPop = flowOf(false), handle = { it }) {
                        request?.let { (id, route) ->
                            ApprovalPromptNavigation(requestId = id, route = route)
                        }
                    }
                }
            }
        }
    }

    private fun approvalRoute(
        label: String,
        onNavigation: (NavigationController) -> Unit,
    ) = object : Route {
        @Composable
        override fun Content() {
            val controller = LocalNavigationController.current
            SideEffect { onNavigation(controller) }
            Text(label)
        }
    }

    private fun ImageComposeScene.hasText(text: String): Boolean =
        semanticsOwners.flatMap { descendants(it.rootSemanticsNode) }.any { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true
        }

    private fun descendants(node: SemanticsNode): List<SemanticsNode> =
        listOf(node) + node.children.flatMap(::descendants)

    private class TestModule(scope: CoroutineScope) {
        val module = module {
            single {
                NavigationRestoreRepository(NavigationStackPersistence(JsonKeyValueStore(), Json), scope)
            }
            single<KeyboardShortcutsServiceHost> {
                object : KeyboardShortcutsServiceHost {
                    override fun register(windowId: WindowId, block: (KeyEvent) -> Boolean): () -> Unit = {}
                }
            }
            single<GetNavAnimation> {
                object : GetNavAnimation {
                    override fun invoke() = MutableStateFlow(NavAnimation.DISABLED)
                }
            }
        }
    }
}
