#if os(macOS) || os(iOS)
import Foundation
import LocalAuthentication
import Security
import KeyguardShared

/// Keychain Services (`SecItem*`) implementation of the shared `KeychainBridge`.
/// Both the app and the AutoFill extension compile this file.
final class SecItemKeychainBridge: KeychainBridge {
    /// Shared across app + appex; deliberately not `Bundle.main.bundleIdentifier`,
    /// which differs between the two targets. The service attribute is part of the
    /// item's primary key, so the two processes must agree on it for the shared
    /// biometric-unlock key to resolve.
    private let service = "com.artemchep.keyguard.shared"
    /// nil → the keychain item uses the first `keychain-access-groups` entry from
    /// the entitlements automatically. Both targets list the shared group, so once
    /// provisioning grants it the app and appex share the same items with no
    /// explicit group here. (A hardcoded "<TeamID>.…" string needs a real
    /// Developer Team and would break the app under a personal dev cert.)
    private let accessGroup: String? = nil

    func set(account: String, value: String) -> Bool {
        let valueData = Data(value.utf8)
        let updateAttributes = [
            kSecValueData as String: valueData
        ]
        let updateStatus = SecItemUpdate(
            baseQuery(account) as CFDictionary,
            updateAttributes as CFDictionary
        )

        switch updateStatus {
        case errSecSuccess:
            return true
        case errSecItemNotFound:
            return add(account: account, valueData: valueData)
        default:
            logFailure("update", account: account, status: updateStatus)
            return false
        }
    }

    func get(account: String) -> String? {
        var query = baseQuery(account)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        switch status {
        case errSecSuccess:
            guard let data = item as? Data else {
                logUnexpectedData(account: account)
                return nil
            }
            guard let value = String(data: data, encoding: .utf8) else {
                logUnexpectedData(account: account)
                return nil
            }
            return value
        case errSecItemNotFound:
            return nil
        default:
            logFailure("read", account: account, status: status)
            return nil
        }
    }

    func delete(account: String) -> Bool {
        var succeeded = true
        let context = LAContext()
        context.interactionNotAllowed = true
        for var query in [baseQuery(account), biometricQuery(account)] {
            // Removing a key must never prompt, including during vault cleanup.
            query[kSecUseAuthenticationContext as String] = context
            let status = SecItemDelete(query as CFDictionary)
            if status != errSecSuccess && status != errSecItemNotFound {
                logFailure("delete", account: account, status: status)
                succeeded = false
            }
        }
        return succeeded
    }

    func setBiometric(account: String, value: String, context: LAContext) -> KeychainBiometricResult {
        #if os(iOS)
        let accessibility = kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly
        #else
        let accessibility = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        #endif
        var error: Unmanaged<CFError>?
        guard
            let accessControl = SecAccessControlCreateWithFlags(
                nil, accessibility, .biometryCurrentSet, &error
            )
        else {
            let status = error.map { OSStatus(CFErrorGetCode($0.takeRetainedValue())) } ?? errSecParam
            return KeychainBiometricResult(value: nil, status: status)
        }

        var attributes = biometricQuery(account)
        attributes[kSecAttrAccessControl as String] = accessControl
        attributes[kSecValueData as String] = Data(value.utf8)
        attributes[kSecUseAuthenticationContext as String] = context
        var status = SecItemAdd(attributes as CFDictionary, nil)
        if status == errSecDuplicateItem {
            // Access-control attributes are add-only. Explicit enrollment must
            // recreate the item to bind it to the current biometric set. If the
            // replacement fails, master-password unlock remains available.
            var query = biometricQuery(account)
            let deletionContext = LAContext()
            deletionContext.interactionNotAllowed = true
            query[kSecUseAuthenticationContext as String] = deletionContext
            let deletionStatus = SecItemDelete(query as CFDictionary)
            guard deletionStatus == errSecSuccess || deletionStatus == errSecItemNotFound else {
                return KeychainBiometricResult(value: nil, status: deletionStatus)
            }
            status = SecItemAdd(attributes as CFDictionary, nil)
        }
        guard status == errSecSuccess else {
            return KeychainBiometricResult(value: nil, status: status)
        }

        // Never use the legacy unprotected item for biometric reads. Only remove
        // it after saving the protected replacement successfully.
        var legacyQuery = baseQuery(account)
        let cleanupContext = LAContext()
        cleanupContext.interactionNotAllowed = true
        legacyQuery[kSecUseAuthenticationContext as String] = cleanupContext
        let cleanupStatus = SecItemDelete(legacyQuery as CFDictionary)
        if cleanupStatus != errSecSuccess && cleanupStatus != errSecItemNotFound {
            return KeychainBiometricResult(value: nil, status: cleanupStatus)
        }
        return KeychainBiometricResult(value: nil, status: errSecSuccess)
    }

    func getBiometric(account: String, context: LAContext) -> KeychainBiometricResult {
        var query = biometricQuery(account)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        query[kSecUseAuthenticationContext as String] = context
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        guard status == errSecSuccess else {
            return KeychainBiometricResult(value: nil, status: status)
        }
        guard let data = item as? Data,
            let value = String(data: data, encoding: .utf8)
        else {
            return KeychainBiometricResult(value: nil, status: errSecDecode)
        }
        return KeychainBiometricResult(value: value, status: errSecSuccess)
    }

    private func biometricQuery(_ account: String) -> [String: Any] {
        var query = baseQuery(account + ".biometry.v1")
        #if os(macOS)
        query[kSecUseDataProtectionKeychain as String] = true
        #endif
        return query
    }

    func contains(account: String) -> Bool {
        var query = baseQuery(account)
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        let status = SecItemCopyMatching(query as CFDictionary, nil)
        switch status {
        case errSecSuccess:
            return true
        case errSecItemNotFound:
            return false
        default:
            logFailure("contains", account: account, status: status)
            return false
        }
    }

    private func add(account: String, valueData: Data) -> Bool {
        var attributes = baseQuery(account)
        attributes[kSecValueData as String] = valueData
        let addStatus = SecItemAdd(attributes as CFDictionary, nil)
        switch addStatus {
        case errSecSuccess:
            return true
        case errSecDuplicateItem:
            let updateAttributes = [
                kSecValueData as String: valueData
            ]
            let retryStatus = SecItemUpdate(
                baseQuery(account) as CFDictionary,
                updateAttributes as CFDictionary
            )
            if retryStatus == errSecSuccess {
                return true
            }
            logFailure("update after duplicate add", account: account, status: retryStatus)
            return false
        default:
            logFailure("add", account: account, status: addStatus)
            return false
        }
    }

    private func baseQuery(_ account: String) -> [String: Any] {
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        if let accessGroup {
            query[kSecAttrAccessGroup as String] = accessGroup
        }
        return query
    }

    private func logFailure(_ operation: String, account: String, status: OSStatus) {
        NSLog(
            "%@",
            "Keyguard: keychain \(operation) failed for account '\(account)': \(statusDescription(status))"
        )
    }

    private func logUnexpectedData(account: String) {
        NSLog("%@", "Keyguard: keychain read returned non-UTF-8 data for account '\(account)'")
    }

    private func statusDescription(_ status: OSStatus) -> String {
        let message = SecCopyErrorMessageString(status, nil) as String?
        return "\(message ?? "OSStatus failure") (\(status))"
    }
}

/// Call at startup, before any secret is read or written.
func installKeychainBridge() {
    KeychainBridgeKt.registerKeychainBridge(bridge: SecItemKeychainBridge())
}

#endif
