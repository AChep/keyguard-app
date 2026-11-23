import Observation
import KeyguardShared

/// All scenes wait for storage before creating the process-wide core or its workers.
@MainActor
@Observable
public final class AppStartup {
    public static let shared = AppStartup()
    @usableFromInline static let storageErrorTitleKey = "storage_error_title"

    public private(set) var model: AppViewModel?
    private(set) var errorKey: String?
    private(set) var errorTitleKey = AppStartup.storageErrorTitleKey
    private(set) var errorDetails: String?
    public var hasError: Bool { errorKey != nil }

    /// The macOS process owns services even when no window or popover is visible.
    public var onStarted: ((AppViewModel) -> Void)?
    public var onStartRequested: (() -> Void)?

    public init() {}

    public func start() {
        #if os(macOS)
        // The process first arbitrates ownership. Views may request startup, but
        // must never construct a second core while that asynchronous step runs.
        onStartRequested?()
        #else
        if model == nil, let errorKey = KeyguardStorage.shared.prepare() {
            fail(messageKey: errorKey)
            return
        }
        startOwnedInstance()
        #endif
    }

    public func fail(
        messageKey: String, titleKey: String = AppStartup.storageErrorTitleKey, details: String? = nil
    ) {
        errorKey = messageKey
        errorTitleKey = titleKey
        errorDetails = details
    }

    /// The caller has prepared storage; on macOS that happens before arbitrating ownership.
    /// `showsMainWindow` tells macOS whether this launch opens its main window; iOS ignores it.
    public func startOwnedInstance(showsMainWindow: Bool = false) {
        errorKey = nil
        errorDetails = nil
        errorTitleKey = Self.storageErrorTitleKey
        guard model == nil else { return }
        let model = AppViewModel.create(showsMainWindow: showsMainWindow)
        self.model = model
        onStarted?(model)
    }
}
