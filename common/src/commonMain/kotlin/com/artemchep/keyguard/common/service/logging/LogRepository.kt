package com.artemchep.keyguard.common.service.logging

import com.artemchep.keyguard.platform.util.isRelease
import org.kodein.di.DirectDI
import org.kodein.di.allInstances

interface LogRepository : LogRepositoryBase

/**
 * A version that only exists in debug and gets completely
 * stripped in release builds.
 */
inline fun LogRepository.postDebug(
    tag: String,
    provideMassage: () -> String,
) {
    if (!isRelease) {
        val msg = provideMassage()
        post(tag, msg, level = LogLevel.DEBUG)
    }
}

class LogRepositoryBridge(
    private val logRepositoryList: List<LogRepositoryChild>,
) : LogRepository {
    constructor(
        directDI: DirectDI,
    ) : this(
        logRepositoryList = directDI.allInstances(),
    )

    override fun post(
        tag: String,
        message: String,
        level: LogLevel,
    ) {
        logRepositoryList.forEach { repo ->
            repo.post(
                tag = tag,
                message = message,
                level = level,
            )
        }
    }

    override suspend fun add(
        tag: String,
        message: String,
        level: LogLevel,
    ) {
        logRepositoryList.forEach { repo ->
            repo.add(
                tag = tag,
                message = message,
                level = level,
            )
        }
    }
}
