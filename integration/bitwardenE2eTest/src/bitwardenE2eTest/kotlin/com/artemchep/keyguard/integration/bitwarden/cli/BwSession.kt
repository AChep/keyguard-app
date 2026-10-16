package com.artemchep.keyguard.integration.bitwarden.cli

import com.artemchep.keyguard.integration.bitwarden.VaultwardenApi

internal suspend fun VaultwardenApi.execute(command: BwCommand.Remote): BwResult = when (command) {
    is BwCommand.Register -> register(command)
    is BwCommand.Seed -> seed(command)
    is BwCommand.Totp -> totp(command)
    is BwCommand.Org -> org(command)
    is BwCommand.Send -> send(command)
    is BwCommand.Attach -> attach(command)
}
