package com.artemchep.keyguard.feature.agent

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.service.agent.AgentPrompt
import com.artemchep.keyguard.common.service.browser.BrowserApprovalRequest
import com.artemchep.keyguard.common.service.browser.BrowserOperation
import com.artemchep.keyguard.common.service.environment.EnvironmentAccount
import com.artemchep.keyguard.common.service.environment.EnvironmentCapability
import com.artemchep.keyguard.common.service.environment.EnvironmentCatalog
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentOperation
import com.artemchep.keyguard.common.service.passkey.PasskeyApprovalOperation
import com.artemchep.keyguard.common.service.passkey.PasskeyApprovalRequest
import com.artemchep.keyguard.feature.agent.sample.ApprovalPromptSample
import com.artemchep.keyguard.feature.browser.approval.BrowserApprovalContent
import com.artemchep.keyguard.feature.browser.approval.BrowserPasskeyPickContent
import com.artemchep.keyguard.feature.cli.approval.CliApprovalContent
import com.artemchep.keyguard.feature.environments.approval.EnvironmentApprovalContent
import com.artemchep.keyguard.feature.gpgagent.GpgAgentApprovalContent
import com.artemchep.keyguard.feature.passkeys.approval.PasskeyApprovalContent
import com.artemchep.keyguard.feature.sshagent.SshAgentApprovalContent
import com.artemchep.keyguard.ui.surface.LocalBackgroundManager
import com.artemchep.keyguard.ui.surface.LocalSurfaceColor
import com.artemchep.keyguard.ui.texts
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.wizard.WizardPreview
import com.artemchep.keyguard.ui.wizard.renderScene
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/** The size of the approval window, see AgentRequestWindow. */
private const val WINDOW_WIDTH = 320
private const val WINDOW_HEIGHT = 420

/** The margin around the prompt in the approval window, see KeyguardPopupScaffold. */
private const val WINDOW_INSET = 16

/**
 * Renders every sample approval prompt at the size of the approval window,
 * to build/reports/approval-prompts, and checks that its item and buttons
 * show. Nothing compares the images: they are for review by eye.
 */
class ApprovalPromptSamplesScreenTest {
    private val variants = listOf(
        Variant("light"),
        Variant("dark", dark = true),
        Variant("large-text", fontScale = 1.5f),
    )

    @Test
    fun `samples fit the approval window in light dark and large text`() {
        val expiresAt = Clock.System.now() + 1.minutes
        val failures = mutableListOf<String>()
        SwingUtilities.invokeAndWait {
            ApprovalPromptSample.entries.forEach { sample ->
                val prompt = sample.build(id = "sample-${sample.name}", expiresAt = expiresAt)
                variants.forEach { variant ->
                    failures += render(
                        name = "${sample.name}-${variant.name}",
                        prompt = prompt,
                        variant = variant,
                    )
                }
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString(separator = "\n"))
    }

    /** Renders the [prompt] with armed buttons; returns what is wrong with it. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun render(
        name: String,
        prompt: AgentPrompt,
        variant: Variant,
    ): List<String> {
        val expected = expectation(prompt)
        val failures = mutableListOf<String>()
        // The buttons arm after a second of focus with no input.
        val guard = AgentApprovalInputGuard()
        val scheduler = TestCoroutineScheduler()
        val scope = CoroutineScope(UnconfinedTestDispatcher(scheduler))
        scope.launch { guard.run(focused = true) }
        try {
            renderScene(
                folder = "build/reports/approval-prompts",
                name = name,
                width = WINDOW_WIDTH,
                height = WINDOW_HEIGHT,
                fontScale = variant.fontScale,
                rtl = false,
                beforeSettle = {
                    scheduler.advanceTimeBy(1.seconds)
                    scheduler.runCurrent()
                },
                content = {
                    WizardPreview(dark = variant.dark) {
                        CompositionLocalProvider(LocalAgentApprovalInputGuard provides guard) {
                            ApprovalWindowFrame {
                                SampleContent(prompt)
                            }
                        }
                    }
                },
            ) { nodes ->
                failures += check(name, nodes, expected)
            }
        } finally {
            scope.cancel()
        }
        return failures
    }

    private fun check(
        name: String,
        nodes: List<SemanticsNode>,
        expected: Expectation,
    ): List<String> = buildList {
        fun nodeWithText(text: String) = nodes.firstOrNull { text in it.texts() }

        if (nodeWithText(expected.title) == null) {
            add("$name: no title \"${expected.title}\"")
        }
        val item = nodeWithText(expected.item)
        when {
            item == null -> add("$name: no item \"${expected.item}\"")
            SemanticsProperties.Disabled in item.config -> add("$name: the item \"${expected.item}\" is disabled")
        }
        // The prompt's own surface, inside the window's margin.
        val viewport = Rect(
            left = WINDOW_INSET.toFloat(),
            top = WINDOW_INSET.toFloat(),
            right = (WINDOW_WIDTH - WINDOW_INSET).toFloat(),
            bottom = (WINDOW_HEIGHT - WINDOW_INSET).toFloat(),
        )
        listOf(expected.deny, expected.approve).forEach { text ->
            val button = nodeWithText(text)
            if (button == null) {
                add("$name: no button \"$text\"")
                return@forEach
            }
            val bounds = button.boundsInRoot
            val visible = bounds.width > 0 && bounds.height > 0 &&
                bounds.left >= viewport.left && bounds.right <= viewport.right &&
                bounds.top >= viewport.top && bounds.bottom <= viewport.bottom
            if (!visible) {
                add("$name: the button \"$text\" at $bounds is outside of $viewport")
            }
        }
    }

    private data class Variant(
        val name: String,
        val dark: Boolean = false,
        val fontScale: Float = 1f,
    )
}

/** The texts a prompt must show. */
private data class Expectation(
    val title: String,
    /** The title of the prompt's main item, or the label of its field. */
    val item: String,
    val deny: String = "Deny",
    val approve: String = "Approve",
)

private fun expectation(prompt: AgentPrompt): Expectation = when (prompt) {
    is AgentPrompt.Ssh -> Expectation(
        title = "SSH Signing Request",
        item = prompt.request.keyName,
        deny = "Cancel",
        approve = "OK",
    )

    is AgentPrompt.Gpg -> Expectation(
        title = when (prompt.request.operation) {
            GpgAgentOperation.SIGN -> "GPG Signing Request"
            GpgAgentOperation.DECRYPT -> "GPG Decryption Request"
        },
        item = prompt.request.keyName,
        deny = "Cancel",
        approve = "OK",
    )

    is AgentPrompt.Cli -> Expectation(
        title = "Environment access request",
        item = "Run a command with this environment",
    )

    is AgentPrompt.Environment -> Expectation(
        title = "Environment access request",
        item = when (val capability = prompt.request.capability) {
            EnvironmentCapability.CreateLocalEnvFile -> "Create a local .env file"
            EnvironmentCapability.ListEnvironments -> "List environments"
            else -> error("Add the label of $capability")
        },
    )

    is AgentPrompt.Browser -> browserExpectation(prompt.request.operation)
    is AgentPrompt.Passkey -> passkeyExpectation(prompt.request.operation)
    is AgentPrompt.Sample -> expectation(prompt.prompt)
}

private fun browserExpectation(operation: BrowserOperation): Expectation = when (operation) {
    is BrowserOperation.Pair -> Expectation(
        title = "Pair a browser",
        item = "Browser name",
    )

    is BrowserOperation.Release -> Expectation(
        title = "Browser extension request",
        item = operation.release.name,
    )

    is BrowserOperation.PasskeyPick -> Expectation(
        title = "Sign in with a passkey",
        item = operation.choices.first().release.name,
    )

    BrowserOperation.Unlock -> error("An unlock prompt shows the unlock screen")
}

private fun passkeyExpectation(operation: PasskeyApprovalOperation): Expectation = when (operation) {
    is PasskeyApprovalOperation.SignIn -> Expectation(
        title = "Sign in with a passkey",
        item = operation.choices.first().name,
    )

    PasskeyApprovalOperation.Unlock -> error("An unlock prompt shows the unlock screen")
}

/** Resolves the account of the samples by its ID. */
private val sampleEnvironmentCatalog = EnvironmentCatalog(
    accounts = listOf(
        EnvironmentAccount(
            id = ApprovalPromptSample.SAMPLE_ACCOUNT_ID,
            name = "Personal",
        ),
    ),
    unlocked = true,
)

/** The [prompt] as the approval window shows it, with callbacks that do nothing. */
@Composable
private fun SampleContent(prompt: AgentPrompt) {
    when (prompt) {
        is AgentPrompt.Ssh -> SshAgentApprovalContent(
            request = prompt.request,
            onDecision = { _, _ -> },
        )

        is AgentPrompt.Gpg -> GpgAgentApprovalContent(
            request = prompt.request,
            onDecision = { _, _ -> },
        )

        is AgentPrompt.Cli -> CliApprovalContent(
            request = prompt.request,
            busy = false,
            onDeny = {},
            onAllow = {},
        )

        is AgentPrompt.Environment -> EnvironmentApprovalContent(
            request = prompt.request,
            state = sampleEnvironmentCatalog,
            busy = false,
            onDeny = {},
            onAllow = {},
        )

        is AgentPrompt.Browser -> BrowserSampleContent(prompt.request)
        is AgentPrompt.Passkey -> PasskeySampleContent(prompt.request)
        is AgentPrompt.Sample -> SampleContent(prompt.prompt)
    }
}

@Composable
private fun BrowserSampleContent(request: BrowserApprovalRequest) {
    when (val operation = request.operation) {
        is BrowserOperation.PasskeyPick -> {
            var selected by remember { mutableIntStateOf(0) }
            BrowserPasskeyPickContent(
                request = request,
                operation = operation,
                selected = selected,
                onSelect = { selected = it },
                onAnotherDevice = {},
                busy = false,
                onDeny = {},
                onAllow = {},
            )
        }

        is BrowserOperation.Pair,
        is BrowserOperation.Release,
        -> {
            var name by remember {
                mutableStateOf((operation as? BrowserOperation.Pair)?.suggestedName.orEmpty())
            }
            var addUri by remember { mutableStateOf(false) }
            BrowserApprovalContent(
                request = request,
                name = name,
                onNameChange = { name = it },
                addUri = addUri,
                onAddUriChange = { addUri = it },
                busy = false,
                onDeny = {},
                onAllow = {},
            )
        }

        BrowserOperation.Unlock -> error("An unlock prompt shows the unlock screen")
    }
}

@Composable
private fun PasskeySampleContent(request: PasskeyApprovalRequest) {
    when (val operation = request.operation) {
        is PasskeyApprovalOperation.SignIn -> {
            var selected by remember { mutableIntStateOf(0) }
            PasskeyApprovalContent(
                request = request,
                operation = operation,
                selected = selected,
                onSelect = { selected = it },
                busy = false,
                onDeny = {},
                onAllow = {},
            )
        }

        PasskeyApprovalOperation.Unlock -> error("An unlock prompt shows the unlock screen")
    }
}

/** The frame of the approval window: a rounded surface inside a margin, see KeyguardPopupScaffold. */
@Composable
private fun ApprovalWindowFrame(
    content: @Composable () -> Unit,
) {
    val containerColor = LocalBackgroundManager.current.colorHighest
    val contentColor = contentColorFor(containerColor)
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .padding(WINDOW_INSET.dp),
        shape = RoundedCornerShape(16.dp),
        color = containerColor,
        border = BorderStroke(
            width = 1.dp,
            color = contentColor
                .combineAlpha(0.12f)
                .compositeOver(containerColor),
        ),
        contentColor = contentColor,
    ) {
        CompositionLocalProvider(
            LocalSurfaceColor provides containerColor,
            content = content,
        )
    }
}
