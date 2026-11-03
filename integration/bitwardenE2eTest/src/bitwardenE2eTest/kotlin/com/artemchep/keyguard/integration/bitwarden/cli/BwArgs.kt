package com.artemchep.keyguard.integration.bitwarden.cli

import com.artemchep.keyguard.integration.bitwarden.TestKdf
import com.artemchep.keyguard.integration.bitwarden.VaultwardenAccounts

/** The account a command acts as. */
internal data class BwAccount(
    /** Without a trailing slash. */
    val server: String,
    val email: String,
    val password: String,
)

/** A parsed command line: a command to run, a request for the usage, or a usage error. */
internal sealed interface BwCommand {
    data object Help : BwCommand

    data class Invalid(val message: String) : BwCommand

    /** A command to run; [json] prints only the JSON line. */
    sealed interface Run : BwCommand {
        val json: Boolean
    }

    /** A command that talks to the server of [account]. */
    sealed interface Remote : Run {
        val account: BwAccount
    }

    data class Register(
        override val account: BwAccount,
        val name: String = VaultwardenAccounts.DEFAULT_NAME,
        val kdf: TestKdf = TestKdf.Pbkdf2(),
        override val json: Boolean = false,
    ) : Remote

    data class Seed(
        override val account: BwAccount,
        val folders: Int = 1,
        val logins: Int = 3,
        val notes: Int = 1,
        val attachment: Boolean = false,
        val send: SendType? = null,
        override val json: Boolean = false,
    ) : Remote

    data class Totp(
        override val account: BwAccount,
        override val json: Boolean = false,
    ) : Remote

    data class Code(
        val secret: String,
        override val json: Boolean = false,
    ) : Run

    data class Org(
        override val account: BwAccount,
        val name: String,
        val member: String? = null,
        override val json: Boolean = false,
    ) : Remote

    data class Send(
        override val account: BwAccount,
        val text: String,
        val sendPassword: String? = null,
        override val json: Boolean = false,
    ) : Remote

    data class Attach(
        override val account: BwAccount,
        val cipherId: String,
        val file: String,
        override val json: Boolean = false,
    ) : Remote

    enum class SendType { Text, File }
}

/** The command line parser of `bw`. */
internal object BwArgs {
    val USAGE = """
        Usage: bw <command> [options] [--json]

        Creates and seeds accounts on a Bitwarden-compatible server, with Keyguard's own crypto.
        Every command but code takes --server URL --email EMAIL --password PASSWORD.

        Commands:
          register [--name NAME] [--argon2 | --pbkdf2-iterations N]
              Creates the account. The KDF is PBKDF2 with 600000 iterations by default.
          seed [--folders 1] [--logins 3] [--notes 1] [--attachment] [--send text|file]
              Adds folders, logins with a TOTP secret, secure notes, an attachment and a Send.
          totp
              Turns on two-step login with an authenticator app.
          code --secret SECRET
              Prints the current TOTP code of a base32 secret. Needs no server.
          org --name NAME [--member EMAIL]
              Creates an organization with a shared login and confirms a registered member.
          send --text TEXT [--password-protect PASSWORD]
              Creates a text Send and prints its link.
          attach --cipher ID --file PATH
              Uploads a file as an attachment of a login.

        Prints readable lines, then one JSON object as the last line; --json prints only the JSON.
        Exits with 0 on success, 1 on a server or harness failure, 2 on a usage error.
    """.trimIndent()

    private val HELP = setOf("help", "--help", "-h")

    fun parse(args: List<String>): BwCommand {
        val name = args.firstOrNull()
        val spec = name?.let(COMMANDS::get)
        return when {
            name == null -> BwCommand.Invalid("Missing a command.")

            name in HELP -> BwCommand.Help

            spec == null -> BwCommand.Invalid("Unknown command: $name")

            else -> try {
                spec.build(Options.parse(name, spec, args.drop(1)))
            } catch (e: UsageException) {
                BwCommand.Invalid(e.message)
            }
        }
    }
}

private class Spec(
    val options: Set<String>,
    val flags: Set<String> = emptySet(),
    val build: Options.() -> BwCommand.Run,
)

private val ACCOUNT_OPTIONS = setOf("server", "email", "password")

private val COMMANDS = mapOf(
    "register" to Spec(ACCOUNT_OPTIONS + setOf("name", "pbkdf2-iterations"), setOf("argon2")) {
        val iterations = optional("pbkdf2-iterations")?.let { positive("pbkdf2-iterations", it) }
        if (flag("argon2") && iterations != null) {
            usage("Pass either --argon2 or --pbkdf2-iterations.")
        }
        val kdf = when {
            flag("argon2") -> TestKdf.Argon2id()
            iterations != null -> TestKdf.Pbkdf2(iterations)
            else -> TestKdf.Pbkdf2()
        }
        val name = optional("name") ?: VaultwardenAccounts.DEFAULT_NAME
        BwCommand.Register(account(), name = name, kdf = kdf, json = json)
    },
    "seed" to Spec(ACCOUNT_OPTIONS + setOf("folders", "logins", "notes", "send"), setOf("attachment")) {
        val account = account()
        val defaults = BwCommand.Seed(account)
        val logins = count("logins") ?: defaults.logins
        if (flag("attachment") && logins == 0) {
            usage("--attachment needs at least one login.")
        }
        val send = optional("send")?.let { type ->
            BwCommand.SendType.entries.firstOrNull { it.name.equals(type, ignoreCase = true) }
                ?: usage("--send takes text or file, got '$type'.")
        }
        BwCommand.Seed(
            account = account,
            folders = count("folders") ?: defaults.folders,
            logins = logins,
            notes = count("notes") ?: defaults.notes,
            attachment = flag("attachment"),
            send = send,
            json = json,
        )
    },
    "totp" to Spec(ACCOUNT_OPTIONS) {
        BwCommand.Totp(account(), json = json)
    },
    "code" to Spec(setOf("secret")) {
        BwCommand.Code(required("secret"), json = json)
    },
    "org" to Spec(ACCOUNT_OPTIONS + setOf("name", "member")) {
        BwCommand.Org(account(), name = required("name"), member = optional("member"), json = json)
    },
    "send" to Spec(ACCOUNT_OPTIONS + setOf("text", "password-protect")) {
        BwCommand.Send(account(), text = required("text"), sendPassword = optional("password-protect"), json = json)
    },
    "attach" to Spec(ACCOUNT_OPTIONS + setOf("cipher", "file")) {
        BwCommand.Attach(account(), cipherId = required("cipher"), file = required("file"), json = json)
    },
)

private class UsageException(override val message: String) : IllegalArgumentException(message)

private fun usage(message: String): Nothing = throw UsageException(message)

/** The `--key value` options and the boolean flags of one command. */
private class Options(
    private val command: String,
    private val values: Map<String, String>,
    private val flags: Set<String>,
) {
    val json: Boolean get() = flag("json")

    fun flag(name: String): Boolean = name in flags

    fun optional(name: String): String? = values[name]

    fun required(name: String): String = values[name]
        ?.takeIf { it.isNotBlank() }
        ?: usage("$command needs --$name.")

    fun count(name: String): Int? {
        val value = values[name] ?: return null
        return value.toIntOrNull()
            ?.takeIf { it >= 0 }
            ?: usage("--$name takes a whole number, got '$value'.")
    }

    fun positive(name: String, value: String): Int = value.toIntOrNull()
        ?.takeIf { it > 0 }
        ?: usage("--$name takes a positive number, got '$value'.")

    fun account(): BwAccount {
        val server = required("server").trimEnd('/')
        if (!server.startsWith("http://") && !server.startsWith("https://")) {
            usage("--server takes an http:// or https:// URL, got '$server'.")
        }
        return BwAccount(server = server, email = required("email"), password = required("password"))
    }

    companion object {
        fun parse(command: String, spec: Spec, tokens: List<String>): Options {
            val values = mutableMapOf<String, String>()
            val flags = mutableSetOf<String>()
            var index = 0
            while (index < tokens.size) {
                val token = tokens[index]
                val name = token.removePrefix("--")
                when {
                    !token.startsWith("--") -> usage("Unexpected argument: $token")

                    name == "json" || name in spec.flags -> flags += name

                    name in spec.options -> {
                        index += 1
                        values[name] = tokens.getOrNull(index) ?: usage("$token needs a value.")
                    }

                    else -> usage("Unknown option for $command: $token")
                }
                index += 1
            }
            return Options(command, values, flags)
        }
    }
}
