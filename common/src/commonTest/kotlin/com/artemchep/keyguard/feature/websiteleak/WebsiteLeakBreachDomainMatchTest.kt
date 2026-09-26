package com.artemchep.keyguard.feature.websiteleak

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebsiteLeakBreachDomainMatchTest {
    @Test
    fun exactDomainMatches() {
        assertTrue(isBreachDomainMatch(host = "example.com", domain = "example.com"))
    }

    @Test
    fun subdomainMatches() {
        assertTrue(isBreachDomainMatch(host = "login.example.com", domain = "example.com"))
        assertTrue(isBreachDomainMatch(host = "a.b.example.com", domain = "example.com"))
    }

    @Test
    fun unrelatedSuffixDoesNotMatch() {
        assertFalse(isBreachDomainMatch(host = "notexample.com", domain = "example.com"))
        assertFalse(isBreachDomainMatch(host = "example.com.evil.net", domain = "example.com"))
    }

    @Test
    fun parentDomainDoesNotMatchBreachSubdomain() {
        assertFalse(isBreachDomainMatch(host = "example.com", domain = "login.example.com"))
    }

    @Test
    fun comparisonIgnoresCase() {
        assertTrue(isBreachDomainMatch(host = "Login.Example.COM", domain = "example.com"))
        assertTrue(isBreachDomainMatch(host = "example.com", domain = "EXAMPLE.com"))
    }

    @Test
    fun missingOrBlankDomainDoesNotMatch() {
        assertFalse(isBreachDomainMatch(host = "example.com", domain = null))
        assertFalse(isBreachDomainMatch(host = "example.com", domain = "  "))
    }
}
