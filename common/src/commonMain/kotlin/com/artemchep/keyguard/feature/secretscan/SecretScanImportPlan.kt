package com.artemchep.keyguard.feature.secretscan

import com.artemchep.keyguard.common.io.runCatchingNonFatal
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.create.CreateRequest
import com.artemchep.keyguard.common.service.crypto.SshKeyImportResult
import com.artemchep.keyguard.common.service.crypto.SshKeyImportService
import com.artemchep.keyguard.common.service.imports.ImportPlan
import com.artemchep.keyguard.common.service.imports.impl.ImportPlanBuilder
import com.artemchep.keyguard.common.service.imports.mapping.importOrNull
import com.artemchep.keyguard.common.service.imports.mapping.toImportedSshKey
import com.artemchep.keyguard.common.service.secretscan.PlaintextSecretScanner
import com.artemchep.keyguard.common.service.secretscan.SecretScanItem
import com.artemchep.keyguard.common.service.secretscan.readWiped
import com.artemchep.keyguard.feature.environments.EnvironmentDotenv
import com.artemchep.keyguard.feature.environments.EnvironmentPayloadResult
import com.artemchep.keyguard.feature.environments.environmentPayloadOf
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The plan of an import of findings, and what happened to the rest of them.
 */
internal data class SecretScanImportPlan(
    val plan: ImportPlan,
    /** The findings the [plan] creates items of. */
    val items: List<SecretScanItem>,
    /** The fingerprints of the planned SSH keys, by path. */
    val sshFingerprints: Map<String, String>,
    /** Findings whose file changed since the scan. */
    val changed: Int,
    /** Findings that could not be read, or whose passphrase was skipped. */
    val skipped: Int,
)

private sealed interface Outcome {
    data class Ok(
        val request: CreateRequest,
        val fingerprint: String? = null,
    ) : Outcome

    data object Changed : Outcome

    data object Skipped : Outcome
}

/**
 * Re-reads the files of the [items] and plans a vault item of each, without
 * a folder: the target folder goes to the commit.
 *
 * @param askPassphrase asks for the passphrase of an encrypted key; `true` if
 *   the previous one was wrong. Returns `null` to skip the key.
 */
internal suspend fun buildSecretScanImportPlan(
    items: List<SecretScanItem>,
    scanner: PlaintextSecretScanner,
    sshKeyImportService: SshKeyImportService,
    askPassphrase: suspend (SecretScanItem.SshKey, Boolean) -> String?,
    now: Instant = Clock.System.now(),
): SecretScanImportPlan {
    val builder = ImportPlanBuilder(
        source = ImportPlan.Source(),
    )

    val planned = mutableListOf<SecretScanItem>()
    val sshFingerprints = mutableMapOf<String, String>()
    var changed = 0
    var skipped = 0
    items.forEach { item ->
        val outcome = when (item) {
            is SecretScanItem.SshKey -> scanner.readWiped(item) { content ->
                sshKeyOutcome(
                    item = item,
                    content = content,
                    sshKeyImportService = sshKeyImportService,
                    askPassphrase = askPassphrase,
                    now = now,
                )
            }

            is SecretScanItem.DotEnv -> scanner.readWiped(item) { content ->
                dotEnvOutcome(
                    item = item,
                    text = content.decodeToString(),
                    now = now,
                )
            }
        } ?: Outcome.Changed
        when (outcome) {
            is Outcome.Ok -> {
                builder.item(
                    request = outcome.request,
                )
                planned += item
                outcome.fingerprint?.let { sshFingerprints[item.path] = it }
            }

            Outcome.Changed -> changed += 1
            Outcome.Skipped -> skipped += 1
        }
    }
    return SecretScanImportPlan(
        plan = builder.build(),
        items = planned,
        sshFingerprints = sshFingerprints,
        changed = changed,
        skipped = skipped,
    )
}

/** Asks for the passphrase of an encrypted key until it fits or is skipped. */
private suspend fun sshKeyOutcome(
    item: SecretScanItem.SshKey,
    content: ByteArray,
    sshKeyImportService: SshKeyImportService,
    askPassphrase: suspend (SecretScanItem.SshKey, Boolean) -> String?,
    now: Instant,
): Outcome {
    // Reads an encrypted key with the passphrase straight away: the scan
    // already found that it needs one.
    fun ok(passphrase: String?): Outcome.Ok? {
        val result = sshKeyImportService.importOrNull(content, passphrase)
        val keyPair = (result as? SshKeyImportResult.Success)?.keyPair
            ?: return null
        return Outcome.Ok(
            request = CreateRequest(
                title = secretScanFileName(item.path),
                type = DSecret.Type.SshKey,
                sshKey = keyPair.toImportedSshKey(),
                now = now,
            ),
            fingerprint = keyPair.publicKey.fingerprint,
        )
    }

    if (!item.encrypted) {
        // Not a key the vault can read.
        return ok(passphrase = null) ?: Outcome.Skipped
    }
    var wrong = false
    var outcome: Outcome? = null
    while (outcome == null) {
        val passphrase = askPassphrase(item, wrong)
        outcome = if (passphrase != null) ok(passphrase) else Outcome.Skipped
        wrong = true
    }
    return outcome
}

/** Builds the item as the Environments editor saves one. */
private fun dotEnvOutcome(
    item: SecretScanItem.DotEnv,
    text: String,
    now: Instant,
): Outcome {
    val result = runCatchingNonFatal {
        EnvironmentDotenv.parse(text)
    }.getOrNull()
        ?.let(::environmentPayloadOf)
    val payload = (result as? EnvironmentPayloadResult.Ok)
        ?.payload
        ?: return Outcome.Skipped
    val request = CreateRequest(
        title = secretScanDotEnvName(item.path),
        note = "",
        favorite = false,
        reprompt = false,
        type = DSecret.Type.Environment,
        environment = payload,
        now = now,
    )
    return Outcome.Ok(request)
}
