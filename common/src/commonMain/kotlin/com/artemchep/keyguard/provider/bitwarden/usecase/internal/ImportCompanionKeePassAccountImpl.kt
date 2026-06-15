package com.artemchep.keyguard.provider.bitwarden.usecase.internal

class ImportCompanionKeePassAccountImpl(
    private val addKeePassAccount: AddKeePassAccount,
) : ImportCompanionKeePassAccountUseCase {
    override fun invoke(
        params: ImportCompanionKeePassAccount.Params,
    ) = addKeePassAccount(
        AddKeePassAccountParams(
            mode = AddKeePassAccountParams.Mode.Open,
            dbUri = params.databaseUri,
            dbFileName = params.payload.databaseFileName,
            managedByApp = true,
            keyUri = params.keyUri,
            password = params.payload.password,
            syncMode = AddKeePassAccountParams.SyncMode.Direct,
        ),
    )
}
