package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.service.id.IdRepository
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@OptIn(ExperimentalAtomicApi::class)
class DeviceIdUseCase(
    private val deviceIdRepository: IdRepository,
) : () -> IO<String> {
    private val deviceId = AtomicReference<String?>(null)
    private val mutex = Mutex()

    // Return a device ID from the memory, or try to load it from the
    // file system.
    override fun invoke(): IO<String> = getOrSet()

    fun clear(): IO<Unit> = ioEffect {
        mutex.withLock {
            val cachedDeviceId = deviceId.load()
            deviceId.store(null)
            try {
                deviceIdRepository.put("")
                    .bind()
            } catch (e: Throwable) {
                deviceId.store(cachedDeviceId)
                throw e
            }
        }
    }

    private fun getOrSet() = ioEffect {
        mutex.withLock {
            deviceId.load()?.let { cachedDeviceId ->
                return@withLock cachedDeviceId
            }

            val storedDeviceId = deviceIdRepository.get()
                .bind()
            val newDeviceId = storedDeviceId
                .takeUnless { it.isBlank() }
                ?: getDeviceId()

            if (storedDeviceId.isBlank()) {
                deviceIdRepository.put(newDeviceId)
                    .bind()
            }

            deviceId.store(newDeviceId)

            newDeviceId
        }
    }
}

private fun getDeviceId() = Uuid.random().toString()
