/// Conditions for a visible surface to prevent automatic screen sleep.
struct ScreenAwakeRequest: Equatable {
    var isVisible: Bool
    var isSceneActive: Bool
    var isUnlocked: Bool
    var isEnabled: Bool

    var isActive: Bool { isVisible && isSceneActive && isUnlocked && isEnabled }
}
