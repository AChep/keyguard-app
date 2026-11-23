import KeyguardShared

/// Held instance ownership; it stays alive until process exit.
@MainActor
protocol AppProcessInstance: AnyObject {}

extension MacosInstance: AppProcessInstance {}
