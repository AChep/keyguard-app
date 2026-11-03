@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.artemchep.keyguard.provider.bitwarden.transport

import cnames.structs.__SecCertificate
import cnames.structs.__SecIdentity
import cnames.structs.__SecKey
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFArrayGetCount
import platform.CoreFoundation.CFArrayGetValueAtIndex
import platform.CoreFoundation.CFArrayRefVar
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFGetTypeID
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFRetain
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.Foundation.CFBridgingRelease
import platform.Foundation.NSURLCredential
import platform.Foundation.NSURLCredentialPersistence
import platform.Foundation.credentialWithIdentity
import platform.Security.SEC_KEY_IMPORT_EXPORT_PARAMS_VERSION
import platform.Security.SecCertificateGetTypeID
import platform.Security.SecCertificateRefVar
import platform.Security.SecExternalFormatVar
import platform.Security.SecExternalItemType
import platform.Security.SecIdentityCopyCertificate
import platform.Security.SecIdentityCreate
import platform.Security.SecIdentityGetTypeID
import platform.Security.SecIdentityRef
import platform.Security.SecItemImport
import platform.Security.SecItemImportExportKeyParameters
import platform.Security.SecKeyGetTypeID
import platform.Security.SecKeyRef
import platform.Security.errSecSuccess
import platform.Security.kSecFormatOpenSSL
import platform.Security.kSecFormatPKCS12

internal actual fun importMutualTlsCredential(
    data: CFDataRef,
    password: String,
    privateKeyDer: ByteArray,
): NSURLCredential = memScoped {
    val passphrase = requireNotNull(CFStringCreateWithCString(null, password, kCFStringEncodingUTF8))
    val parameters = alloc<SecItemImportExportKeyParameters>()
    parameters.version = SEC_KEY_IMPORT_EXPORT_PARAMS_VERSION.toUInt()
    parameters.passphrase = passphrase
    parameters.flags = 0u
    parameters.alertTitle = null
    parameters.alertPrompt = null
    parameters.accessRef = null
    parameters.keyUsage = null
    parameters.keyAttributes = null
    val format = alloc<SecExternalFormatVar>()
    format.value = kSecFormatPKCS12
    val type = alloc<SecExternalItemType.Var>()
    type.value = SecExternalItemType.kSecItemTypeAggregate
    val items = alloc<CFArrayRefVar>()
    items.value = null
    var createdIdentity: SecIdentityRef? = null
    try {
        // A null destination keychain keeps macOS 14 imports in process memory.
        check(SecItemImport(data, null, format.ptr, type.ptr, 0u, parameters.ptr, null, items.ptr) == errSecSuccess) {
            "Could not import the client certificate."
        }
        val array = requireNotNull(items.value)
        val identities = (0 until CFArrayGetCount(array)).mapNotNull { index ->
            CFArrayGetValueAtIndex(array, index)?.takeIf { CFGetTypeID(it) == SecIdentityGetTypeID() }
        }
        val certificateRefs = (0 until CFArrayGetCount(array)).mapNotNull { index ->
            CFArrayGetValueAtIndex(array, index)?.takeIf { CFGetTypeID(it) == SecCertificateGetTypeID() }
        }
        // With no destination keychain, the legacy PKCS#12 importer may return
        // only certificates. Import the key separately and join it in memory.
        val identity = identities.singleOrNull()?.reinterpret<__SecIdentity>() ?: run {
            val key = importMutualTlsPrivateKey(privateKeyDer)
            val joined = try {
                certificateRefs.firstNotNullOfOrNull { certificate ->
                    SecIdentityCreate(null, certificate.reinterpret<__SecCertificate>(), key)
                }
            } finally {
                CFRelease(key)
            }
            requireNotNull(joined) { "The client certificate does not match its private key." }
            createdIdentity = joined
            joined
        }
        val leaf = alloc<SecCertificateRefVar>()
        leaf.value = null
        check(SecIdentityCopyCertificate(identity, leaf.ptr) == errSecSuccess)
        val leafCertificate = requireNotNull(leaf.value)
        val certificates = certificateRefs.mapNotNull { CFBridgingRelease(CFRetain(it)) }
        val leafObject = requireNotNull(CFBridgingRelease(leafCertificate))
        val chain = listOf(leafObject) + certificates.filter { it != leafObject }
        NSURLCredential.credentialWithIdentity(
            identity = identity,
            certificates = chain,
            persistence = NSURLCredentialPersistence.NSURLCredentialPersistenceNone,
        )
    } finally {
        createdIdentity?.let(::CFRelease)
        items.value?.let(::CFRelease)
        CFRelease(passphrase)
    }
}

/** Returns an owned key reference, independent of the temporary import array. */
private fun importMutualTlsPrivateKey(privateKeyDer: ByteArray): SecKeyRef = memScoped {
    require(privateKeyDer.isNotEmpty())
    val data = privateKeyDer.usePinned {
        CFDataCreate(null, it.addressOf(0).reinterpret(), privateKeyDer.size.convert())
    }
    requireNotNull(data)
    val format = alloc<SecExternalFormatVar>()
    format.value = kSecFormatOpenSSL
    val type = alloc<SecExternalItemType.Var>()
    type.value = SecExternalItemType.kSecItemTypePrivateKey
    val items = alloc<CFArrayRefVar>()
    items.value = null
    try {
        check(SecItemImport(data, null, format.ptr, type.ptr, 0u, null, null, items.ptr) == errSecSuccess) {
            "Could not import the client private key."
        }
        val keys = requireNotNull(items.value)
        check(CFArrayGetCount(keys) == 1L)
        val key = requireNotNull(CFArrayGetValueAtIndex(keys, 0))
        check(CFGetTypeID(key) == SecKeyGetTypeID())
        requireNotNull(CFRetain(key)).reinterpret<__SecKey>()
    } finally {
        items.value?.let(::CFRelease)
        CFRelease(data)
    }
}
