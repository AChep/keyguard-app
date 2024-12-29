package autotype

internal object AutoTypeUtil {
    /**
     * Returns `true` if the `char` is a keyboard key that
     * would require the shift key to be held down, such as
     * uppercase letters or the symbols on the keyboard's number row.
     */
    fun requiresShiftPress(char: Char): Boolean {
        return char.isUpperCase() || char in "~!@#\$%^&*()_+{}|:\"<>?"
    }
}
