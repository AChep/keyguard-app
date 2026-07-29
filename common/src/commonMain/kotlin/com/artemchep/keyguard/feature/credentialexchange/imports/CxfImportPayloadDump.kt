package com.artemchep.keyguard.feature.credentialexchange.imports

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.service.credentialexchange.CredentialExchangeImportTransportResult
import com.artemchep.keyguard.common.service.dirs.DirsService
import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.platform.util.isRelease
import com.artemchep.keyguard.util.foundation.io.writeText
import kotlin.time.Clock

/**
 * The raw received payload, but only when payload dumping is enabled.
 *
 * `null` in a release build, and that is what keeps the production lifetime of the
 * payload unchanged: nobody retains it, so it stays a local of the transfer coroutine
 * and dies with it. Also `null` for a cancelled or failed transfer, so re-running the
 * flow clears whatever the previous attempt left behind.
 *
 * [enabled] is a parameter rather than a bare [isRelease] read so the release-build
 * guarantee is testable — the same reason `SyncDiagnostics` and `BackupDiagnostics`
 * take theirs.
 */
internal fun CredentialExchangeImportTransportResult.debugPayloadOrNull(
    enabled: Boolean = !isRelease,
): String? = (this as? CredentialExchangeImportTransportResult.Success)
    ?.payload
    ?.takeIf { enabled }

/**
 * Writes the received CXF document to the downloads folder verbatim, and answers with
 * the name it was written under.
 *
 * Verbatim is the point: the file is meant to go straight into the conformance suite as
 * a golden vector, so re-encoding it — pretty-printing, reordering keys, dropping
 * duplicates — would destroy exactly the properties a parser test needs to pin.
 *
 * A debug affordance only. This writes a source vault's passwords, TOTP seeds and
 * private keys, in the clear, into a folder every application on the device can read;
 * [debugPayloadOrNull] is what keeps it unreachable in a release build.
 *
 * The name is returned rather than [DirsService]'s own answer because that answer is
 * `null` on Android API 29 and above, where the write goes through MediaStore and there
 * is no URI to hand back.
 */
internal fun saveCxfImportPayload(
    payload: String,
    dirsService: DirsService,
    dateFormatter: DateFormatter,
): IO<String> = ioEffect {
    val timestamp = dateFormatter.formatDateTimeMachine(Clock.System.now())
    val fileName = "keyguard_cxf_import_$timestamp.json"
    dirsService
        .saveToDownloads(fileName) { sink ->
            sink.writeText(payload)
        }
        .bind()
    fileName
}
