package com.artemchep.keyguard.common.service.cli

/**
 * Every command of the CLI, as `CliRequest` in `commonCli` lists them,
 * with what each one requires of the build and the vault. The agent's
 * `CliPolicy` holds the checks that only the agent can make, such as
 * whether the caller is the bundled CLI.
 */
enum class CliCommand(
    /** The `command` of its frame: `CliCommand::NAME` in `commonCli`. */
    val id: String,
    val requirements: CliCommandRequirements,
) {
    /** `keyguard run`: starts a command with the values of an environment. */
    Run(
        id = "run",
        requirements = CliCommandRequirements(
            session = CliCommandRequirements.Session.Unlocked,
            // A sandboxed CLI would start the command in its sandbox.
            platform = setOf(CliPlatformCapability.Unsandboxed),
            authorization = CliCommandRequirements.Authorization.Approval,
        ),
    ),
}

/** What a command requires before the dispatcher calls its handler. */
data class CliCommandRequirements(
    val session: Session,
    /** What the platform must let the bundled CLI do. */
    val platform: Set<CliPlatformCapability>,
    val authorization: Authorization,
) {
    init {
        // Approvals and their grants belong to the vault session.
        require(authorization == Authorization.None || session == Session.Unlocked) {
            "A command that requires an approval requires an unlocked vault"
        }
    }

    enum class Session {
        /** The command also answers while the vault is locked. */
        Any,
        Unlocked,
    }

    enum class Authorization {
        /** The command answers without asking the user. */
        None,

        /**
         * The user approves the request, or its terminal tab reuses an
         * approval. The dispatcher withholds an answer without one.
         */
        Approval,
    }
}

/** What the platform lets the bundled CLI do. */
enum class CliPlatformCapability {
    /**
     * The CLI runs outside an app sandbox, so the commands that it starts
     * do too. Flatpak and Mac App Store builds sandbox it.
     */
    Unsandboxed,
}
