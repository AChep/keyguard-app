import XCTest
@testable import KeyguardUI

#if os(macOS)
import Vision
#endif

/// Expected module patterns come from the Code 39 and Code 93 symbology tables.
final class OneDimensionalBarcodeEncoderTests: XCTestCase {
    private func pattern(_ modules: [Bool]?) -> String? {
        modules.map { String($0.map { $0 ? "1" : "0" }) }
    }

    // MARK: - Code 39

    private let code39Asterisk = "100101101101"

    func testCode39EncodesStartCharacterStopWithNarrowGaps() {
        let a = "110101001011"
        XCTAssertEqual(
            pattern(OneDimensionalBarcodeEncoder.code39("A")),
            code39Asterisk + "0" + a + "0" + code39Asterisk
        )
    }

    func testCode39EncodesDigitsWithoutCheckCharacter() {
        let zero = "101001101101"
        let one = "110100101011"
        XCTAssertEqual(
            pattern(OneDimensionalBarcodeEncoder.code39("10")),
            code39Asterisk + "0" + one + "0" + zero + "0" + code39Asterisk
        )
    }

    func testCode39UsesFullAsciiForLowercase() {
        // `a` is (+)A in full ASCII mode, as ZXing's writer encodes it.
        XCTAssertEqual(OneDimensionalBarcodeEncoder.code39("a"), OneDimensionalBarcodeEncoder.code39("+A"))
        // Once one character needs full ASCII, base characters like `$` are shifted too.
        XCTAssertEqual(OneDimensionalBarcodeEncoder.code39("a$"), OneDimensionalBarcodeEncoder.code39("+A/D"))
    }

    func testCode39RejectsUnencodableContents() {
        XCTAssertNil(OneDimensionalBarcodeEncoder.code39("é"))
        XCTAssertNil(OneDimensionalBarcodeEncoder.code39(String(repeating: "A", count: 81)))
        XCTAssertNil(OneDimensionalBarcodeEncoder.code39(String(repeating: "a", count: 41)))
        XCTAssertNotNil(OneDimensionalBarcodeEncoder.code39(String(repeating: "a", count: 40)))
    }

    // MARK: - Code 93

    func testCode93EncodesSpecificationExample() {
        // TEST93 with check characters C = `+` and K = `6`.
        let expected = [
            "101011110",  // start
            "110100110",  // T
            "110010010",  // E
            "110101100",  // S
            "110100110",  // T
            "100001010",  // 9
            "101000010",  // 3
            "101110110",  // +
            "100100010",  // 6
            "101011110",  // stop
            "1",  // termination bar
        ].joined()
        XCTAssertEqual(pattern(OneDimensionalBarcodeEncoder.code93("TEST93")), expected)
    }

    func testCode93ChecksumWeightsWrap() {
        // Weights restart after 20 (C) and 15 (K) characters.
        let indices = Array(repeating: 1, count: 21)
        XCTAssertEqual(OneDimensionalBarcodeEncoder.code93Checksum(indices, maxWeight: 20), (210 + 1) % 47)
        XCTAssertEqual(OneDimensionalBarcodeEncoder.code93Checksum(indices, maxWeight: 15), (120 + 21) % 47)
    }

    func testCode93UsesShiftCharactersForFullAscii() throws {
        let lower = try XCTUnwrap(OneDimensionalBarcodeEncoder.code93("a"))
        // Start, (+), A, C, K, stop, termination bar.
        XCTAssertEqual(lower.count, 6 * 9 + 1)
        XCTAssertEqual(pattern(Array(lower[9..<18])), "100110010")  // (+)
        XCTAssertEqual(pattern(Array(lower[18..<27])), "110101000")  // A
    }

    func testCode93RejectsUnencodableContents() {
        XCTAssertNil(OneDimensionalBarcodeEncoder.code93("é"))
        XCTAssertNil(OneDimensionalBarcodeEncoder.code93(String(repeating: "A", count: 81)))
        XCTAssertNotNil(OneDimensionalBarcodeEncoder.code93(String(repeating: "A", count: 80)))
    }

    // MARK: - Rendering

    func testRendererDrawsCode39AndCode93() throws {
        for format in ["CODE_39", "CODE_93"] {
            let image = try XCTUnwrap(BarcodeRenderer.image(data: "KEYGUARD-1", format: format))
            XCTAssertGreaterThan(image.height, 1, format)
        }
        XCTAssertNil(BarcodeRenderer.image(data: "é", format: "CODE_39"))
        XCTAssertNil(BarcodeRenderer.image(data: "é", format: "CODE_93"))
    }

    #if os(macOS)
    func testRenderedBarcodesDecode() throws {
        let cases: [(format: String, symbology: VNBarcodeSymbology)] = [
            ("CODE_39", .code39),
            ("CODE_93", .code93),
        ]
        for testCase in cases {
            let image = try XCTUnwrap(
                BarcodeRenderer.image(data: "KEYGUARD-1", format: testCase.format, displayScale: 3))
            let request = VNDetectBarcodesRequest()
            request.symbologies = [testCase.symbology]
            try VNImageRequestHandler(cgImage: image).perform([request])
            let payloads = (request.results ?? []).compactMap(\.payloadStringValue)
            XCTAssertTrue(payloads.contains("KEYGUARD-1"), "\(testCase.format): \(payloads)")
        }
    }
    #endif
}
