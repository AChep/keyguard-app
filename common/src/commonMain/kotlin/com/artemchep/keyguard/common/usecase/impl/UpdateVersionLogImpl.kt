package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.AppVersionLog
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.GetAppBuildRef
import com.artemchep.keyguard.common.usecase.GetAppVersionName
import com.artemchep.keyguard.common.usecase.UpdateVersionLog
import kotlin.time.Clock
import kotlinx.coroutines.flow.first

class UpdateVersionLogImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
    private val getAppBuildRef: GetAppBuildRef,
    private val getAppVersionName: GetAppVersionName,
) : UpdateVersionLog {
    override fun invoke(): IO<Unit> = ioEffect {
        val log = settingsReadWriteRepository
            .getAppVersionLog()
            .first()
            .toMutableList()

        val buildRef = getAppBuildRef().first()
        if (buildRef.isBlank()) {
            return@ioEffect
        }
        // Check if the first entry in the log
        // has the same build reference as the
        // current one. If so, we do not need to
        // update it.
        val last = log.firstOrNull()
        if (last?.ref == buildRef) {
            return@ioEffect
        }

        val version = getAppVersionName().first()
        val newEntry = AppVersionLog(
            version = version,
            ref = buildRef,
            timestamp = Clock.System.now(),
        )
        // Append the new entry to the start of the
        // log. Cap the size of the log to a small amount.
        log.add(0, newEntry)
        val newLog = log.take(3)

        // Save the updated log.
        settingsReadWriteRepository
            .setAppVersionLog(newLog)
            .bind()
    }
}
