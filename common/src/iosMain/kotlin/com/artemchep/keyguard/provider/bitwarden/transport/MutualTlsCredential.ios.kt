@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.artemchep.keyguard.provider.bitwarden.transport

import cnames.structs.__CFDictionary
import cnames.structs.__SecIdentity
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import platform.CoreFoundation.CFArrayGetCount
import platform.CoreFoundation.CFArrayGetValueAtIndex
import platform.CoreFoundation.CFArrayRefVar
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryGetValue
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFRetain
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.Foundation.CFBridgingRelease
import platform.Foundation.NSURLCredential
import platform.Foundation.NSURLCredentialPersistence
import platform.Foundation.credentialWithIdentity
import platform.Security.SecPKCS12Import
import platform.Security.errSecSuccess
import platform.Security.kSecImportExportPassphrase
import platform.Security.kSecImportItemCertChain
import platform.Security.kSecImportItemIdentity

internal actual fun importMutualTlsCredential(
    data: CFDataRef,
    password: String,
    privateKeyDer: ByteArray,
): NSURLCredential = memScoped {
    val options = requireNotNull(CFDictionaryCreateMutable(null, 0, null, null))
    val passphrase = requireNotNull(CFStringCreateWithCString(null, password, kCFStringEncodingUTF8))
    val items = alloc<CFArrayRefVar>()
    items.value = null
    try {
        CFDictionarySetValue(options, kSecImportExportPassphrase, passphrase)
        // iOS imports into process memory by default, including before iOS 18.
        check(SecPKCS12Import(data, options, items.ptr) == errSecSuccess) { "Could not import the client certificate." }
        val array = requireNotNull(items.value)
        check(CFArrayGetCount(array) == 1L)
        val item = requireNotNull(CFArrayGetValueAtIndex(array, 0)).reinterpret<__CFDictionary>()
        val identity = requireNotNull(CFDictionaryGetValue(item, kSecImportItemIdentity)).reinterpret<__SecIdentity>()
        val chain = CFDictionaryGetValue(item, kSecImportItemCertChain)?.let {
            CFBridgingRelease(CFRetain(it)) as? List<*>
        }
        NSURLCredential.credentialWithIdentity(
            identity = identity,
            certificates = chain,
            persistence = NSURLCredentialPersistence.NSURLCredentialPersistenceNone,
        )
    } finally {
        items.value?.let(::CFRelease)
        CFRelease(passphrase)
        CFRelease(options)
    }
}
