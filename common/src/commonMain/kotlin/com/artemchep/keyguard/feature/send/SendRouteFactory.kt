package com.artemchep.keyguard.feature.send

import com.artemchep.keyguard.common.model.DAccount
import com.artemchep.keyguard.common.model.DSendFilter
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.account
import com.artemchep.keyguard.res.accounts

interface SendRouteFactory {
    fun create(
        args: SendRoute.Args,
    ): Route
}

object SendRouteFactoryDefault : SendRouteFactory {
    override fun create(
        args: SendRoute.Args,
    ): Route {
        return SendRoute(
            args = args,
        )
    }
}

//
// Account
//

fun SendRouteFactory.by(account: DAccount) = by(
    accounts = listOf(account),
)

@JvmName("byAccounts")
fun SendRouteFactory.by(accounts: Collection<DAccount>) = create(
    args = SendRoute.Args(
        appBar = SendRoute.Args.AppBar(
            title = accounts.joinToString { it.username ?: it.host },
            subtitle = if (accounts.size > 1) {
                TextHolder.Res(Res.string.accounts)
            } else {
                TextHolder.Res(Res.string.account)
            },
        ),
        filter = DSendFilter.Or(
            filters = accounts
                .map { account ->
                    DSendFilter.ById(
                        id = account.accountId(),
                        what = DSendFilter.ById.What.ACCOUNT,
                    )
                },
        ),
        preselect = false,
        canAddSecrets = false,
    ),
)
