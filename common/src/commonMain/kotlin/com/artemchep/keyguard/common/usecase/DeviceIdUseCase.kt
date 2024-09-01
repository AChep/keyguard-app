package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.flatMap
import com.artemchep.keyguard.common.io.flatTap
import com.artemchep.keyguard.common.io.io
import com.artemchep.keyguard.common.service.id.IdRepository
import org.kodein.di.DirectDI
import org.kodein.di.instance
import kotlin.uuid.Uuid

class DeviceIdUseCase(
    private val deviceIdRepository: IdRepository,
) : () -> IO<String> {
    @Volatile
    private var deviceId: String? = null

    constructor(directDI: DirectDI) : this(
        deviceIdRepository = directDI.instance(),
    )

    // Return a device ID from the memory, or try to load it from the
    // file system.
    override fun invoke(): IO<String> = deviceId?.let(::io) ?: getOrSet()

    private fun getOrSet() = deviceIdRepository
        .get()
        .flatMap { storedDeviceId ->
            val inMemoryId = synchronized(this@DeviceIdUseCase) {
                deviceId
                    ?: storedDeviceId
                        .takeUnless { it.isBlank() }
                    ?: getDeviceId()
                        .also(::deviceId::set)
            }

            io(inMemoryId)
                .run {
                    if (inMemoryId != storedDeviceId) {
                        flatTap(deviceIdRepository::put)
                    } else {
                        this
                    }
                }
        }
}

private fun getDeviceId() = Uuid.random().toString()
