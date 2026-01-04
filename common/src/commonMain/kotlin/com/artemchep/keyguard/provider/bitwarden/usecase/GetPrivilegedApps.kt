package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.model.DPrivilegedApp
import com.artemchep.keyguard.common.service.gpmprivapps.AppPrivilegedAppRepository
import com.artemchep.keyguard.common.service.gpmprivapps.UserPrivilegedAppRepository
import com.artemchep.keyguard.common.usecase.GetPrivilegedApps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import org.kodein.di.DirectDI
import org.kodein.di.instance
import kotlin.coroutines.CoroutineContext

/**
 * @author Artem Chepurnyi
 */
class GetPrivilegedAppsImpl(
    private val appPrivilegedAppRepository: AppPrivilegedAppRepository,
    private val userPrivilegedAppRepository: UserPrivilegedAppRepository,
    private val dispatcher: CoroutineContext = Dispatchers.Default,
) : GetPrivilegedApps {
    private val sharedFlow = combine(
        appPrivilegedAppRepository.get(),
        userPrivilegedAppRepository.get(),
    )  { appList, userList ->
        appList + userList
    }
        .map { list ->
            list
                .sorted()
        }
        .flowOn(dispatcher)
        .shareIn(
            scope = GlobalScope,
            started = SharingStarted.WhileSubscribed(5000L),
            replay = 1,
        )

    constructor(directDI: DirectDI) : this(
        appPrivilegedAppRepository = directDI.instance(),
        userPrivilegedAppRepository = directDI.instance(),
    )

    override fun invoke(): Flow<List<DPrivilegedApp>> = sharedFlow
}
