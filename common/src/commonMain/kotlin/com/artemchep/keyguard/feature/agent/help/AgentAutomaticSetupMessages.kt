package com.artemchep.keyguard.feature.agent.help

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.agent_auto_setup_conflict_changed_block
import com.artemchep.keyguard.res.agent_auto_setup_conflict_compiled_startup
import com.artemchep.keyguard.res.agent_auto_setup_conflict_unrecorded_block
import com.artemchep.keyguard.res.agent_auto_setup_conflict_zdotdir
import com.artemchep.keyguard.res.agent_auto_setup_error_busy
import com.artemchep.keyguard.res.agent_auto_setup_error_failed
import com.artemchep.keyguard.res.agent_auto_setup_error_files_changed
import com.artemchep.keyguard.res.agent_auto_setup_error_managed_block
import com.artemchep.keyguard.res.agent_auto_setup_error_no_powershell
import com.artemchep.keyguard.res.agent_auto_setup_error_unsafe_file
import com.artemchep.keyguard.res.agent_auto_setup_error_update_required
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun agentSetupErrorText(error: AgentSetupError): String {
    val path = error.path.orEmpty()
    return when (error.kind) {
        AgentSetupError.Kind.CHANGED -> stringResource(Res.string.agent_auto_setup_error_files_changed)
        AgentSetupError.Kind.BUSY -> stringResource(Res.string.agent_auto_setup_error_busy)
        AgentSetupError.Kind.UNSAFE_FILE -> stringResource(Res.string.agent_auto_setup_error_unsafe_file, path)
        AgentSetupError.Kind.MANAGED_BLOCK -> stringResource(Res.string.agent_auto_setup_error_managed_block, path)
        AgentSetupError.Kind.NO_POWERSHELL -> stringResource(Res.string.agent_auto_setup_error_no_powershell)
        AgentSetupError.Kind.UPDATE_REQUIRED -> stringResource(Res.string.agent_auto_setup_error_update_required)
        AgentSetupError.Kind.FAILED -> stringResource(Res.string.agent_auto_setup_error_failed)
    }
}

@Composable
internal fun agentSetupConflictText(conflict: AgentSetupConflict): String {
    val text = when (conflict.kind) {
        AgentSetupConflict.Kind.ZDOTDIR -> Res.string.agent_auto_setup_conflict_zdotdir
        AgentSetupConflict.Kind.COMPILED_STARTUP -> Res.string.agent_auto_setup_conflict_compiled_startup
        AgentSetupConflict.Kind.CHANGED_BLOCK -> Res.string.agent_auto_setup_conflict_changed_block
        AgentSetupConflict.Kind.UNRECORDED_BLOCK -> Res.string.agent_auto_setup_conflict_unrecorded_block
    }
    return stringResource(text, conflict.path)
}
