package com.artemchep.keyguard.feature.agent.sample

import com.artemchep.keyguard.common.service.agent.AgentPrompt
import com.artemchep.keyguard.common.service.agent.CallerIdentity
import com.artemchep.keyguard.common.service.browser.BrowserApprovalRequest
import com.artemchep.keyguard.common.service.browser.BrowserCaller
import com.artemchep.keyguard.common.service.browser.BrowserExtensionSession
import com.artemchep.keyguard.common.service.browser.BrowserOperation
import com.artemchep.keyguard.common.service.browser.BrowserPasskeyChoice
import com.artemchep.keyguard.common.service.browser.BrowserRelease
import com.artemchep.keyguard.common.service.cli.CliApprovalRequest
import com.artemchep.keyguard.common.service.cli.CliCaller
import com.artemchep.keyguard.common.service.cli.CliOperation
import com.artemchep.keyguard.common.service.environment.EnvironmentApprovalRequest
import com.artemchep.keyguard.common.service.environment.EnvironmentCaller
import com.artemchep.keyguard.common.service.environment.EnvironmentCapability
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentApprovalRequest
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentOperation
import com.artemchep.keyguard.common.service.passkey.PasskeyApprovalChoice
import com.artemchep.keyguard.common.service.passkey.PasskeyApprovalOperation
import com.artemchep.keyguard.common.service.passkey.PasskeyApprovalRequest
import com.artemchep.keyguard.common.service.sshagent.SshAgentApprovalRequest
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Sample approval prompts, one for each layout case of the approval window.
 * They hold plain values only: no vault item, environment or browser
 * session backs them, so showing one reads nothing from the vault.
 *
 * The callers name no absolute path, so the window looks up no
 * application on disk.
 *
 * @param title The name of the sample, for the Development settings.
 */
enum class ApprovalPromptSample(
    val title: String,
) {
    SshKnownCaller("SSH sign, known caller") {
        override fun build(id: String, expiresAt: Instant): AgentPrompt = AgentPrompt.Ssh(
            SshAgentApprovalRequest(
                id = id,
                keyName = "GitHub",
                keyFingerprint = "SHA256:3vH0r4mJxq2b8KcW9tZ1yLp6dN5eF7gA0sR4uV2wXkM",
                accountId = null,
                cipherId = null,
                caller = CallerIdentity(
                    pid = 48213,
                    processName = "ssh",
                    appName = "Terminal",
                ),
                expiresAt = expiresAt,
            ),
        )
    },
    SshUnknownCaller("SSH sign, unknown caller, long key name") {
        override fun build(id: String, expiresAt: Instant): AgentPrompt = AgentPrompt.Ssh(
            SshAgentApprovalRequest(
                id = id,
                keyName = "Deploy key of the production cluster in eu-central-1, rotated on 2026-11-01",
                keyFingerprint = "SHA256:Qm8xZ2pV7cR1nT4kL9wE3yH6sD0fA5gB8jU2iO7tKpM",
                accountId = null,
                cipherId = null,
                caller = null,
                expiresAt = expiresAt,
            ),
        )
    },
    GpgDecryptKeygrip("GPG decrypt, no fingerprint") {
        override fun build(id: String, expiresAt: Instant): AgentPrompt = AgentPrompt.Gpg(
            GpgAgentApprovalRequest(
                id = id,
                operation = GpgAgentOperation.DECRYPT,
                keyName = "Alex Doe <alex@example.com>",
                keyFingerprint = "",
                keygrip = "6A2C8E0B4D1F3A5C7E9B0D2F4A6C8E0B1D3F5A7C",
                accountId = null,
                cipherId = null,
                caller = CallerIdentity(
                    pid = 30417,
                    processName = "gpg",
                    appName = "Thunderbird",
                ),
                expiresAt = expiresAt,
            ),
        )
    },
    CliRun("CLI run, long command") {
        override fun build(id: String, expiresAt: Instant): AgentPrompt = AgentPrompt.Cli(
            CliApprovalRequest(
                id = id,
                caller = sampleCliCaller,
                terminalSession = true,
                expiresAt = expiresAt,
                operation = CliOperation.Run(
                    environmentId = SAMPLE_ENVIRONMENT_ID,
                    environmentName = "Production",
                    accountName = "Personal",
                    command = listOf(
                        "npm",
                        "run",
                        "deploy",
                        "--",
                        "--prod",
                        "--region",
                        "eu-central-1",
                        "--config",
                        "deploy/production.config.json",
                        "--message",
                        "Release 2.4.0 to all regions",
                    ),
                    workingDirectory = "~/projects/keyguard/server/web",
                ),
            ),
        )
    },
    CliRunIdentity("CLI run, identity required") {
        override fun build(id: String, expiresAt: Instant): AgentPrompt = AgentPrompt.Cli(
            CliApprovalRequest(
                id = id,
                caller = sampleCliCaller,
                terminalSession = false,
                expiresAt = expiresAt,
                operation = CliOperation.Run(
                    environmentId = SAMPLE_ENVIRONMENT_ID,
                    environmentName = "Release signing",
                    accountName = "Work",
                    command = listOf("./gradlew", "publish"),
                    workingDirectory = "~/projects/keyguard",
                ),
                requiresIdentity = true,
            ),
        )
    },
    EnvironmentCreateLocalFile("Environment: create a local .env file") {
        override fun build(id: String, expiresAt: Instant): AgentPrompt = AgentPrompt.Environment(
            EnvironmentApprovalRequest(
                id = id,
                caller = EnvironmentCaller(
                    pid = 39102,
                    processName = "claude",
                    appName = "Terminal",
                    clientName = "Claude Code",
                ),
                environmentName = "Staging",
                capability = EnvironmentCapability.CreateLocalEnvFile,
                path = "~/projects/keyguard/server/web/.env.staging",
                expiresAt = expiresAt,
                accountId = SAMPLE_ACCOUNT_ID,
            ),
        )
    },
    EnvironmentList("Environment: list environments") {
        override fun build(id: String, expiresAt: Instant): AgentPrompt = AgentPrompt.Environment(
            EnvironmentApprovalRequest(
                id = id,
                caller = EnvironmentCaller(
                    pid = 27455,
                    processName = "node",
                    appName = "Visual Studio Code",
                ),
                environmentName = null,
                capability = EnvironmentCapability.ListEnvironments,
                path = null,
                expiresAt = expiresAt,
            ),
        )
    },
    BrowserPair("Browser: pair") {
        override fun build(id: String, expiresAt: Instant): AgentPrompt = AgentPrompt.Browser(
            BrowserApprovalRequest(
                id = id,
                session = sampleChromeSession,
                pairingName = null,
                expiresAt = expiresAt,
                operation = BrowserOperation.Pair(
                    code = "482913",
                    suggestedName = "Google Chrome",
                ),
            ),
        )
    },
    BrowserFill("Browser: fill, with warnings") {
        override fun build(id: String, expiresAt: Instant): AgentPrompt = AgentPrompt.Browser(
            BrowserApprovalRequest(
                id = id,
                session = sampleFirefoxSession,
                pairingName = "Firefox on the laptop",
                expiresAt = expiresAt,
                operation = BrowserOperation.Fill(
                    sampleRelease(
                        origin = "https://login.example.net",
                        site = "https://example.net",
                        name = "Example Store",
                        username = "alexandra.konstantinopoulou+shopping@example-mail.com",
                        accountName = "Work (vault.example.com)",
                        // The page asks in a frame of another site, for an item
                        // found by search: it always asks.
                        topSite = "https://checkout.example.org",
                        matched = false,
                        addUri = "https://login.example.net",
                    ),
                ),
            ),
        )
    },
    BrowserTotpReprompt("Browser: one-time password, re-prompt") {
        override fun build(id: String, expiresAt: Instant): AgentPrompt = AgentPrompt.Browser(
            BrowserApprovalRequest(
                id = id,
                session = sampleChromeSession,
                pairingName = "Chrome on the desktop",
                expiresAt = expiresAt,
                operation = BrowserOperation.Totp(
                    sampleRelease(
                        name = "Example",
                        username = "alex",
                        accountName = "Personal",
                        reprompt = true,
                    ),
                ),
                requiresIdentity = true,
            ),
        )
    },
    BrowserPasskeyPick("Browser: pick one of 3 passkeys") {
        override fun build(id: String, expiresAt: Instant): AgentPrompt = AgentPrompt.Browser(
            BrowserApprovalRequest(
                id = id,
                session = sampleChromeSession,
                pairingName = "Chrome on the desktop",
                expiresAt = expiresAt,
                operation = BrowserOperation.PasskeyPick(
                    choices = listOf(
                        BrowserPasskeyChoice(
                            release = sampleRelease(
                                name = "Example",
                                username = "alex",
                                accountName = "Personal",
                            ),
                            requiresIdentity = false,
                        ),
                        BrowserPasskeyChoice(
                            release = sampleRelease(
                                name = "Example (work)",
                                username = "alex@work.example.com",
                                accountName = "Work",
                                reprompt = true,
                            ),
                            requiresIdentity = true,
                        ),
                        BrowserPasskeyChoice(
                            release = sampleRelease(
                                name = "Example test account",
                                username = "qa-tester",
                                accountName = "Personal",
                            ),
                            requiresIdentity = false,
                        ),
                    ),
                ),
            ),
        )
    },
    PasskeySignIn("Windows: sign in with one of 2 passkeys") {
        override fun build(id: String, expiresAt: Instant): AgentPrompt = AgentPrompt.Passkey(
            PasskeyApprovalRequest(
                id = id,
                rpId = "example.com",
                expiresAt = expiresAt,
                operation = PasskeyApprovalOperation.SignIn(
                    choices = listOf(
                        PasskeyApprovalChoice(
                            name = "Example",
                            username = "alex",
                            accountName = "Personal",
                            reprompt = false,
                        ),
                        PasskeyApprovalChoice(
                            name = "Example (work)",
                            username = "alex@work.example.com",
                            accountName = "Work",
                            reprompt = true,
                        ),
                    ),
                ),
            ),
        )
    },
    ;

    /** The prompt of the sample, as an agent would send it to the approval window. */
    abstract fun build(id: String, expiresAt: Instant): AgentPrompt

    companion object {
        /** The account of the samples' items and environments; no account of the vault has it. */
        const val SAMPLE_ACCOUNT_ID = "sample-account"

        /** The environment of the samples; no environment of the vault has it. */
        private const val SAMPLE_ENVIRONMENT_ID = "sample-environment"
    }
}

private val sampleCliCaller = CliCaller(
    pid = 51877,
    processName = "keyguard",
    appName = "Terminal",
)

private val sampleChromeSession = BrowserExtensionSession(
    id = "sample-session-chrome",
    origin = "chrome-extension://pkgbcjmhaodkmlnfaagkjgfbdaioldkh/",
    caller = BrowserCaller(
        pid = 1105,
        processName = "chrome",
        appName = "Google Chrome",
    ),
    verifiedHost = true,
    pairingId = "sample-pairing-chrome",
)

private val sampleFirefoxSession = BrowserExtensionSession(
    id = "sample-session-firefox",
    origin = "keyguard@example.com",
    caller = BrowserCaller(
        pid = 2210,
        processName = "firefox",
        appName = "Firefox",
    ),
    verifiedHost = true,
    pairingId = "sample-pairing-firefox",
)

private fun sampleRelease(
    name: String,
    username: String?,
    accountName: String?,
    origin: String = "https://example.com",
    site: String = "https://example.com",
    topSite: String? = null,
    reprompt: Boolean = false,
    matched: Boolean = true,
    addUri: String? = null,
) = BrowserRelease(
    origin = origin,
    site = site,
    topSite = topSite,
    accountId = ApprovalPromptSample.SAMPLE_ACCOUNT_ID,
    cipherId = "sample-cipher",
    name = name,
    username = username,
    accountName = accountName,
    reprompt = reprompt,
    // An item found by search, or asked for by a frame of another site, is never remembered.
    approvalWindow = if (matched && topSite == null) 15.minutes else Duration.ZERO,
    matched = matched,
    addUri = addUri,
)
