package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.model.DKeyguardPeerStatus
import com.artemchep.keyguard.common.model.DKeyguardRelayStatus
import com.artemchep.keyguard.common.model.DKeyguardSyncStatus
import com.artemchep.keyguard.common.model.P2pGroupId
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.common.usecase.GetKeyguardSyncStatus
import com.artemchep.keyguard.common.util.sqldelight.flatMapQueryToList
import com.artemchep.keyguard.provider.keyguard.session.KeyguardSessionRegistry
import com.artemchep.keyguard.provider.keyguard.session.keyguardTransportOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.time.Instant

class GetKeyguardSyncStatusImpl(
    private val db: VaultDatabaseManager,
    private val registry: KeyguardSessionRegistry,
) : GetKeyguardSyncStatus {
    override fun invoke(
        groupId: P2pGroupId,
    ): Flow<DKeyguardSyncStatus> {
        val rowsFlow = db
            .get()
            .effectMap { database ->
                database.p2pPeerStateQueries.getByGroupId(groupId = groupId.id)
            }
            .flatMapQueryToList(Dispatchers.Default)
        return combine(
            registry.status(groupId.id),
            rowsFlow,
        ) { status, rows ->
            val rowsById = rows.associateBy { it.deviceId }
            // Without a live session every peer is offline.
            val connections = status?.peers.orEmpty()
            val peers = (connections.keys + rowsById.keys).associateWith { deviceId ->
                val row = rowsById[deviceId]
                DKeyguardPeerStatus(
                    deviceId = deviceId,
                    connection = connections[deviceId] ?: DKeyguardPeerStatus.Connection.Offline,
                    lastSeenAt = row?.lastSeenAt?.let(Instant::fromEpochMilliseconds),
                    lastTransport = row?.lastTransport?.let(::keyguardTransportOf),
                )
            }
            DKeyguardSyncStatus(
                relay = DKeyguardRelayStatus(
                    state = status?.relay ?: DKeyguardRelayStatus.State.OFFLINE,
                    revoked = status?.revoked == true,
                ),
                peers = peers,
                tooLarge = status?.tooLarge,
                storage = status?.storage,
            )
        }.distinctUntilChanged()
    }
}
