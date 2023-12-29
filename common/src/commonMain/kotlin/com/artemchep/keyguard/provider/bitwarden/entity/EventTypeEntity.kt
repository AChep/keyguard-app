package com.artemchep.keyguard.provider.bitwarden.entity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class EventTypeEntity {
    @SerialName("1000")
    User_LoggedIn,

    @SerialName("1001")
    User_ChangedPassword,

    @SerialName("1002")
    User_Updated2fa,

    @SerialName("1003")
    User_Disabled2fa,

    @SerialName("1004")
    User_Recovered2fa,

    @SerialName("1005")
    User_FailedLogIn,

    @SerialName("1006")
    User_FailedLogIn2fa,

    @SerialName("1007")
    User_ClientExportedVault,

    @SerialName("1008")
    User_UpdatedTempPassword,

    @SerialName("1100")
    Cipher_Created,

    @SerialName("1101")
    Cipher_Updated,

    @SerialName("1102")
    Cipher_Deleted,

    @SerialName("1103")
    Cipher_AttachmentCreated,

    @SerialName("1104")
    Cipher_AttachmentDeleted,

    @SerialName("1105")
    Cipher_Shared,

    @SerialName("1106")
    Cipher_UpdatedCollections,

    @SerialName("1107")
    Cipher_ClientViewed,

    @SerialName("1108")
    Cipher_ClientToggledPasswordVisible,

    @SerialName("1109")
    Cipher_ClientToggledHiddenFieldVisible,

    @SerialName("1110")
    Cipher_ClientToggledCardCodeVisible,

    @SerialName("1111")
    Cipher_ClientCopiedPassword,

    @SerialName("1112")
    Cipher_ClientCopiedHiddenField,

    @SerialName("1113")
    Cipher_ClientCopiedCardCode,

    @SerialName("1114")
    Cipher_ClientAutofilled,

    @SerialName("1115")
    Cipher_SoftDeleted,

    @SerialName("1116")
    Cipher_Restored,

    @SerialName("1117")
    Cipher_ClientToggledCardNumberVisible,

    @SerialName("1300")
    Collection_Created,

    @SerialName("1301")
    Collection_Updated,

    @SerialName("1302")
    Collection_Deleted,

    @SerialName("1400")
    Group_Created,

    @SerialName("1401")
    Group_Updated,

    @SerialName("1402")
    Group_Deleted,

    @SerialName("1500")
    OrganizationUser_Invited,

    @SerialName("1501")
    OrganizationUser_Confirmed,

    @SerialName("1502")
    OrganizationUser_Updated,

    @SerialName("1503")
    OrganizationUser_Removed,

    @SerialName("1504")
    OrganizationUser_UpdatedGroups,

    @SerialName("1505")
    OrganizationUser_UnlinkedSso,

    @SerialName("1506")
    OrganizationUser_ResetPassword_Enroll,

    @SerialName("1507")
    OrganizationUser_ResetPassword_Withdraw,

    @SerialName("1508")
    OrganizationUser_AdminResetPassword,

    @SerialName("1509")
    OrganizationUser_ResetSsoLink,

    @SerialName("1600")
    Organization_Updated,

    @SerialName("1601")
    Organization_PurgedVault,

    @SerialName("1602")
    Organization_ClientExportedVault,

    @SerialName("1603")
    Organization_VaultAccessed,

    @SerialName("1700")
    Policy_Updated,

    @SerialName("1800")
    ProviderUser_Invited,

    @SerialName("1801")
    ProviderUser_Confirmed,

    @SerialName("1802")
    ProviderUser_Updated,

    @SerialName("1803")
    ProviderUser_Removed,

    @SerialName("1900")
    ProviderOrganization_Created,

    @SerialName("1901")
    ProviderOrganization_Added,

    @SerialName("1902")
    ProviderOrganization_Removed,

    @SerialName("1903")
    ProviderOrganization_VaultAccessed,
}
