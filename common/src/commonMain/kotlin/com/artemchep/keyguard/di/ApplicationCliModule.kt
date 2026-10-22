package com.artemchep.keyguard.di

import com.artemchep.keyguard.common.service.cli.CliCommandDispatcher
import com.artemchep.keyguard.common.service.cli.CliRuntimeHost
import com.artemchep.keyguard.common.service.cli.CliService
import com.artemchep.keyguard.common.service.cli.CliServiceImpl
import com.artemchep.keyguard.common.service.cli.agent.CliAgentHost
import com.artemchep.keyguard.common.service.environment.EnvironmentRunCliCommand
import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.koin.dsl.binds
import org.koin.dsl.module
import org.koin.dsl.onClose

private class CliApplicationScope {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

class ApplicationCliModule {
    val module = module {
        single { CliApplicationScope() } onClose { it?.scope?.cancel() }
        single {
            CliServiceImpl(
                getVaultSession = get(), cryptoGenerator = get(), storeFactory = get(),
                scope = get<CliApplicationScope>().scope,
                approvalCacheConfig = get<SettingsReadRepository>().getCliApprovalCacheConfig(),
            )
        } binds arrayOf(CliService::class, CliRuntimeHost::class)
        single { EnvironmentRunCliCommand(cli = get(), vault = get(), repository = get(), access = get()) }
        single {
            // Every command of the CLI.
            CliCommandDispatcher(
                service = get(),
                handlers = listOf(
                    get<EnvironmentRunCliCommand>(),
                ),
            )
        }
        single {
            CliAgentHost(
                cli = get(), mcp = get(), mounts = get(), commands = get(),
                environments = { directoryAccess -> environmentBrokerHandler(directoryAccess) },
            )
        }
    }
}
