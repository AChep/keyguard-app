package com.artemchep.keyguard.di

import com.artemchep.keyguard.common.service.environment.EnvironmentAccess
import com.artemchep.keyguard.common.service.environment.EnvironmentAccessImpl
import com.artemchep.keyguard.common.service.environment.EnvironmentBrokerHandler
import com.artemchep.keyguard.common.service.environment.EnvironmentDirectoryAccess
import com.artemchep.keyguard.common.service.environment.EnvironmentMcp
import com.artemchep.keyguard.common.service.environment.EnvironmentMcpBrokerHandler
import com.artemchep.keyguard.common.service.environment.EnvironmentMcpHost
import com.artemchep.keyguard.common.service.environment.EnvironmentMcpImpl
import com.artemchep.keyguard.common.service.environment.EnvironmentMountBrokerHandler
import com.artemchep.keyguard.common.service.environment.EnvironmentMounts
import com.artemchep.keyguard.common.service.environment.EnvironmentMountsHost
import com.artemchep.keyguard.common.service.environment.EnvironmentMountsImpl
import com.artemchep.keyguard.common.service.environment.EnvironmentRepository
import com.artemchep.keyguard.common.service.environment.EnvironmentRepositoryImpl
import com.artemchep.keyguard.common.service.environment.EnvironmentSessionAccess
import com.artemchep.keyguard.common.service.environment.EnvironmentSessionDependencies
import com.artemchep.keyguard.common.service.environment.EnvironmentSessionTracker
import com.artemchep.keyguard.common.service.environment.EnvironmentVault
import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.koin.core.scope.Scope
import org.koin.dsl.binds
import org.koin.dsl.module
import org.koin.dsl.onClose

private class EnvironmentApplicationScope {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

class ApplicationEnvironmentsModule {
    val module = module {
        single { EnvironmentApplicationScope() } onClose { it?.scope?.cancel() }
        single<EnvironmentSessionAccess> {
            EnvironmentSessionAccess { key ->
                key.session.resolve {
                    EnvironmentSessionDependencies(
                        getCiphers = get(),
                        getProfiles = get(),
                        getCollections = get(),
                        addCipher = get(),
                        trashCipher = get(),
                        scope = requireNotNull(getSource<VaultScopeSource>()).coroutineScope,
                    )
                }
            }
        }
        single { EnvironmentVault(getVaultSession = get(), sessionAccess = get()) }
        single {
            EnvironmentAccessImpl(
                vault = get(), getVaultSession = get(), cryptoGenerator = get(),
                scope = get<EnvironmentApplicationScope>().scope,
                mcpApprovalCacheConfig = get<SettingsReadRepository>().getEnvironmentsMcpApprovalCacheConfig(),
            )
        } binds arrayOf(EnvironmentAccess::class)
        single {
            EnvironmentMountsImpl(
                vault = get(), access = get(), cryptoGenerator = get(), storeFactory = get(), json = get(),
                scope = get<EnvironmentApplicationScope>().scope,
            )
        } binds arrayOf(EnvironmentMounts::class, EnvironmentMountsHost::class)
        single {
            EnvironmentMcpImpl(
                access = get(), storeFactory = get(), json = get(),
                scope = get<EnvironmentApplicationScope>().scope,
            )
        } binds arrayOf(EnvironmentMcp::class, EnvironmentMcpHost::class)
        single {
            EnvironmentSessionTracker(
                getVaultSession = get(), sessionAccess = get(), access = get(), mounts = get(),
                scope = get<EnvironmentApplicationScope>().scope,
            )
        }
        single {
            EnvironmentRepositoryImpl(vault = get(), access = get(), mounts = get(), tracker = get())
        } binds arrayOf(EnvironmentRepository::class)
    }
}

/** The handler of the private environment requests of one agent connection. */
internal fun Scope.environmentBrokerHandler(
    directoryAccess: EnvironmentDirectoryAccess,
) = EnvironmentBrokerHandler(
    mcp = EnvironmentMcpBrokerHandler(
        vault = get(), repository = get(), access = get(), mounts = get(), mcp = get(),
    ),
    mounts = EnvironmentMountBrokerHandler(
        vault = get(), repository = get(), access = get(), mounts = get(), directoryAccess = directoryAccess,
    ),
)
