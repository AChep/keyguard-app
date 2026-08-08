package com.artemchep.keyguard.ipctestclient.ui

import com.artemchep.keyguard.ipctestclient.ipc.IpcExchange
import com.artemchep.keyguard.ipctestclient.ipc.OpenPgpRequestSpec
import com.artemchep.keyguard.ipctestclient.ipc.SshRequestSpec
import com.artemchep.keyguard.ipctestclient.ipc.toKeyIdList
import com.artemchep.keyguard.ipctestclient.ipc.toKeyIdOrNull
import org.openintents.openpgp.util.OpenPgpApi
import org.openintents.ssh.authentication.SshAuthenticationApi

private fun OpenPgpFormState.userIdList(): List<String> = userIds
    .split('\n', ',')
    .map(String::trim)
    .filter(String::isNotEmpty)

fun OpenPgpFormState.toSpec(): OpenPgpRequestSpec {
    val recipients = userIdList()
    val typedSignKeyId = signKeyId.toKeyIdOrNull()
    return OpenPgpRequestSpec(
        operation = operation,
        apiVersion = apiVersion.trim().toIntOrNull().takeUnless { omitApiVersion },
        payload = payload.toBytes(),
        userIds = recipients.takeUnless { sendAsSingleUserId }.orEmpty(),
        singleUserId = recipients.firstOrNull().takeIf { sendAsSingleUserId },
        keyIds = keyIds.toKeyIdList(),
        selectedKeyIds = selectedKeyIds.toKeyIdList(),
        signKeyId = typedSignKeyId.takeUnless { sendSignKeyIdAsPreselect },
        preselectKeyId = typedSignKeyId.takeIf { sendSignKeyIdAsPreselect },
        keyId = keyId.toKeyIdOrNull(),
        originalFilename = originalFilename.trim().ifEmpty { null },
        asciiArmor = asciiArmor,
        enableCompression = enableCompression,
        opportunistic = opportunistic,
        detachedSignature = detachedSignature,
        customHeaders = customHeaders,
        minimize = minimize,
        omitInput = omitInput || !operation.needsInput,
        omitOutputPipe = omitOutputPipe,
        outputPipeIdOverride = foreignPipeId.trim().toIntOrNull(),
    )
}

fun SshFormState.toSpec(scratch: Scratchpad): SshRequestSpec = SshRequestSpec(
    operation = operation,
    apiVersion = apiVersion.trim().toIntOrNull().takeUnless { omitApiVersion },
    keyId = keyId.trim().ifEmpty { null },
    challenge = if (challengeFromScratch) {
        scratch.sshSignature ?: ByteArray(0)
    } else {
        challenge.encodeToByteArray()
    },
    hashAlgorithm = hashAlgorithm,
    useLibraryBuilders = useLibraryBuilders,
)

/** Carries result values forward so the next operation can consume them. */
fun Scratchpad.updatedWithOpenPgp(exchange: IpcExchange): Scratchpad {
    val result = exchange.result ?: return this
    return copy(
        signKeyId = result
            .takeIf { it.hasExtra(OpenPgpApi.RESULT_SIGN_KEY_ID) }
            ?.getLongExtra(OpenPgpApi.RESULT_SIGN_KEY_ID, 0L)
            ?: signKeyId,
        keyIds = result.getLongArrayExtra(OpenPgpApi.RESULT_KEY_IDS)?.toList() ?: keyIds,
        primaryUserId = result.getStringExtra(OpenPgpApi.RESULT_PRIMARY_USER_ID)
            ?: primaryUserId,
        detachedSignature = result.getByteArrayExtra(OpenPgpApi.RESULT_DETACHED_SIGNATURE)
            ?: detachedSignature,
        lastOutput = exchange.output?.takeIf { it.isNotEmpty() } ?: lastOutput,
    )
}

fun Scratchpad.updatedWithSsh(exchange: IpcExchange): Scratchpad {
    val result = exchange.result ?: return this
    return copy(
        sshKeyId = result.getStringExtra(SshAuthenticationApi.EXTRA_KEY_ID) ?: sshKeyId,
        sshPublicKey = result.getStringExtra(SshAuthenticationApi.EXTRA_SSH_PUBLIC_KEY)
            ?: sshPublicKey,
        sshSignature = result.getByteArrayExtra(SshAuthenticationApi.EXTRA_SIGNATURE)
            ?: sshSignature,
    )
}
