package com.artemchep.keyguard.common.service.agent

import com.artemchep.keyguard.common.service.browser.BrowserApprovalRequest
import com.artemchep.keyguard.common.service.browser.BrowserExtensionService
import com.artemchep.keyguard.common.service.cli.CliApprovalRequest
import com.artemchep.keyguard.common.service.cli.CliService
import com.artemchep.keyguard.common.service.environment.EnvironmentApprovalRequest
import com.artemchep.keyguard.common.service.environment.EnvironmentAccess
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentApprovalRequest
import com.artemchep.keyguard.common.service.passkey.PasskeyApprovalRequest
import com.artemchep.keyguard.common.service.passkey.PasskeyApprovals
import com.artemchep.keyguard.common.service.sshagent.SshAgentApprovalRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlin.time.Instant

/** A pending prompt of any agent, as the shared approval window shows it. */
sealed interface AgentPrompt {
    val id: String
    val expiresAt: Instant

    data class Ssh(val request: SshAgentApprovalRequest) : AgentPrompt {
        override val id: String get() = request.id
        override val expiresAt: Instant get() = request.expiresAt
    }

    data class Gpg(val request: GpgAgentApprovalRequest) : AgentPrompt {
        override val id: String get() = request.id
        override val expiresAt: Instant get() = request.expiresAt
    }

    data class Environment(val request: EnvironmentApprovalRequest) : AgentPrompt {
        override val id: String get() = request.id
        override val expiresAt: Instant get() = request.expiresAt
    }

    data class Cli(val request: CliApprovalRequest) : AgentPrompt {
        override val id: String get() = request.id
        override val expiresAt: Instant get() = request.expiresAt
    }

    data class Browser(val request: BrowserApprovalRequest) : AgentPrompt {
        override val id: String get() = request.id
        override val expiresAt: Instant get() = request.expiresAt
    }

    data class Passkey(val request: PasskeyApprovalRequest) : AgentPrompt {
        override val id: String get() = request.id
        override val expiresAt: Instant get() = request.expiresAt
    }

    /** A sample [prompt] from [AgentApprovalSamples]: it shows as the prompt, and no agent waits for it. */
    data class Sample(val prompt: AgentPrompt) : AgentPrompt {
        override val id: String get() = prompt.id
        override val expiresAt: Instant get() = prompt.expiresAt
    }
}

/**
 * The pending approval prompts of every agent, so one surface shows them
 * one at a time instead of each agent opening its own window over the
 * others.
 */
class AgentApprovalCenter(
    private val ssh: AgentApprovalBroker<SshAgentApprovalRequest>,
    private val gpg: AgentApprovalBroker<GpgAgentApprovalRequest>,
    private val environments: EnvironmentAccess,
    private val cli: CliService,
    private val browser: BrowserExtensionService,
    private val passkeys: PasskeyApprovals,
    private val samples: AgentApprovalSamples,
    scope: CoroutineScope,
) {
    /** The pending prompts, oldest first. */
    val pending: StateFlow<List<AgentPrompt>> = combine(
        // The typed combine takes at most five flows.
        combine(ssh.pending, gpg.pending, environments.approvals) { sshPrompts, gpgPrompts, environmentPrompts ->
            sshPrompts.map(AgentPrompt::Ssh) +
                gpgPrompts.map(AgentPrompt::Gpg) +
                environmentPrompts.map(AgentPrompt::Environment)
        },
        cli.approvals,
        browser.approvals,
        passkeys.approvals,
        samples.pending,
    ) { agentPrompts, cliPrompts, browserPrompts, passkeyPrompts, samplePrompts ->
        val prompts = agentPrompts +
            cliPrompts.map(AgentPrompt::Cli) +
            browserPrompts.map(AgentPrompt::Browser) +
            passkeyPrompts.map(AgentPrompt::Passkey) +
            samplePrompts
        // The prompt that expires first is shown first.
        prompts.sortedBy { it.expiresAt }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** True while the operating system verifies the user: the approval window stays off screen. */
    val suspended: StateFlow<Boolean> = passkeys.verifying
        .stateIn(scope, SharingStarted.Eagerly, false)

    /** Answers the prompt [id] of whichever agent owns it; `false` if it is no longer pending. */
    fun respond(id: String, approved: Boolean): Boolean =
        samples.respond(id, approved) ||
            ssh.respond(id, approved) ||
            gpg.respond(id, approved) ||
            environments.respondToApproval(id, approved) ||
            cli.respondToApproval(id, approved) ||
            browser.respondToApproval(id, approved) ||
            passkeys.respondToApproval(id, approved)
}
