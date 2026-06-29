package app.keemobile.kotpass.constants

/**
 * Specifies the obfuscation method for Auto-Type to protect against keyloggers.
 */
enum class AutoTypeObfuscation {
    /**
     * Sends characters as individual keystrokes.
     * This is less secure against keyloggers.
     */
    None,

    /**
     * Pastes the password via the system clipboard to bypass keyloggers.
     * Also known as Two-Channel Auto-Type Obfuscation (TCATO).
     */
    UseClipboard
}
