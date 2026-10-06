package com.artemchep.keyguard.util.dns

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.util.Hashtable
import javax.naming.Context
import javax.naming.NameNotFoundException
import javax.naming.NamingException
import javax.naming.directory.InitialDirContext

private const val JNDI_DNS_CONTEXT_FACTORY = "com.sun.jndi.dns.DnsContextFactory"
private const val JNDI_DNS_PROVIDER_URL = "dns:"
private const val JNDI_DNS_TIMEOUT_INITIAL_KEY = "com.sun.jndi.dns.timeout.initial"
private const val JNDI_DNS_TIMEOUT_INITIAL_MS = "1500"
private const val JNDI_DNS_TIMEOUT_RETRIES_KEY = "com.sun.jndi.dns.timeout.retries"
private const val JNDI_DNS_TIMEOUT_RETRIES = "2"
private const val ATTRIBUTE_TXT = "TXT"

actual fun createDnsTxtResolver(): DnsTxtResolver = JndiDnsTxtResolver()

/**
 * Queries through the JDK's JNDI DNS provider. It reads the system resolver
 * configuration and ships with the packaged desktop runtime.
 *
 * The provider sends its own plain-text queries to the configured nameservers
 * instead of calling the OS resolver. It therefore bypasses OS-level encrypted
 * DNS (Windows DoH, macOS DNS profiles) and, on macOS, the per-interface
 * resolvers of a VPN. Calling the OS resolver API would fix both.
 */
internal class JndiDnsTxtResolver : DnsTxtResolver {
    override suspend fun lookupTxt(name: String): List<String> {
        val fqdn = requireValidDnsName(name) + "."
        return runInterruptible(Dispatchers.IO) {
            queryTxt(fqdn)
        }
    }

    private fun queryTxt(fqdn: String): List<String> {
        val environment = Hashtable<String, String>().apply {
            put(Context.INITIAL_CONTEXT_FACTORY, JNDI_DNS_CONTEXT_FACTORY)
            put(Context.PROVIDER_URL, JNDI_DNS_PROVIDER_URL)
            put(JNDI_DNS_TIMEOUT_INITIAL_KEY, JNDI_DNS_TIMEOUT_INITIAL_MS)
            put(JNDI_DNS_TIMEOUT_RETRIES_KEY, JNDI_DNS_TIMEOUT_RETRIES)
        }
        return try {
            val context = InitialDirContext(environment)
            try {
                context.readTxt(fqdn)
            } finally {
                context.close()
            }
        } catch (ignored: NameNotFoundException) {
            emptyList()
        } catch (e: NamingException) {
            throw DnsLookupException("DNS lookup failed.", e)
        }
    }

    private fun InitialDirContext.readTxt(fqdn: String): List<String> {
        val attribute = getAttributes(fqdn, arrayOf(ATTRIBUTE_TXT)).get(ATTRIBUTE_TXT)
            ?: return emptyList()
        val values = mutableListOf<String>()
        val enumeration = attribute.all
        try {
            while (enumeration.hasMore()) {
                values += decodeJndiTxtValue(enumeration.next().toString())
            }
        } finally {
            enumeration.close()
        }
        return values
    }
}
