package com.artemchep.keyguard.feature.keyguardsync.devices

import com.artemchep.keyguard.common.usecase.GetKeyguardDevices
import com.artemchep.keyguard.common.usecase.GetKeyguardSyncStatus
import com.artemchep.keyguard.common.usecase.LeaveKeyguardGroup
import com.artemchep.keyguard.common.usecase.RevokeKeyguardDevice
import org.koin.core.scope.Scope

/** The use cases the devices screen reads and changes the sync group with. */
data class KeyguardDevicesUseCases(
    val getKeyguardDevices: GetKeyguardDevices,
    val getKeyguardSyncStatus: GetKeyguardSyncStatus,
    val revokeKeyguardDevice: RevokeKeyguardDevice,
    val leaveKeyguardGroup: LeaveKeyguardGroup,
)

/** Returns the use cases of the devices screen from this vault session scope. */
fun Scope.keyguardDevicesUseCases() = KeyguardDevicesUseCases(
    getKeyguardDevices = get(),
    getKeyguardSyncStatus = get(),
    revokeKeyguardDevice = get(),
    leaveKeyguardGroup = get(),
)
