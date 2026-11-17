package com.artemchep.keyguard.common.model

import com.artemchep.keyguard.common.service.logging.LogRepositoryBridge
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class EquivalentDomainsTest {
    private val groups = listOf(
        group(accountId = "a", "example.com", "example.net"),
        group(accountId = "b", "example.com", "example.org"),
        group(accountId = "b", "shop.example", "shop.test", excluded = true),
    )

    @Test
    fun `the equivalent domains of all accounts join the groups of every account`() {
        val domains = equivalentDomainsOfAllAccounts(groups)

        assertEquals(
            listOf("example.com", "example.net", "example.org"),
            domains.findEqDomains("example.com"),
        )
        assertEquals(listOf("example.com", "example.net"), domains.findEqDomains("example.net"))
        assertEquals(listOf("example.com", "example.org"), domains.findEqDomains("example.org"))
        // An excluded group counts for no account.
        assertEquals(listOf("shop.example"), domains.findEqDomains("shop.example"))
    }

    @Test
    fun `the builder gives the equivalent domains of all accounts and of each one`() = runTest {
        val builder = EquivalentDomainsBuilder(
            logRepository = LogRepositoryBridge(emptyList()),
            sharedAllEquivalentDomains = { groups },
        )

        assertEquals(equivalentDomainsOfAllAccounts(groups), builder.getAndCacheAll())
        assertEquals(
            listOf("example.com", "example.net"),
            builder.getAndCache("a").findEqDomains("example.com"),
        )
    }

    private fun group(
        accountId: String,
        vararg domains: String,
        excluded: Boolean = false,
    ) = DEquivalentDomains(
        id = "$accountId:${domains.first()}",
        accountId = accountId,
        global = false,
        excluded = excluded,
        domains = domains.toList(),
    )
}
