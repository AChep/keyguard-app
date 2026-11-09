package com.artemchep.keyguard.provider.keyguard

import com.artemchep.keyguard.core.store.bitwarden.P2pDevice
import com.artemchep.keyguard.core.store.bitwarden.P2pMembership

/** TEST ONLY: a device of a sync group, with placeholder keys. */
internal fun testP2pDevice() = P2pDevice(
    deviceId = "device",
    label = "Phone",
    platform = "android",
    noisePublicKeyBase64 = "noise-public",
    noisePrivateKeyBase64 = "noise-private",
    kemPublicKeyBase64 = "kem-public",
    kemPrivateKeyBase64 = "kem-private",
    signPublicKeyBase64 = "sign-public",
    signPrivateKeyBase64 = "sign-private",
)

/** TEST ONLY: a membership in the sync group [groupId], founded by [testP2pDevice]. */
internal fun testP2pMembership(groupId: String = "group") = P2pMembership(
    groupId = groupId,
    groupKeyBase64 = "group-key",
    founderId = "device",
    device = testP2pDevice(),
)
