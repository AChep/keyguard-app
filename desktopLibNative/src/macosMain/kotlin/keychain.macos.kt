@file:OptIn(ExperimentalForeignApi::class)

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFAutorelease
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanFalse
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataWithBytes
import platform.Security.SecCopyErrorMessageString
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrIsPermanent
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.darwin.OSStatus
import platform.darwin.noErr
import kotlin.experimental.ExperimentalNativeApi

@OptIn(ExperimentalNativeApi::class)
@CName("keychainAddPassword")
public actual fun keychainAddPassword(
    id: String,
    password: String,
): Boolean = memScoped {
    val account = "com.artemchep.keyguard"
    val data = NSData.dataWithBytes(
        bytes = password.cstr.ptr,
        length = password.length.toULong(),
    )
    context(id, account, data) { (retainedId, retainedAccount, retainedData) ->
        val query = queryWithContext(
            kSecClass to kSecClassGenericPassword,
            kSecAttrAccount to retainedAccount,
            kSecAttrService to retainedId,
            kSecAttrIsPermanent to kCFBooleanTrue,
            kSecValueData to retainedData,
        )

        SecItemDelete(query)

        val status = SecItemAdd(query, null)
        val success = status.isSuccess()
        // Log the failure
        if (!success) {
            val cause = status.toCauseString()
            println("Keychain Add: Failed to add an item to the keychain: $cause")
        }

        success
    }
}

@OptIn(ExperimentalNativeApi::class)
@CName("keychainGetPassword")
public actual fun keychainGetPassword(
    id: String,
): String? = memScoped {
    val account = "com.artemchep.keyguard"
    context(id, account) { (retainedId, retainedAccount) ->
        val query = queryWithContext(
            kSecClass to kSecClassGenericPassword,
            kSecAttrAccount to retainedAccount,
            kSecAttrService to retainedId,
            kSecReturnData to kCFBooleanTrue,
        )

        val result = alloc<CFTypeRefVar>()
        val status = SecItemCopyMatching(query, result.ptr)
        val success = status.isSuccess()
        // Log the failure
        if (!success) {
            val cause = status.toCauseString()
            println("Keychain Get: Failed to get an item from the keychain: $cause")
            return@context null
        }

        val data = CFBridgingRelease(result.value) as NSData
        data.toKotlinString()
    }
}

@OptIn(ExperimentalNativeApi::class)
@CName("keychainDeletePassword")
public actual fun keychainDeletePassword(id: String): Boolean = memScoped {
    val account = "com.artemchep.keyguard"
    context(id, account) { (retainedId, retainedAccount) ->
        val query = queryWithContext(
            kSecClass to kSecClassGenericPassword,
            kSecAttrAccount to retainedAccount,
            kSecAttrService to retainedId,
        )

        val status = SecItemDelete(query)
        val success = status.isSuccess()
        // Log the failure
        if (!success) {
            val cause = status.toCauseString()
            println("Keychain Delete: Failed to delete an item from the keychain: $cause")
        }

        success
    }
}

@OptIn(ExperimentalNativeApi::class)
@CName("keychainContainsPassword")
public actual fun keychainContainsPassword(id: String): Boolean = memScoped {
    val account = "com.artemchep.keyguard"
    context(id, account) { (retainedId, retainedAccount) ->
        val query = queryWithContext(
            kSecClass to kSecClassGenericPassword,
            kSecAttrAccount to retainedAccount,
            kSecAttrService to retainedId,
            kSecReturnData to kCFBooleanFalse,
        )

        val status = SecItemCopyMatching(query, null)
        val success = status.isSuccess()
        // Log the failure
        if (!success) {
            val cause = status.toCauseString()
            println("Keychain Contains: Failed to check if item exists in the keychain: $cause")
        }

        success
    }
}

@Suppress("CAST_NEVER_SUCCEEDS")
private inline fun NSData.toKotlinString(): String {
    return NSString.create(data = this, encoding = NSUTF8StringEncoding) as String
}

private class Context(
    val refs: Map<CFStringRef?, CFTypeRef?>,
) {
    fun queryWithContext(
        vararg pairs: Pair<CFStringRef?, CFTypeRef?>,
    ) = kotlin.run {
        val finalPairs = refs.entries
            .map { it.toPair() }
            .toTypedArray() + pairs
        query(*finalPairs)
    }
}

private fun query(
    vararg pairs: Pair<CFStringRef?, CFTypeRef?>,
): CFDictionaryRef? {
    val map = mapOf(*pairs)
    val dict = CFDictionaryCreateMutable(
        allocator = null,
        capacity = map.size.convert(),
        kCFTypeDictionaryKeyCallBacks.ptr,
        kCFTypeDictionaryValueCallBacks.ptr,
    )
    map.entries.forEach {
        CFDictionaryAddValue(dict, it.key, it.value)
    }
    CFAutorelease(dict)
    return dict
}

private fun <T> context(
    vararg values: Any?,
    block: Context.(List<CFTypeRef?>) -> T,
): T {
    val custom = arrayOf(*values)
        .map(::CFBridgingRetain)
    return try {
        val context = Context(emptyMap())
        block.invoke(context, custom)
    } finally {
        custom.forEach(::CFBridgingRelease)
    }
}

@OptIn(ExperimentalForeignApi::class)
@Suppress("CAST_NEVER_SUCCEEDS")
private inline fun OSStatus.toCauseString(): String {
    val nsString = CFBridgingRelease(SecCopyErrorMessageString(this, null)) as? NSString
    val message = (nsString as? String)
        ?: "Keychain error: $this"
    return message
}

private fun OSStatus.isSuccess(): Boolean = toUInt() == noErr
