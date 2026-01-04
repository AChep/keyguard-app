package com.artemchep.keyguard.common.service.gpmprivapps.impl

import arrow.core.partially1
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.DPrivilegedApp
import com.artemchep.keyguard.common.model.FileResource
import com.artemchep.keyguard.common.service.gpmprivapps.PrivilegedAppListEntity
import com.artemchep.keyguard.common.service.gpmprivapps.PrivilegedAppsService
import com.artemchep.keyguard.common.service.text.TextService
import com.artemchep.keyguard.common.service.text.readFromResourcesAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import org.kodein.di.DirectDI
import org.kodein.di.instance

class PrivilegedAppsServiceImpl(
    private val textService: TextService,
    private val json: Json,
) : PrivilegedAppsService {
    private val jsonIo = ::loadPrivilegedAppsJson
        .partially1(textService)

    constructor(
        directDI: DirectDI,
    ) : this(
        textService = directDI.instance(),
        json = directDI.instance(),
    )

    override fun get() = jsonIo

    override fun stringify(
        privilegedApps: List<DPrivilegedApp>,
    ): IO<String> = ioEffect(Dispatchers.Default) {
        val apps = privilegedApps
            .groupBy { privilegedApp ->
                privilegedApp.packageName
            }
            .map { (packageName, privilegedApps) ->
                val signatures = privilegedApps
                    .map { privilegedApp ->
                        val fingerprint = privilegedApp.certFingerprintSha256
                        PrivilegedAppListEntity.App.AndroidApp.Signature(
                            certFingerprintSha256 = fingerprint,
                        )
                    }
                PrivilegedAppListEntity.App.AndroidApp(
                    info = PrivilegedAppListEntity.App.AndroidApp.Info(
                        packageName = packageName,
                        signatures = signatures,
                    )
                )
            }
        val entity = PrivilegedAppListEntity(apps = apps)
        json.encodeToString(entity)
    }
}

private suspend fun loadPrivilegedAppsJson(
    textService: TextService,
) = textService.readFromResourcesAsText(FileResource.gpmPasskeysPrivilegedApps)
