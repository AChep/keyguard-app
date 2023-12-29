package com.artemchep.keyguard.provider.bitwarden.entity

import kotlinx.serialization.SerialName

enum class PolicyTypeEntity {
    // Requires users to have 2fa enabled
    @SerialName("0")
    TwoFactorAuthentication,

    // Sets minimum requirements for master password complexity
    @SerialName("1")
    MasterPassword,

    // Sets minimum requirements/default type for generated
    // passwords/passphrases
    @SerialName("2")
    PasswordGenerator,

    // Allows users to only be apart of one organization
    @SerialName("3")
    SingleOrg,

    // Requires users to authenticate with SSO
    @SerialName("4")
    RequireSso,

    // Disables personal vault ownership for adding/cloning items
    @SerialName("5")
    PersonalOwnership,

    // Disables the ability to create and edit Bitwarden Sends
    @SerialName("6")
    DisableSend,

    // Sets restrictions or defaults for Bitwarden Sends
    @SerialName("7")
    SendOptions,

    // Allows organizations to use reset password,
    // also can enable auto-enrollment during invite flow
    @SerialName("8")
    ResetPassword,

    // Sets the maximum allowed vault timeout
    @SerialName("9")
    MaximumVaultTimeout,

    // Disable personal vault export
    @SerialName("10")
    DisablePersonalVaultExport,
}
