#if os(macOS)
import AppKit
import Carbon.HIToolbox

/// A minimal zero-dependency wrapper around Carbon `RegisterEventHotKey`, the
/// sandbox-/App-Store-safe way to register a system-wide hotkey that needs **no
/// Accessibility permission**. Used for the Quick Search overlay (default
/// ⌘⇧Space). One instance == one registered hotkey; call `invalidate()` to
/// unregister explicitly.
// Carbon delivers hot-key events on the main run loop and registration/teardown must
// happen there too, so the whole wrapper — including the shared registry — lives on
// the main actor.
@MainActor
final class GlobalHotkey {
    private var hotKeyRef: EventHotKeyRef?
    private let id: UInt32

    private static var nextId: UInt32 = 1
    private static var registry: [UInt32: () -> Void] = [:]
    private static var eventHandlerRef: EventHandlerRef?
    /// 'KGRD'
    private static let signature: OSType = 0x4B47_5244

    /// `keyCode` is a Carbon virtual key code (e.g. `kVK_Space` = 49); `modifiers`
    /// is a Carbon modifier mask (e.g. `cmdKey | shiftKey`).
    init(keyCode: UInt32, modifiers: UInt32, callback: @escaping () -> Void) throws {
        self.id = GlobalHotkey.nextId
        GlobalHotkey.nextId += 1

        try GlobalHotkey.ensureEventHandlerInstalled()
        GlobalHotkey.registry[id] = callback

        var registeredHotKey: EventHotKeyRef?
        let hotKeyID = EventHotKeyID(signature: GlobalHotkey.signature, id: id)
        let registerStatus = RegisterEventHotKey(
            keyCode,
            modifiers,
            hotKeyID,
            GetApplicationEventTarget(),
            0,
            &registeredHotKey
        )
        guard registerStatus == noErr else {
            GlobalHotkey.registry[id] = nil
            _ = GlobalHotkey.removeEventHandlerIfIdle()
            throw GlobalHotkeyError.registerEventHotKey(registerStatus)
        }
        guard let registeredHotKey else {
            GlobalHotkey.registry[id] = nil
            _ = GlobalHotkey.removeEventHandlerIfIdle()
            throw GlobalHotkeyError.missingHotKeyRef
        }

        hotKeyRef = registeredHotKey
    }

    @discardableResult
    func invalidate() -> OSStatus {
        GlobalHotkey.registry[id] = nil

        var firstError: OSStatus = noErr
        if let hotKeyRef {
            let status = UnregisterEventHotKey(hotKeyRef)
            if status != noErr {
                firstError = status
            }
            self.hotKeyRef = nil
        }

        let handlerStatus = GlobalHotkey.removeEventHandlerIfIdle()
        if firstError == noErr {
            firstError = handlerStatus
        }
        return firstError
    }

    isolated deinit {
        _ = invalidate()
    }

    private static func ensureEventHandlerInstalled() throws {
        guard eventHandlerRef == nil else { return }
        var eventType = EventTypeSpec(
            eventClass: OSType(kEventClassKeyboard),
            eventKind: UInt32(kEventHotKeyPressed)
        )
        var installedHandler: EventHandlerRef?
        let status = InstallEventHandler(
            GetApplicationEventTarget(),
            { _, event, _ -> OSStatus in
                guard let event else { return OSStatus(eventNotHandledErr) }
                var hkID = EventHotKeyID()
                let status = GetEventParameter(
                    event,
                    EventParamName(kEventParamDirectObject),
                    EventParamType(typeEventHotKeyID),
                    nil,
                    MemoryLayout<EventHotKeyID>.size,
                    nil,
                    &hkID
                )
                guard status == noErr else { return status }
                guard hkID.signature == GlobalHotkey.signature else {
                    return OSStatus(eventNotHandledErr)
                }
                // Carbon dispatches hot-key events on the main thread, which is the
                // only place the registry is ever touched.
                return MainActor.assumeIsolated {
                    guard let callback = GlobalHotkey.registry[hkID.id] else {
                        return OSStatus(eventNotHandledErr)
                    }
                    callback()
                    return noErr
                }
            },
            1,
            &eventType,
            nil,
            &installedHandler
        )
        guard status == noErr else {
            throw GlobalHotkeyError.installEventHandler(status)
        }
        guard let installedHandler else {
            throw GlobalHotkeyError.missingEventHandlerRef
        }
        eventHandlerRef = installedHandler
    }

    private static func removeEventHandlerIfIdle() -> OSStatus {
        guard registry.isEmpty, let eventHandlerRef else { return noErr }
        let status = RemoveEventHandler(eventHandlerRef)
        if status == noErr {
            self.eventHandlerRef = nil
        }
        return status
    }
}

private enum GlobalHotkeyError: LocalizedError, CustomStringConvertible {
    case installEventHandler(OSStatus)
    case registerEventHotKey(OSStatus)
    case missingEventHandlerRef
    case missingHotKeyRef

    var description: String {
        switch self {
        case let .installEventHandler(status):
            "InstallEventHandler failed with OSStatus \(status)"
        case let .registerEventHotKey(status):
            "RegisterEventHotKey failed with OSStatus \(status)"
        case .missingEventHandlerRef:
            "InstallEventHandler succeeded without returning an EventHandlerRef"
        case .missingHotKeyRef:
            "RegisterEventHotKey succeeded without returning an EventHotKeyRef"
        }
    }

    var errorDescription: String? { description }
}

#endif
