package com.artemchep.keyguard.common.service.gpmprivapps

import com.artemchep.keyguard.common.model.DPrivilegedApp
import com.artemchep.keyguard.common.service.database.DatabaseDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.kodein.di.DirectDI
import org.kodein.di.instance

@Serializable
data class PrivilegedAppListEntity(
    val apps: List<App>,
) {
    @Serializable
    sealed interface App {
        @Serializable
        @SerialName("android")
        data class AndroidApp(
            val info: Info,
        ) : App {
            @Serializable
            data class Info(
                @SerialName("package_name")
                val packageName: String,
                val signatures: List<Signature>,
            )

            @Serializable
            data class Signature(
                val build: String = "release",
                @SerialName("cert_fingerprint_sha256")
                val certFingerprintSha256: String,
            )
        }

        @Serializable
        @SerialName("unknown")
        data object Unknown : App
    }
}

class AppPrivilegedAppRepositoryImpl(
    private val privilegedAppsService: PrivilegedAppsService,
    private val json: Json,
    private val dispatcher: CoroutineDispatcher,
) : AppPrivilegedAppRepository {
    constructor(
        directDI: DirectDI,
    ) : this(
        privilegedAppsService = directDI.instance(),
        json = directDI.instance(),
        dispatcher = directDI.instance(tag = DatabaseDispatcher),
    )

    override fun get(): Flow<List<DPrivilegedApp>> = privilegedAppsService
        .get()
        .asFlow()
        .map { text ->
            val entity = json.decodeFromString<PrivilegedAppListEntity>(text)
            entity.apps
                .mapIndexedNotNull { appIndex, app ->
                    val androidApp = app as? PrivilegedAppListEntity.App.AndroidApp
                        ?: return@mapIndexedNotNull null
                    androidApp
                        .info
                        .signatures
                        .mapIndexedNotNull { signatureIndex, signature ->
                            // TODO: Would be nice to surface Build.TYPE instead
                            //  of hardcoding the release OS build type.
                            if (signature.build != "release") {
                                return@mapIndexedNotNull null
                            }
                            DPrivilegedApp(
                                id = "app_${appIndex}_$signatureIndex",
                                name = null,
                                packageName = app.info.packageName,
                                certFingerprintSha256 = signature.certFingerprintSha256,
                                createdDate = null,
                                source = DPrivilegedApp.Source.APP,
                            )
                        }
                }
                .flatten()
        }
}
