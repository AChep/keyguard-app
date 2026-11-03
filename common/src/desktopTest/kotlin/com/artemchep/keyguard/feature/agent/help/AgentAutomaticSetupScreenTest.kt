@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.artemchep.keyguard.feature.agent.help

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.use
import com.artemchep.keyguard.ui.wizard.WizardPreview
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Check real wizard layouts, accessible choices, and the fixed primary action without app services. */
class AgentAutomaticSetupScreenTest {
    @Test
    fun `SSH and GPG steps fit compact desktop dark and large text RTL layouts`() {
        SwingUtilities.invokeAndWait {
            val variants = listOf(
                Variant("compact", 390, 844),
                Variant("desktop-dark", 1024, 800, dark = true),
                Variant("large-text-rtl", 390, 844, fontScale = 1.6f, rtl = true),
            )
            variants.forEach { variant ->
                SetupAgent.entries.forEach { agent ->
                    AgentSetupStep.entries.forEach { step ->
                        val name = "${variant.name}-${agent.name}-${step.name}"
                        render(variant, agentSetupPreviewData(step, agent), name)
                    }
                }
            }
        }
    }

    @Test
    fun `lines that can take precedence render separately from file edits`() {
        SwingUtilities.invokeAndWait {
            val review = agentSetupPreviewData(AgentSetupStep.REVIEW).copy(
                selectedShells = persistentSetOf(SetupShell.ZSH),
            )
            val plan = review.plan!!.copy(
                edits = persistentListOf(review.plan.edits.first()),
                overrides = persistentListOf(AgentSetupOverride(
                    "SSH_AUTH_SOCK", "/home/alex/.config/zsh/.zlogin", 42,
                )),
            )
            listOf(Variant("compact", 390, 844), Variant("large-rtl", 390, 844, fontScale = 1.6f, rtl = true))
                .forEach { variant ->
                    render(variant, review.copy(plan = plan), "${variant.name}-review-mixed")
                    render(variant, review.copy(plan = plan.copy(edits = persistentListOf())),
                        "${variant.name}-review-overrides")
                }
        }
    }

    @Test
    fun `review errors busy states undo and external guidance render`() {
        SwingUtilities.invokeAndWait {
            val review = agentSetupPreviewData(AgentSetupStep.REVIEW)
            val result = agentSetupPreviewData(AgentSetupStep.RESULT)
            val states = mapOf(
                "review-conflict" to review.copy(plan = review.plan!!.copy(
                    conflicts = persistentListOf(
                        AgentSetupConflict(AgentSetupConflict.Kind.ZDOTDIR, "/home/alex/.zshenv"),
                        AgentSetupConflict(AgentSetupConflict.Kind.COMPILED_STARTUP, "/home/alex/.zshrc"),
                    ),
                )),
                "review-failed" to review.copy(
                    plan = null,
                    error = AgentSetupError(
                        kind = AgentSetupError.Kind.UNSAFE_FILE,
                        path = "/home/alex/.zshrc",
                        detail = "cannot safely edit /home/alex/.zshrc: refusing to follow a symbolic link",
                    ),
                ),
                "review-required" to review.copy(plan = null),
                "review-empty" to review.copy(plan = review.plan.copy(edits = persistentListOf())),
                "review-disabled" to review.copy(enabled = false, plan = null),
                "review-one-file" to review.copy(plan = review.plan.copy(
                    edits = persistentListOf(review.plan.edits.first()),
                )),
                "removed-file" to review.copy(plan = review.plan.copy(
                    edits = persistentListOf(AgentSetupEdit("/home/alex/.ssh/config", "Host *\n", null)),
                )),
                "disabled" to agentSetupPreviewData().copy(enabled = false),
                "reviewing" to review.copy(plan = null, operation = AgentSetupOperation.REVIEWING),
                "applying" to review.copy(operation = AgentSetupOperation.APPLYING),
                "checking" to result.copy(verified = null, operation = AgentSetupOperation.VERIFYING),
                "check-failed" to result.copy(
                    verified = false,
                    error = AgentSetupError(AgentSetupError.Kind.FAILED, detail = "Agent is locked"),
                ),
                "undo" to review.copy(mode = AgentSetupMode.UNDO, plan = review.plan.copy(undo = true)),
                "undo-conflict" to review.copy(mode = AgentSetupMode.UNDO, plan = review.plan.copy(
                    undo = true,
                    conflicts = persistentListOf(
                        AgentSetupConflict(AgentSetupConflict.Kind.CHANGED_BLOCK, "/home/alex/.zshrc"),
                    ),
                )),
                "undo-empty" to review.copy(mode = AgentSetupMode.UNDO, plan = review.plan.copy(
                    undo = true, edits = persistentListOf(),
                )),
                "undo-result" to result.copy(mode = AgentSetupMode.UNDO, verified = null),
                "termux-run" to review.copy(termux = true, selectedShells = persistentSetOf(SetupShell.BASH)),
                "termux-result" to result.copy(termux = true, applied = false, verified = null),
                "unavailable" to agentSetupPreviewData().copy(available = false),
                "discard" to agentSetupPreviewData().copy(confirmingDiscard = true),
            )
            states.forEach { (name, data) -> render(Variant(name, 390, 844), data, name) }
            listOf("review-conflict", "review-failed", "review-disabled").forEach { name ->
                render(Variant(name, 1024, 800, dark = true), states.getValue(name), "$name-dark")
                render(Variant(name, 390, 844, fontScale = 1.6f, rtl = true), states.getValue(name), "$name-large-rtl")
            }
        }
    }

    @Test
    fun `sandboxed builds show the host command and an explicit check`() {
        SwingUtilities.invokeAndWait {
            val run = agentSetupPreviewData(AgentSetupStep.REVIEW)
                .copy(hostCli = HOST_CLI, plan = null)
            val result = agentSetupPreviewData(AgentSetupStep.RESULT)
                .copy(hostCli = HOST_CLI, applied = false, verified = null)
            val failed = agentSetupPreviewData().copy(
                available = false,
                unavailableError = AgentSetupError(
                    AgentSetupError.Kind.FAILED,
                    detail = "SSH agent executable is unavailable",
                ),
            )
            mapOf(
                "host-settings" to agentSetupPreviewData().copy(hostCli = HOST_CLI),
                "host-run" to run,
                "host-undo" to run.copy(mode = AgentSetupMode.UNDO),
                "host-result" to result,
                "unavailable-details" to failed,
            ).forEach { (name, data) -> render(Variant(name, 390, 844), data, name) }
            listOf(
                failed to "Agent setup failed",
                run to hostCliCommand(run, HOST_CLI),
                result to externalCheckCommand(SetupAgent.SSH, HOST_CLI.endpoint),
            ).forEach { (data, expected) ->
                scene(Variant("host", 390, 844), data).use { scene ->
                    scene.render(0).close()
                    val nodes = nodes(scene)
                    assertTrue(nodes.any { text(it).contains(expected) }, expected)
                    assertTrue(nodes.none { text(it).contains("Termux") })
                    assertTrue(nodes.none { text(it).contains("requires the bundled Keyguard CLI") })
                }
            }
        }
    }

    @Test
    fun `primary recovery buttons refresh and only a ready review can continue`() {
        SwingUtilities.invokeAndWait {
            val review = agentSetupPreviewData(AgentSetupStep.REVIEW)
            val cases = listOf(
                Triple(review.copy(plan = review.plan!!.copy(conflicts = persistentListOf(
                    AgentSetupConflict(AgentSetupConflict.Kind.ZDOTDIR, "/home/alex/.zshenv"),
                ))), "Refresh review", true),
                Triple(review.copy(plan = null, error = AgentSetupError(AgentSetupError.Kind.CHANGED)),
                    "Retry review", true),
                Triple(review.copy(plan = null), "Refresh review", true),
                Triple(review, "Apply changes", false),
                Triple(review.copy(plan = review.plan.copy(edits = persistentListOf())),
                    "Check agent connection", false),
                Triple(review.copy(mode = AgentSetupMode.UNDO), "Remove configuration", false),
            )
            cases.forEach { (data, label, recovery) ->
                var refreshes = 0
                var continues = 0
                val state = agentSetupPreviewState(data).copy(
                    onContinue = { continues += 1 },
                    onRefresh = { refreshes += 1 },
                )
                scene(Variant("primary", 390, 844), data, state).use { scene ->
                    scene.render(0).close()
                    val action = nodes(scene).single { text(it) == label }
                    assertTrue(action.config.getOrNull(SemanticsProperties.Disabled) == null)
                    assertTrue(action.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke() == true)
                    assertEquals(if (recovery) 1 else 0, refreshes)
                    assertEquals(if (recovery) 0 else 1, continues)
                }
            }
        }
    }

    @Test
    fun `file review opens a dedicated diff dialog and closes without expanding the row`() {
        val data = agentSetupPreviewData(AgentSetupStep.REVIEW)
        lateinit var scene: ImageComposeScene
        SwingUtilities.invokeAndWait {
            scene = scene(Variant("dialog", 900, 900), data)
            scene.render(0).close()
            assertTrue(nodes(scene).none { text(it) == "File changes" })
            val file = nodes(scene).first { text(it).contains("/home/alex/.ssh/config") }
            assertTrue(file.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke() == true)
        }
        try {
            var ready = false
            for (frame in 1..100) {
                SwingUtilities.invokeAndWait {
                    scene.render(frame * 1_000_000_000L).close()
                    ready = nodes(scene).any {
                        it.config.getOrNull(SemanticsProperties.StateDescription) == "Added line"
                    }
                }
                if (ready) break
                Thread.sleep(20)
            }
            SwingUtilities.invokeAndWait {
                assertTrue(ready, "background diff completes")
                scene.render(101_000_000_000L).close()
                scene.render(102_000_000_000L).use { save(it, "file-diff-dialog") }
                val lines = nodes(scene).filter {
                    it.config.getOrNull(SemanticsProperties.StateDescription) == "Added line"
                }
                assertEquals(2, lines.size)
                assertTrue(lines.all { it.boundsInRoot.height > 0 })
                assertTrue(nodes(scene).any { text(it) == "File changes" })
                val close = nodes(scene).first { text(it) == "Close" }
                assertTrue(close.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke() == true)
                scene.render(103_000_000_000L).close()
                scene.render(104_000_000_000L).close()
                assertTrue(nodes(scene).none { text(it) == "File changes" })
                assertTrue(nodes(scene).none {
                    it.config.getOrNull(SemanticsProperties.StateDescription) == "Added line"
                })
            }
        } finally {
            SwingUtilities.invokeAndWait { scene.close() }
        }
    }
}

private const val HOST_CLI_LIB =
    "/var/lib/flatpak/runtime/org.freedesktop.Platform/x86_64/25.08/0f1e2d3c4b5a/files/lib/x86_64-linux-gnu"

private val HOST_CLI = AgentSetupHostCli(
    command = persistentListOf(
        "$HOST_CLI_LIB/ld-linux-x86-64.so.2",
        "--library-path",
        HOST_CLI_LIB,
        "/var/lib/flatpak/app/com.artemchep.keyguard/x86_64/stable/9a8b7c6d5e4f/files/lib/app/resources/keyguard",
    ),
    endpoint = "/run/user/1000/app/com.artemchep.keyguard/ssh-agent.sock",
)

private fun render(variant: Variant, data: AgentSetupData, name: String) {
    scene(variant, data).use { scene ->
        scene.render(0).close()
        scene.render(1_000_000_000).use { save(it, name) }
        val nodes = nodes(scene)
        assertTrue(nodes.none { text(it).contains("Terminal environment") })
        assertTrue(nodes.none { text(it).contains("Enable the GPG agent first") })
        assertTrue(nodes.none { text(it) == "Setup notes" })
        assertTranslatedMessages(nodes, data, name)
        if (data.confirmingDiscard) {
            assertTrue(nodes.any { text(it) == "Discard setup?" })
            return@use
        }
        assertFileCount(nodes, data, name)
        val expected = expectedAction(data)
        val action = assertNotNull(nodes.firstOrNull { text(it) == expected }, "$name: primary action")
        val bounds = action.boundsInRoot
        assertTrue(bounds.width > 0 && bounds.height > 0, "$name: visible action")
        assertTrue(bounds.left >= 0 && bounds.right <= variant.width, "$name: horizontal fit")
        assertTrue(bounds.top >= 0 && bounds.bottom <= variant.height, "$name: vertical fit")
        assertEquals(stateEnabled(data), action.config.getOrNull(SemanticsProperties.Disabled) == null,
            "$name: primary action availability")
        assertReviewGuidance(nodes, data, variant, name)
        if (data.step == AgentSetupStep.SETTINGS && data.available && data.setupAllowed) {
            val choices = nodes.filter { it.config.getOrNull(SemanticsProperties.ToggleableState) != null }
            assertEquals(data.shells.size, choices.size)
            choices.forEach {
                assertNotNull(it.config.getOrNull(SemanticsActions.OnClick)?.action)
                assertTrue(text(it).isNotEmpty())
            }
        }
    }
}

/** Notes, conflicts and errors use the app's strings; helper details stay collapsed. Notes merge their texts. */
private fun assertTranslatedMessages(nodes: List<SemanticsNode>, data: AgentSetupData, name: String) {
    data.error?.detail?.let { detail ->
        assertTrue(nodes.none { text(it).contains(detail) }, "$name: details collapsed")
    }
    if (data.reviewStatus == null || !data.setupAllowed || data.confirmingDiscard) return
    val expected = listOfNotNull(
        AGENT_NOTES.getValue(data.agent).takeIf { data.plan?.undo == false },
        data.error?.let { error -> ERROR_TEXTS[error.kind]?.format(error.path) },
    ) + data.plan?.conflicts.orEmpty().map { CONFLICT_TEXTS.getValue(it.kind).format(it.path) }
    expected.forEach { message -> assertTrue(nodes.any { text(it).contains(message) }, "$name: $message") }
}

private val AGENT_NOTES = mapOf(
    SetupAgent.SSH to "SSH configuration applies",
    SetupAgent.GPG to "Selected terminals will use",
)
private val CONFLICT_TEXTS = mapOf(
    AgentSetupConflict.Kind.ZDOTDIR to "%s sets ZDOTDIR",
    AgentSetupConflict.Kind.COMPILED_STARTUP to "%s has a compiled copy",
    AgentSetupConflict.Kind.CHANGED_BLOCK to "Keyguard's settings in %s were edited",
)
private val ERROR_TEXTS = mapOf(
    AgentSetupError.Kind.CHANGED to "Files changed after the review. Refresh the review.",
    AgentSetupError.Kind.UNSAFE_FILE to "Keyguard can't safely edit %s.",
)

private fun stateEnabled(data: AgentSetupData) =
    if (data.reviewStatus?.recovery == true) data.canRefresh else data.canContinue

private fun assertReviewGuidance(nodes: List<SemanticsNode>, data: AgentSetupData, variant: Variant, name: String) {
    assertTrue(nodes.none { text(it).startsWith("Resolve the issues listed above") })
    assertTrue(nodes.none { text(it).startsWith("Check the error above") })
    val guidance = when (data.reviewStatus) {
        AgentSetupReviewStatus.REQUIRED -> "Review the current configuration before continuing."
        AgentSetupReviewStatus.DISABLED ->
            "Enable the ${data.agent.name} agent in Settings before configuring it automatically."
        else -> null
    }
    guidance?.let {
        val message = nodes.single { node -> text(node) == it }
        val bounds = message.boundsInRoot
        assertTrue(bounds.left >= 0 && bounds.right <= variant.width, "$name: guidance horizontal fit")
        assertTrue(bounds.top >= 0 && bounds.bottom <= variant.height, "$name: guidance vertical fit")
        val footer = nodes.first { node -> node.config.getOrNull(SemanticsProperties.LiveRegion) != null }
        assertTrue(footer.boundsInRoot.contains(bounds.topLeft), "$name: guidance is in the fixed footer")
    }
    if (data.reviewStatus == AgentSetupReviewStatus.CONFLICTS) {
        assertTrue(nodes.any { text(it).contains("Action required") })
    }
}

private fun assertFileCount(nodes: List<SemanticsNode>, data: AgentSetupData, name: String) {
    if (data.step == AgentSetupStep.REVIEW && data.plan != null && !data.external) {
        val count = data.plan.edits.size
        val label = if (count == 1) "1 file to change" else "$count files to change"
        assertEquals(count > 0, nodes.any { text(it) == label }, "$name: formatted file count only for changes")
        if (data.plan.overrides.isNotEmpty()) {
            assertTrue(nodes.any { text(it).contains("Check these lines") }, "$name: override information")
            data.plan.overrides.forEach { override ->
                val location = "${override.path}:${override.line}"
                assertTrue(nodes.any { text(it).contains(location) }, "$name: source location")
            }
        }
        data.plan.edits.forEach { edit ->
            val row = nodes.first { text(it) == edit.path }
            val action = when {
                edit.before == null -> "Create file"
                edit.after == null -> "Remove file"
                else -> "Update file"
            }
            assertEquals(action, row.config.getOrNull(SemanticsProperties.StateDescription))
            assertTrue(nodes.none { text(it) == action })
        }
    }
}

private fun expectedAction(data: AgentSetupData) = when {
    !data.available || data.step == AgentSetupStep.RESULT -> "Done"
    data.operation == AgentSetupOperation.REVIEWING -> "Reviewing changes"
    data.operation == AgentSetupOperation.APPLYING -> "Applying changes"
    data.step == AgentSetupStep.SETTINGS || data.external -> "Continue"
    data.reviewStatus == AgentSetupReviewStatus.FAILED -> "Retry review"
    data.reviewStatus?.recovery == true -> "Refresh review"
    data.mode == AgentSetupMode.UNDO -> "Remove configuration"
    data.plan?.edits?.isEmpty() == true -> "Check agent connection"
    else -> "Apply changes"
}

private fun scene(
    variant: Variant,
    data: AgentSetupData,
    state: AgentAutomaticSetupState = agentSetupPreviewState(data),
) = ImageComposeScene(
    width = variant.width,
    height = variant.height,
    density = Density(1f, variant.fontScale),
    layoutDirection = if (variant.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
) {
    WizardPreview(dark = variant.dark) {
        AgentAutomaticSetupContent(data.agent, data.mode, state)
    }
}

private fun save(image: org.jetbrains.skia.Image, name: String) {
    val folder = File("build/reports/agent-setup").apply { mkdirs() }
    image.encodeToData(EncodedImageFormat.PNG)!!.use { data -> File(folder, "$name.png").writeBytes(data.bytes) }
}

private fun text(node: SemanticsNode) =
    node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }.orEmpty()
private fun nodes(scene: ImageComposeScene) = scene.semanticsOwners.flatMap { descendants(it.rootSemanticsNode) }
private fun descendants(node: SemanticsNode): List<SemanticsNode> =
    listOf(node) + node.children.flatMap(::descendants)

private data class Variant(
    val name: String,
    val width: Int,
    val height: Int,
    val dark: Boolean = false,
    val fontScale: Float = 1f,
    val rtl: Boolean = false,
)
