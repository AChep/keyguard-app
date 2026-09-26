package com.artemchep.keyguard.android.ipc

import android.content.Context

/**
 * Result of the shared caller admission gate that every provider service
 * must pass before serving a request. Keeping this flow in one place
 * guarantees the OpenPGP and SSH services enforce identical registration
 * and one-shot-authorization rules.
 */
internal sealed interface AndroidIpcAdmission {
    /** The caller is registered; proceed with the request. */
    data class Admitted(
        val authorization: AndroidIpcApprovalCoordinator.Authorization?,
    ) : AndroidIpcAdmission

    /** A one-shot authorization token was supplied but is invalid or expired. */
    data object InvalidToken : AndroidIpcAdmission

    /** The app signer changed; the old registration must be revoked first. */
    data object SignerMismatch : AndroidIpcAdmission

    /** The caller is not registered; user approval is required. */
    data object NeedsRegistration : AndroidIpcAdmission
}

internal const val ANDROID_IPC_INVALID_TOKEN_MESSAGE =
    "The one-shot authorization is invalid or expired."
internal const val ANDROID_IPC_SIGNER_MISMATCH_MESSAGE =
    "The app signer changed. Revoke the old registration in Keyguard before registering again."
internal const val ANDROID_IPC_TOO_MANY_APPROVALS_MESSAGE =
    "Too many pending approval requests."

@Suppress("LongParameterList")
internal suspend fun admitAndroidIpcCaller(
    context: Context,
    registrationRepository: AndroidIpcRegistrationRepository,
    caller: AndroidIpcCaller,
    protocol: String,
    action: String,
    requestDigest: String,
    token: String?,
    sessionIdentity: String?,
): AndroidIpcAdmission {
    val authorization = AndroidIpcApprovalCoordinator.consume(
        token = token,
        caller = caller,
        protocol = protocol,
        action = action,
        requestDigest = requestDigest,
        sessionIdentity = sessionIdentity,
    )
    if (token != null && authorization == null) {
        return AndroidIpcAdmission.InvalidToken
    }
    val registered = authorization?.registerApp != true ||
            (
                isCurrentAndroidIpcCaller(context, caller) &&
                        registrationRepository.register(caller)
                )
    return when {
        !registered -> AndroidIpcAdmission.SignerMismatch

        else -> when (registrationRepository.status(caller)) {
            AndroidIpcRegistrationRepository.Status.SIGNER_MISMATCH -> {
                AndroidIpcApprovalCoordinator.invalidateCaller(caller.packageName)
                AndroidIpcAdmission.SignerMismatch
            }

            AndroidIpcRegistrationRepository.Status.NOT_REGISTERED ->
                AndroidIpcAdmission.NeedsRegistration

            AndroidIpcRegistrationRepository.Status.REGISTERED -> {
                registrationRepository.recordUse(caller)
                AndroidIpcAdmission.Admitted(authorization)
            }
        }
    }
}
