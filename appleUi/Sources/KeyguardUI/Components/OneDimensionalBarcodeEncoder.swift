import Foundation

/// Code 39 and Code 93 encoders, which Core Image does not provide. They match
/// ZXing's `Code39Writer` and `Code93Writer`, used on Android and desktop:
/// full ASCII extended mode for characters outside the base alphabet, no
/// Code 39 check digit, two Code 93 check characters, and at most 80 encoded
/// characters. Each element is one module, `true` for a bar.
enum OneDimensionalBarcodeEncoder {
    private static let maxLength = 80

    // MARK: - Code 39

    private static let code39Alphabet = Array("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ-. $/+%".unicodeScalars)

    /// Nine elements each, starting with a bar; a set bit is a wide element.
    private static let code39Encodings: [Int] = [
        0x034, 0x121, 0x061, 0x160, 0x031, 0x130, 0x070, 0x025, 0x124, 0x064,  // 0-9
        0x109, 0x049, 0x148, 0x019, 0x118, 0x058, 0x00D, 0x10C, 0x04C, 0x01C,  // A-J
        0x103, 0x043, 0x142, 0x013, 0x112, 0x052, 0x007, 0x106, 0x046, 0x016,  // K-T
        0x181, 0x0C1, 0x1C0, 0x091, 0x190, 0x0D0, 0x085, 0x184, 0x0C4, 0x0A8,  // U-$
        0x0A2, 0x08A, 0x02A,  // /-%
    ]
    private static let code39Asterisk = 0x094

    static func code39(_ contents: String) -> [Bool]? {
        var scalars = Array(contents.unicodeScalars)
        guard scalars.count <= maxLength else { return nil }
        if scalars.contains(where: { !code39Alphabet.contains($0) }) {
            guard let extended = code39Extended(scalars), extended.count <= maxLength else { return nil }
            scalars = extended
        }
        var modules: [Bool] = []
        modules.reserveCapacity(24 + 1 + 13 * scalars.count)
        appendCode39(code39Asterisk, to: &modules)
        for scalar in scalars {
            guard let index = code39Alphabet.firstIndex(of: scalar) else { return nil }
            modules.append(false)
            appendCode39(code39Encodings[index], to: &modules)
        }
        modules.append(false)
        appendCode39(code39Asterisk, to: &modules)
        return modules
    }

    private static func appendCode39(_ encoding: Int, to modules: inout [Bool]) {
        for element in 0..<9 {
            let wide = encoding & (1 << (8 - element)) != 0
            let bar = element.isMultiple(of: 2)
            modules.append(contentsOf: repeatElement(bar, count: wide ? 2 : 1))
        }
    }

    private static func code39Extended(_ scalars: [Unicode.Scalar]) -> [Unicode.Scalar]? {
        var result: [Unicode.Scalar] = []
        for scalar in scalars {
            let value = scalar.value
            switch value {
            case 0: result += ["%", "U"]
            case 0x20, 0x2D, 0x2E: result.append(scalar)  // space - .
            case 0x40: result += ["%", "V"]  // @
            case 0x60: result += ["%", "W"]  // `
            case 1...26: result += ["$", offset("A", value - 1)]
            case 27...31: result += ["%", offset("A", value - 27)]
            case 0x21...0x2C, 0x2F, 0x3A: result += ["/", offset("A", value - 33)]  // ! - , / :
            case 0x30...0x39: result.append(scalar)  // 0-9
            case 0x3B...0x3F: result += ["%", offset("F", value - 59)]  // ; - ?
            case 0x41...0x5A: result.append(scalar)  // A-Z
            case 0x5B...0x5F: result += ["%", offset("K", value - 91)]  // [ - _
            case 0x61...0x7A: result += ["+", offset("A", value - 97)]  // a-z
            case 0x7B...0x7F: result += ["%", offset("P", value - 123)]  // { - DEL
            default: return nil
            }
        }
        return result
    }

    // MARK: - Code 93

    /// `a`-`d` stand for the ($), (%), (/) and (+) shift characters.
    private static let code93Alphabet = Array("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ-. $/+%abcd*".unicodeScalars)

    /// Nine modules each, most significant bit first; a set bit is a bar.
    private static let code93Encodings: [Int] = [
        0x114, 0x148, 0x144, 0x142, 0x128, 0x124, 0x122, 0x150, 0x112, 0x10A,  // 0-9
        0x1A8, 0x1A4, 0x1A2, 0x194, 0x192, 0x18A, 0x168, 0x164, 0x162, 0x134,  // A-J
        0x11A, 0x158, 0x14C, 0x146, 0x12C, 0x116, 0x1B4, 0x1B2, 0x1AC, 0x1A6,  // K-T
        0x196, 0x19A, 0x16C, 0x166, 0x136, 0x13A,  // U-Z
        0x12E, 0x1D4, 0x1D2, 0x1CA, 0x16E, 0x176, 0x1AE,  // - . space $ / + %
        0x126, 0x1DA, 0x1D6, 0x132, 0x15E,  // ($) (%) (/) (+) *
    ]
    private static let code93Asterisk = 0x15E

    static func code93(_ contents: String) -> [Bool]? {
        guard let extended = code93Extended(Array(contents.unicodeScalars)), extended.count <= maxLength else {
            return nil
        }
        var indices: [Int] = []
        for scalar in extended {
            guard let index = code93Alphabet.firstIndex(of: scalar) else { return nil }
            indices.append(index)
        }
        indices.append(code93Checksum(indices, maxWeight: 20))
        indices.append(code93Checksum(indices, maxWeight: 15))

        var modules: [Bool] = []
        modules.reserveCapacity((indices.count + 2) * 9 + 1)
        appendCode93(code93Asterisk, to: &modules)
        for index in indices {
            appendCode93(code93Encodings[index], to: &modules)
        }
        appendCode93(code93Asterisk, to: &modules)
        // Termination bar.
        modules.append(true)
        return modules
    }

    private static func appendCode93(_ encoding: Int, to modules: inout [Bool]) {
        for module in 0..<9 {
            modules.append(encoding & (1 << (8 - module)) != 0)
        }
    }

    /// Weights run 1...`maxWeight` from the rightmost character, then repeat.
    static func code93Checksum(_ indices: [Int], maxWeight: Int) -> Int {
        var total = 0
        for (position, index) in indices.reversed().enumerated() {
            total += index * (position % maxWeight + 1)
        }
        return total % 47
    }

    private static func code93Extended(_ scalars: [Unicode.Scalar]) -> [Unicode.Scalar]? {
        var result: [Unicode.Scalar] = []
        for scalar in scalars {
            let value = scalar.value
            switch value {
            case 0: result += ["b", "U"]
            case 1...26: result += ["a", offset("A", value - 1)]
            case 27...31: result += ["b", offset("A", value - 27)]
            case 0x20, 0x24, 0x25, 0x2B: result.append(scalar)  // space $ % +
            case 0x21...0x2C: result += ["c", offset("A", value - 0x21)]  // ! - ,
            case 0x2D...0x39: result.append(scalar)  // - . / 0-9
            case 0x3A: result += ["c", "Z"]  // :
            case 0x3B...0x3F: result += ["b", offset("F", value - 0x3B)]  // ; - ?
            case 0x40: result += ["b", "V"]  // @
            case 0x41...0x5A: result.append(scalar)  // A-Z
            case 0x5B...0x5F: result += ["b", offset("K", value - 0x5B)]  // [ - _
            case 0x60: result += ["b", "W"]  // `
            case 0x61...0x7A: result += ["d", offset("A", value - 0x61)]  // a-z
            case 0x7B...0x7F: result += ["b", offset("P", value - 0x7B)]  // { - DEL
            default: return nil
            }
        }
        return result
    }

    // MARK: -

    private static func offset(_ base: Unicode.Scalar, _ delta: UInt32) -> Unicode.Scalar {
        // Every caller stays within A-Z.
        Unicode.Scalar(base.value + delta) ?? base
    }
}
