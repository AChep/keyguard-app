/// The app shell's current vault state, independent of its composition root.
public enum VaultStatus {
    case loading
    case needsCreate
    case locked
    case unlocked
}
