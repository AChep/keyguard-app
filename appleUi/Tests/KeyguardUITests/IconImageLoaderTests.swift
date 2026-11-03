import CoreGraphics
import ImageIO
import UniformTypeIdentifiers
import XCTest
@testable import KeyguardUI

@MainActor
final class IconImageLoaderTests: XCTestCase {
    func testLoadsAndCachesAnIcon() async {
        let fixture = Fixture()

        let first = await fixture.loader.image(for: "a")
        let second = await fixture.loader.image(for: "a")

        XCTAssertNotNil(first)
        XCTAssertTrue(first === second)
        XCTAssertTrue(fixture.loader.cachedImage(for: "a") === first)
        XCTAssertEqual(fixture.calls.keys, ["a"])
    }

    func testConcurrentLoadsOfOneIconShareTheDownload() async {
        let fixture = Fixture()
        fixture.gate = AsyncGate()

        async let first = fixture.loader.image(for: "a")
        async let second = fixture.loader.image(for: "a")
        await Task.yield()
        await fixture.gate?.open()
        let images = await [first, second]

        XCTAssertNotNil(images[0])
        XCTAssertTrue(images[0] === images[1])
        XCTAssertEqual(fixture.calls.keys, ["a"])
    }

    func testNothingToLoadIsAskedAgain() async {
        let fixture = Fixture()
        fixture.result = .nothing

        let first = await fixture.loader.image(for: "a")
        let second = await fixture.loader.image(for: "a")

        XCTAssertNil(first)
        XCTAssertNil(second)
        XCTAssertEqual(fixture.calls.keys, ["a", "a"])
    }

    func testFailuresAreRememberedForAWhile() async {
        let fixture = Fixture()
        fixture.result = .failure

        _ = await fixture.loader.image(for: "a")
        _ = await fixture.loader.image(for: "a")
        XCTAssertEqual(fixture.calls.keys, ["a"])

        fixture.result = .image
        fixture.time += 11 * 60
        let image = await fixture.loader.image(for: "a")

        XCTAssertNotNil(image)
        XCTAssertEqual(fixture.calls.keys, ["a", "a"])
    }

    func testUndecodableBytesCountAsAFailure() async {
        let fixture = Fixture()
        fixture.result = .garbage

        let first = await fixture.loader.image(for: "a")
        let second = await fixture.loader.image(for: "a")

        XCTAssertNil(first)
        XCTAssertNil(second)
        XCTAssertEqual(fixture.calls.keys, ["a"])
    }

    func testLargeIconsAreScaledDown() async throws {
        let fixture = Fixture()
        fixture.size = 1024

        let image = await fixture.loader.image(for: "a")

        let cgImage = try XCTUnwrap(image?.cgImage)
        XCTAssertEqual(max(cgImage.width, cgImage.height), 192)
    }
}

@MainActor
private final class Fixture {
    enum Result { case image, nothing, failure, garbage }

    var result = Result.image
    var size = 16
    var time = Date(timeIntervalSince1970: 1_700_000_000)
    var gate: AsyncGate?
    let calls = Calls()

    lazy var loader = IconImageLoader(
        load: { [weak self] key in
            guard let self else { return nil }
            return try await self.respond(key)
        },
        now: { [weak self] in self?.time ?? Date() }
    )

    private func respond(_ key: String) async throws -> Data? {
        calls.keys.append(key)
        await gate?.wait()
        switch result {
        case .image: return png(size: size)
        case .nothing: return nil
        case .failure: throw URLError(.notConnectedToInternet)
        case .garbage: return Data([1, 2, 3])
        }
    }
}

@MainActor
private final class Calls {
    var keys: [String] = []
}

private actor AsyncGate {
    private var isOpen = false
    private var waiters: [CheckedContinuation<Void, Never>] = []

    func wait() async {
        if isOpen { return }
        await withCheckedContinuation { waiters.append($0) }
    }

    func open() {
        isOpen = true
        waiters.forEach { $0.resume() }
        waiters.removeAll()
    }
}

private func png(size: Int) -> Data {
    let context = CGContext(
        data: nil, width: size, height: size, bitsPerComponent: 8, bytesPerRow: 0,
        space: CGColorSpaceCreateDeviceRGB(),
        bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
    )!
    context.setFillColor(CGColor(red: 1, green: 0, blue: 0, alpha: 1))
    context.fill(CGRect(x: 0, y: 0, width: size, height: size))
    let image = context.makeImage()!
    let data = NSMutableData()
    let destination = CGImageDestinationCreateWithData(data, UTType.png.identifier as CFString, 1, nil)!
    CGImageDestinationAddImage(destination, image, nil)
    CGImageDestinationFinalize(destination)
    return data as Data
}
