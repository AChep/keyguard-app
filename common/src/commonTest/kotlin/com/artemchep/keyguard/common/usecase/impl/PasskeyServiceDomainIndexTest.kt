package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.passkey.PassKeyServiceInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PasskeyServiceDomainIndexTest {
    @Test
    fun `exact domain matches`() {
        val service = service("exact", "login.example.com")
        val index = PasskeyServiceDomainIndex(listOf(service))

        assertEquals(service, index.findFirstMatchOrNull("login.example.com"))
    }

    @Test
    fun `subdomain matches parent domain`() {
        val service = service("parent", "example.com")
        val index = PasskeyServiceDomainIndex(listOf(service))

        assertEquals(service, index.findFirstMatchOrNull("login.example.com"))
    }

    @Test
    fun `first catalog entry wins over a more exact later entry`() {
        val parent = service("parent", "example.com")
        val exact = service("exact", "login.example.com")
        val index = PasskeyServiceDomainIndex(listOf(parent, exact))

        assertEquals(parent, index.findFirstMatchOrNull("login.example.com"))
    }

    @Test
    fun `partial suffix does not match`() {
        val service = service("partial", "example.com")
        val index = PasskeyServiceDomainIndex(listOf(service))

        assertNull(index.findFirstMatchOrNull("notexample.com"))
    }

    @Test
    fun `first catalog entry wins for duplicate domains`() {
        val first = service("first", "example.com")
        val second = service("second", "example.com")
        val index = PasskeyServiceDomainIndex(listOf(first, second))

        assertEquals(first, index.findFirstMatchOrNull("example.com"))
    }

    private fun service(
        id: String,
        vararg domains: String,
    ) = PassKeyServiceInfo(
        id = id,
        name = id,
        domain = domains.first(),
        domains = domains.toSet(),
    )
}
