package com.artemchep.keyguard.feature.add

import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.toIO
import com.artemchep.keyguard.common.model.DProfile
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.displayName
import com.artemchep.keyguard.feature.navigation.state.PersistedStorage
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

interface OwnershipHandle<T : OwnershipState> {
    val profilesFlow: Flow<List<DProfile>>
    val ciphersFlow: Flow<List<DSecret>>

    val stateSink: MutableStateFlow<T>
}

interface OwnershipState {
    val accountId: String?
}

suspend fun <T : OwnershipState> RememberStateFlowScope.ownershipHandle(
    key: String,
    profilesFlow: Flow<List<DProfile>>,
    ciphersFlow: Flow<List<DSecret>>,
    initialValue: T?,
    factory: (String?) -> T,
): OwnershipHandle<T> {
    val disk = loadDiskHandle(key)
    val accountIdSink = mutablePersistedFlow<String?>(
        key = "ownership.account_id",
        storage = PersistedStorage.InDisk(disk),
    ) { null }

    val initialState = kotlin.run {
        if (initialValue != null) {
            return@run initialValue
        }

        // Make an account that has the most ciphers a
        // default account.
        val accountId = ioEffect {
            val accountIds = profilesFlow.toIO().bind()
                .asSequence()
                .map { profile ->
                    profile.accountId()
                }
                .toSet()

            fun String.takeIfAccountIdExists() = this
                .takeIf { id ->
                    id in accountIds
                }

            val lastAccountId = accountIdSink.value?.takeIfAccountIdExists()
            if (lastAccountId != null) {
                return@ioEffect lastAccountId
            }

            val ciphers = ciphersFlow.toIO().bind()
            ciphers
                .asSequence()
                .map {
                    val groupKey = it.accountId
                    val score = 1.0 +
                            (if (it.organizationId == null) 0.8 else 0.0) +
                            (if (it.folderId != null) 0.2 else 0.0)
                    groupKey to score
                }
                .groupBy { it.first }
                .mapValues { it.value.sumOf { it.second } }
                // the one that has the highest score
                .maxByOrNull { entry -> entry.value }
                // account id
                ?.key
                ?.takeIfAccountIdExists()
                // Fallback to the first account ID we fetched in the
                // case there are not ciphers in the vault yet.
                ?: accountIds.firstOrNull()
        }.attempt().bind().getOrNull()

        factory(
            accountId,
        )
    }
    val sink = mutablePersistedFlow("ownership") {
        initialState
    }

    // If we are creating a new item, then remember the
    // last selected account to pre-select it next time.
    sink
        .map { it.accountId }
        .onEach(accountIdSink::value::set)
        .launchIn(screenScope)

    return object : OwnershipHandle<T> {
        override val profilesFlow: Flow<List<DProfile>> get() = profilesFlow

        override val ciphersFlow: Flow<List<DSecret>> get() = ciphersFlow

        override val stateSink: MutableStateFlow<T> get() = sink
    }
}

context(RememberStateFlowScope)
fun <T : OwnershipState> OwnershipHandle<T>.accountFlow(
    readOnly: Boolean,
): Flow<AddStateOwnershipElementHolder<String?>> {
    val accountFlow = combine(
        stateSink
            .map { it.accountId }
            .distinctUntilChanged(),
        profilesFlow,
    ) { accountId, profiles ->
        if (accountId == null) {
            val item = AddStateOwnership.Element.Item(
                key = "account.empty",
                title = translate(Res.string.account_none),
                stub = true,
            )
            val el = AddStateOwnership.Element(
                readOnly = readOnly,
                items = listOf(item),
            )
            return@combine AddStateOwnershipElementHolder(
                value = null,
                element = el,
            )
        }
        val profileOrNull = profiles
            .firstOrNull { it.accountId() == accountId }
        val el = AddStateOwnership.Element(
            readOnly = readOnly,
            items = listOfNotNull(profileOrNull)
                .map { account ->
                    val key = "account.${account.accountId()}"
                    AddStateOwnership.Element.Item(
                        key = key,
                        title = account.displayName,
                        text = account.accountHost,
                        accentColors = account.accentColor,
                    )
                },
        )
        AddStateOwnershipElementHolder(
            value = accountId,
            element = el,
        )
    }
    return accountFlow
}
