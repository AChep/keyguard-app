package com.artemchep.keyguard.feature.agent.help

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.auth.common.TextFieldModel
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.protocol_gpg
import com.artemchep.keyguard.res.protocol_ssh
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import org.jetbrains.compose.resources.StringResource

internal enum class SetupAgent(val argument: String, val protocol: StringResource) {
    SSH("ssh", Res.string.protocol_ssh),
    GPG("gpg", Res.string.protocol_gpg),
}

internal enum class SetupShell(val argument: String, val title: String) {
    BASH("bash", "Bash"),
    ZSH("zsh", "Zsh"),
    FISH("fish", "Fish"),
    POWERSHELL("powershell", "PowerShell 7"),
    WINDOWS_POWERSHELL("windows-powershell", "Windows PowerShell 5.1"),
}

internal data class AgentSetupEdit(
    val path: String,
    val before: String?,
    val after: String?,
)

internal data class AgentSetupPlan(
    val json: String,
    val edits: ImmutableList<AgentSetupEdit>,
    val conflicts: ImmutableList<AgentSetupConflict>,
    val undo: Boolean,
    val overrides: ImmutableList<AgentSetupOverride> = persistentListOf(),
)

/** A user line that names the setting and can take precedence over Keyguard's block. */
internal data class AgentSetupOverride(
    val setting: String,
    val path: String,
    val line: Int,
)

/** An issue that blocks applying until the user resolves it in [path]. */
internal data class AgentSetupConflict(
    val kind: Kind,
    val path: String,
) {
    enum class Kind(val code: String) {
        ZDOTDIR("zdotdir"),
        COMPILED_STARTUP("compiled_startup"),
        CHANGED_BLOCK("changed_block"),
        UNRECORDED_BLOCK("unrecorded_block"),
    }
}

/**
 * A setup failure. The app shows its own text for [kind]; [detail] is the
 * helper's English description, shown only as details.
 */
internal data class AgentSetupError(
    val kind: Kind,
    val path: String? = null,
    val detail: String? = null,
) {
    enum class Kind(val code: String) {
        CHANGED("changed"),
        BUSY("busy"),
        UNSAFE_FILE("unsafe_file"),
        MANAGED_BLOCK("managed_block"),
        NO_POWERSHELL("no_powershell"),
        UPDATE_REQUIRED("update_required"),
        FAILED("failed"),
    }
}

internal class AgentSetupException(val error: AgentSetupError) : Exception(error.detail)

internal interface AgentSetupClient {
    suspend fun preview(shells: Set<SetupShell>, host: String): AgentSetupPlan
    suspend fun undo(): AgentSetupPlan
    suspend fun apply(plan: AgentSetupPlan)
    suspend fun verify(): String
}

@Composable
internal expect fun rememberAgentSetupClient(agent: SetupAgent): AgentSetupClient?

internal enum class AgentSetupMode { CONFIGURE, UNDO }
internal enum class AgentSetupStep { SETTINGS, REVIEW, RESULT }
internal enum class AgentSetupOperation { IDLE, REVIEWING, APPLYING, VERIFYING }

internal enum class AgentSetupReviewStatus(val recovery: Boolean = false) {
    DISABLED, REVIEWING, APPLYING, READY,
    CONFLICTS(recovery = true), FAILED(recovery = true), REQUIRED(recovery = true),
}

private val HOST_REGEX = Regex("[A-Za-z0-9._-]+")

internal data class AgentSetupData(
    val agent: SetupAgent,
    val mode: AgentSetupMode = AgentSetupMode.CONFIGURE,
    val external: Boolean = false,
    val available: Boolean = true,
    val enabled: Boolean = false,
    val shells: ImmutableList<SetupShell> = persistentListOf(),
    val selectedShells: ImmutableSet<SetupShell> = persistentSetOf(),
    val step: AgentSetupStep = AgentSetupStep.SETTINGS,
    val operation: AgentSetupOperation = AgentSetupOperation.IDLE,
    val oneHost: Boolean = false,
    val host: String = "",
    val plan: AgentSetupPlan? = null,
    val applied: Boolean = false,
    val verified: Boolean? = null,
    val error: AgentSetupError? = null,
    val diagnostic: String? = null,
    val hasEdits: Boolean = false,
    val confirmingDiscard: Boolean = false,
) {
    val busy get() = operation != AgentSetupOperation.IDLE
    val mutating get() = operation == AgentSetupOperation.APPLYING
    val setupAllowed get() = enabled || mode == AgentSetupMode.UNDO
    val editable get() = setupAllowed && !busy && !confirmingDiscard && step == AgentSetupStep.SETTINGS
    val hostValid get() = !oneHost || host.trim().matches(HOST_REGEX)
    val settingsValid get() = hostValid &&
        ((agent == SetupAgent.SSH && !external) || selectedShells.isNotEmpty())
    val canRefresh get() = available && setupAllowed && !busy && !confirmingDiscard &&
        !external && step == AgentSetupStep.REVIEW
    val canRetry get() = setupAllowed && !busy && verified == false
    val reviewStatus: AgentSetupReviewStatus? get() = when {
        step != AgentSetupStep.REVIEW || external || !available -> null
        operation == AgentSetupOperation.APPLYING -> AgentSetupReviewStatus.APPLYING
        !setupAllowed -> AgentSetupReviewStatus.DISABLED
        operation == AgentSetupOperation.REVIEWING -> AgentSetupReviewStatus.REVIEWING
        plan == null -> if (error == null) AgentSetupReviewStatus.REQUIRED else AgentSetupReviewStatus.FAILED
        plan.conflicts.isNotEmpty() -> AgentSetupReviewStatus.CONFLICTS
        else -> AgentSetupReviewStatus.READY
    }
    val canContinue: Boolean get() = !confirmingDiscard && when {
        !available -> true
        step == AgentSetupStep.RESULT -> !mutating
        !setupAllowed -> false
        busy -> false
        step == AgentSetupStep.SETTINGS -> settingsValid
        external -> true
        else -> plan != null && plan.conflicts.isEmpty()
    }
    val stepNumber get() = if (mode == AgentSetupMode.UNDO) step.ordinal else step.ordinal + 1
    val stepCount get() = if (mode == AgentSetupMode.UNDO) 2 else 3
}

internal data class AgentAutomaticSetupState(
    val data: AgentSetupData,
    val host: TextFieldModel = TextFieldModel.empty,
    val onShellToggle: (SetupShell) -> Unit = {},
    val onHostScopeChange: (Boolean) -> Unit = {},
    val onBack: () -> Unit = {},
    val onContinue: (() -> Unit)? = null,
    val onRefresh: (() -> Unit)? = null,
    val onRetry: (() -> Unit)? = null,
    val onDiscard: () -> Unit = {},
    val onDismissDiscard: () -> Unit = {},
) {
    val onPrimaryAction: (() -> Unit)? get() = if (data.reviewStatus?.recovery == true) onRefresh else onContinue
}

internal fun termuxSetupCommand(data: AgentSetupData): String {
    val helper = "keyguard-android-${data.agent.argument}-agent"
    return if (data.mode == AgentSetupMode.UNDO) "$helper --undo-setup" else {
        "$helper --setup-shells ${data.selectedShells.joinToString(",") { it.argument }}"
    }
}
