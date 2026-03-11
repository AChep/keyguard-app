package com.artemchep.autotype

public enum class GlobalHotKeyKey {
    A,
    B,
    C,
    D,
    E,
    F,
    G,
    H,
    I,
    J,
    K,
    L,
    M,
    N,
    O,
    P,
    Q,
    R,
    S,
    T,
    U,
    V,
    W,
    X,
    Y,
    Z,
    Digit0,
    Digit1,
    Digit2,
    Digit3,
    Digit4,
    Digit5,
    Digit6,
    Digit7,
    Digit8,
    Digit9,
    Space,
    Enter,
    Tab,
    Escape,
    Backspace,
    Delete,
    Insert,
    Home,
    End,
    PageUp,
    PageDown,
    ArrowLeft,
    ArrowRight,
    ArrowUp,
    ArrowDown,
    F1,
    F2,
    F3,
    F4,
    F5,
    F6,
    F7,
    F8,
    F9,
    F10,
    F11,
    F12,
}

public data class GlobalHotKeySpec(
    public val key: GlobalHotKeyKey,
    public val isCtrlPressed: Boolean = false,
    public val isShiftPressed: Boolean = false,
    public val isAltPressed: Boolean = false,
    public val isMetaPressed: Boolean = false,
)

internal val GlobalHotKeyKey.macosKeyCode: Int?
    get() = when (this) {
        GlobalHotKeyKey.A -> 0x00
        GlobalHotKeyKey.B -> 0x0B
        GlobalHotKeyKey.C -> 0x08
        GlobalHotKeyKey.D -> 0x02
        GlobalHotKeyKey.E -> 0x0E
        GlobalHotKeyKey.F -> 0x03
        GlobalHotKeyKey.G -> 0x05
        GlobalHotKeyKey.H -> 0x04
        GlobalHotKeyKey.I -> 0x22
        GlobalHotKeyKey.J -> 0x26
        GlobalHotKeyKey.K -> 0x28
        GlobalHotKeyKey.L -> 0x25
        GlobalHotKeyKey.M -> 0x2E
        GlobalHotKeyKey.N -> 0x2D
        GlobalHotKeyKey.O -> 0x1F
        GlobalHotKeyKey.P -> 0x23
        GlobalHotKeyKey.Q -> 0x0C
        GlobalHotKeyKey.R -> 0x0F
        GlobalHotKeyKey.S -> 0x01
        GlobalHotKeyKey.T -> 0x11
        GlobalHotKeyKey.U -> 0x20
        GlobalHotKeyKey.V -> 0x09
        GlobalHotKeyKey.W -> 0x0D
        GlobalHotKeyKey.X -> 0x07
        GlobalHotKeyKey.Y -> 0x10
        GlobalHotKeyKey.Z -> 0x06
        GlobalHotKeyKey.Digit0 -> 0x1D
        GlobalHotKeyKey.Digit1 -> 0x12
        GlobalHotKeyKey.Digit2 -> 0x13
        GlobalHotKeyKey.Digit3 -> 0x14
        GlobalHotKeyKey.Digit4 -> 0x15
        GlobalHotKeyKey.Digit5 -> 0x17
        GlobalHotKeyKey.Digit6 -> 0x16
        GlobalHotKeyKey.Digit7 -> 0x1A
        GlobalHotKeyKey.Digit8 -> 0x1C
        GlobalHotKeyKey.Digit9 -> 0x19
        GlobalHotKeyKey.Space -> 0x31
        GlobalHotKeyKey.Enter -> 0x24
        GlobalHotKeyKey.Tab -> 0x30
        GlobalHotKeyKey.Escape -> 0x35
        GlobalHotKeyKey.Backspace -> 0x33
        GlobalHotKeyKey.Delete -> 0x75
        GlobalHotKeyKey.Insert -> null
        GlobalHotKeyKey.Home -> 0x73
        GlobalHotKeyKey.End -> 0x77
        GlobalHotKeyKey.PageUp -> 0x74
        GlobalHotKeyKey.PageDown -> 0x79
        GlobalHotKeyKey.ArrowLeft -> 0x7B
        GlobalHotKeyKey.ArrowRight -> 0x7C
        GlobalHotKeyKey.ArrowUp -> 0x7E
        GlobalHotKeyKey.ArrowDown -> 0x7D
        GlobalHotKeyKey.F1 -> 0x7A
        GlobalHotKeyKey.F2 -> 0x78
        GlobalHotKeyKey.F3 -> 0x63
        GlobalHotKeyKey.F4 -> 0x76
        GlobalHotKeyKey.F5 -> 0x60
        GlobalHotKeyKey.F6 -> 0x61
        GlobalHotKeyKey.F7 -> 0x62
        GlobalHotKeyKey.F8 -> 0x64
        GlobalHotKeyKey.F9 -> 0x65
        GlobalHotKeyKey.F10 -> 0x6D
        GlobalHotKeyKey.F11 -> 0x67
        GlobalHotKeyKey.F12 -> 0x6F
    }

internal val GlobalHotKeyKey.windowsKeyCode: Int
    get() = when (this) {
        GlobalHotKeyKey.A -> 0x41
        GlobalHotKeyKey.B -> 0x42
        GlobalHotKeyKey.C -> 0x43
        GlobalHotKeyKey.D -> 0x44
        GlobalHotKeyKey.E -> 0x45
        GlobalHotKeyKey.F -> 0x46
        GlobalHotKeyKey.G -> 0x47
        GlobalHotKeyKey.H -> 0x48
        GlobalHotKeyKey.I -> 0x49
        GlobalHotKeyKey.J -> 0x4A
        GlobalHotKeyKey.K -> 0x4B
        GlobalHotKeyKey.L -> 0x4C
        GlobalHotKeyKey.M -> 0x4D
        GlobalHotKeyKey.N -> 0x4E
        GlobalHotKeyKey.O -> 0x4F
        GlobalHotKeyKey.P -> 0x50
        GlobalHotKeyKey.Q -> 0x51
        GlobalHotKeyKey.R -> 0x52
        GlobalHotKeyKey.S -> 0x53
        GlobalHotKeyKey.T -> 0x54
        GlobalHotKeyKey.U -> 0x55
        GlobalHotKeyKey.V -> 0x56
        GlobalHotKeyKey.W -> 0x57
        GlobalHotKeyKey.X -> 0x58
        GlobalHotKeyKey.Y -> 0x59
        GlobalHotKeyKey.Z -> 0x5A
        GlobalHotKeyKey.Digit0 -> 0x30
        GlobalHotKeyKey.Digit1 -> 0x31
        GlobalHotKeyKey.Digit2 -> 0x32
        GlobalHotKeyKey.Digit3 -> 0x33
        GlobalHotKeyKey.Digit4 -> 0x34
        GlobalHotKeyKey.Digit5 -> 0x35
        GlobalHotKeyKey.Digit6 -> 0x36
        GlobalHotKeyKey.Digit7 -> 0x37
        GlobalHotKeyKey.Digit8 -> 0x38
        GlobalHotKeyKey.Digit9 -> 0x39
        GlobalHotKeyKey.Space -> 0x20
        GlobalHotKeyKey.Enter -> 0x0D
        GlobalHotKeyKey.Tab -> 0x09
        GlobalHotKeyKey.Escape -> 0x1B
        GlobalHotKeyKey.Backspace -> 0x08
        GlobalHotKeyKey.Delete -> 0x2E
        GlobalHotKeyKey.Insert -> 0x2D
        GlobalHotKeyKey.Home -> 0x24
        GlobalHotKeyKey.End -> 0x23
        GlobalHotKeyKey.PageUp -> 0x21
        GlobalHotKeyKey.PageDown -> 0x22
        GlobalHotKeyKey.ArrowLeft -> 0x25
        GlobalHotKeyKey.ArrowRight -> 0x27
        GlobalHotKeyKey.ArrowUp -> 0x26
        GlobalHotKeyKey.ArrowDown -> 0x28
        GlobalHotKeyKey.F1 -> 0x70
        GlobalHotKeyKey.F2 -> 0x71
        GlobalHotKeyKey.F3 -> 0x72
        GlobalHotKeyKey.F4 -> 0x73
        GlobalHotKeyKey.F5 -> 0x74
        GlobalHotKeyKey.F6 -> 0x75
        GlobalHotKeyKey.F7 -> 0x76
        GlobalHotKeyKey.F8 -> 0x77
        GlobalHotKeyKey.F9 -> 0x78
        GlobalHotKeyKey.F10 -> 0x79
        GlobalHotKeyKey.F11 -> 0x7A
        GlobalHotKeyKey.F12 -> 0x7B
    }

internal val GlobalHotKeyKey.x11KeySym: Int
    get() = when (this) {
        GlobalHotKeyKey.A -> 0x0061
        GlobalHotKeyKey.B -> 0x0062
        GlobalHotKeyKey.C -> 0x0063
        GlobalHotKeyKey.D -> 0x0064
        GlobalHotKeyKey.E -> 0x0065
        GlobalHotKeyKey.F -> 0x0066
        GlobalHotKeyKey.G -> 0x0067
        GlobalHotKeyKey.H -> 0x0068
        GlobalHotKeyKey.I -> 0x0069
        GlobalHotKeyKey.J -> 0x006A
        GlobalHotKeyKey.K -> 0x006B
        GlobalHotKeyKey.L -> 0x006C
        GlobalHotKeyKey.M -> 0x006D
        GlobalHotKeyKey.N -> 0x006E
        GlobalHotKeyKey.O -> 0x006F
        GlobalHotKeyKey.P -> 0x0070
        GlobalHotKeyKey.Q -> 0x0071
        GlobalHotKeyKey.R -> 0x0072
        GlobalHotKeyKey.S -> 0x0073
        GlobalHotKeyKey.T -> 0x0074
        GlobalHotKeyKey.U -> 0x0075
        GlobalHotKeyKey.V -> 0x0076
        GlobalHotKeyKey.W -> 0x0077
        GlobalHotKeyKey.X -> 0x0078
        GlobalHotKeyKey.Y -> 0x0079
        GlobalHotKeyKey.Z -> 0x007A
        GlobalHotKeyKey.Digit0 -> 0x0030
        GlobalHotKeyKey.Digit1 -> 0x0031
        GlobalHotKeyKey.Digit2 -> 0x0032
        GlobalHotKeyKey.Digit3 -> 0x0033
        GlobalHotKeyKey.Digit4 -> 0x0034
        GlobalHotKeyKey.Digit5 -> 0x0035
        GlobalHotKeyKey.Digit6 -> 0x0036
        GlobalHotKeyKey.Digit7 -> 0x0037
        GlobalHotKeyKey.Digit8 -> 0x0038
        GlobalHotKeyKey.Digit9 -> 0x0039
        GlobalHotKeyKey.Space -> 0x0020
        GlobalHotKeyKey.Enter -> 0xFF0D
        GlobalHotKeyKey.Tab -> 0xFF09
        GlobalHotKeyKey.Escape -> 0xFF1B
        GlobalHotKeyKey.Backspace -> 0xFF08
        GlobalHotKeyKey.Delete -> 0xFFFF
        GlobalHotKeyKey.Insert -> 0xFF63
        GlobalHotKeyKey.Home -> 0xFF50
        GlobalHotKeyKey.End -> 0xFF57
        GlobalHotKeyKey.PageUp -> 0xFF55
        GlobalHotKeyKey.PageDown -> 0xFF56
        GlobalHotKeyKey.ArrowLeft -> 0xFF51
        GlobalHotKeyKey.ArrowRight -> 0xFF53
        GlobalHotKeyKey.ArrowUp -> 0xFF52
        GlobalHotKeyKey.ArrowDown -> 0xFF54
        GlobalHotKeyKey.F1 -> 0xFFBE
        GlobalHotKeyKey.F2 -> 0xFFBF
        GlobalHotKeyKey.F3 -> 0xFFC0
        GlobalHotKeyKey.F4 -> 0xFFC1
        GlobalHotKeyKey.F5 -> 0xFFC2
        GlobalHotKeyKey.F6 -> 0xFFC3
        GlobalHotKeyKey.F7 -> 0xFFC4
        GlobalHotKeyKey.F8 -> 0xFFC5
        GlobalHotKeyKey.F9 -> 0xFFC6
        GlobalHotKeyKey.F10 -> 0xFFC7
        GlobalHotKeyKey.F11 -> 0xFFC8
        GlobalHotKeyKey.F12 -> 0xFFC9
    }
