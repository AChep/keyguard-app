package com.artemchep.keyguard.provider.bitwarden.model

import com.artemchep.keyguard.provider.bitwarden.ServerEnv

/**
 * A server environment discovered from the domain of a sign-in email.
 *
 * DNS answers are not authenticated, so the environment must be shown to
 * the user before it is used to sign in.
 */
data class ServerDiscoveryCandidate(
    val env: ServerEnv,
    /** `true` when the server shares the registrable domain of the email. */
    val sameDomain: Boolean,
)
