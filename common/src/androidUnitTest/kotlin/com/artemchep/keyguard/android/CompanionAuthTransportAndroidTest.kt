package com.artemchep.keyguard.android

import com.artemchep.keyguard.feature.auth.companion.CompanionAuthPhoneCapabilitySource
import com.artemchep.keyguard.feature.auth.companion.companionAuthPhoneAvailabilityFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class CompanionAuthTransportAndroidTest {
    @Test
    fun `availability flow emits initial reachable snapshot`() = runTest {
        val source = FakeCompanionAuthPhoneCapabilitySource(
            initialReachable = true,
        )

        val values = companionAuthPhoneAvailabilityFlow(source)
            .take(1)
            .toList()

        assertEquals(listOf(true), values)
    }

    @Test
    fun `availability flow emits reachable to unreachable transition`() = runTest {
        val source = FakeCompanionAuthPhoneCapabilitySource(
            initialReachable = true,
        )

        val valuesDeferred = async {
            companionAuthPhoneAvailabilityFlow(source)
                .take(2)
                .toList()
        }
        runCurrent()
        source.emitAvailability(false)

        assertEquals(listOf(true, false), valuesDeferred.await())
    }

    @Test
    fun `availability flow suppresses duplicate updates`() = runTest {
        val source = FakeCompanionAuthPhoneCapabilitySource(
            initialReachable = false,
        )

        val valuesDeferred = async {
            companionAuthPhoneAvailabilityFlow(source)
                .take(2)
                .toList()
        }
        runCurrent()
        source.emitAvailability(false)
        source.emitAvailability(true)

        assertEquals(listOf(false, true), valuesDeferred.await())
    }
}

private class FakeCompanionAuthPhoneCapabilitySource(
    private val initialReachable: Boolean,
) : CompanionAuthPhoneCapabilitySource {
    private val updates = MutableSharedFlow<Boolean>(
        extraBufferCapacity = 4,
    )

    override suspend fun getReachablePhoneNodes() =
        if (initialReachable) {
            listOf(FakeNode)
        } else {
            emptyList()
        }

    override fun observeReachablePhoneNodes(): Flow<List<com.google.android.gms.wearable.Node>> =
        updates.map { isReachable ->
            if (isReachable) {
                listOf(FakeNode)
            } else {
                emptyList()
            }
        }

    fun emitAvailability(
        isReachable: Boolean,
    ) {
        updates.tryEmit(isReachable)
    }
}

private object FakeNode : com.google.android.gms.wearable.Node {
    override fun getDisplayName(): String = "node"

    override fun getId(): String = "node-id"

    override fun isNearby(): Boolean = true
}
