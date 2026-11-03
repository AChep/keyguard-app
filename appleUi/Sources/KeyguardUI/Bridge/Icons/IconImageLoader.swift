import CoreGraphics
import Foundation
import ImageIO
import SwiftUI

/// A decoded icon. Immutable, so it can leave the decoding task.
final class IconImage: @unchecked Sendable {
    let cgImage: CGImage
    let cost: Int

    init(_ cgImage: CGImage) {
        self.cgImage = cgImage
        self.cost = cgImage.bytesPerRow * cgImage.height
    }
}

/// Loads the icons that snapshots reference by `iconKey`. The shared core resolves
/// and downloads them, so an account's icon server gets the account's client
/// certificate and the icon settings apply.
@MainActor
final class IconImageLoader {
    typealias Load = @Sendable (String) async throws -> Data?

    private let load: Load
    private let failureTtl: TimeInterval
    private let now: () -> Date
    private let maxPixelSize: Int
    private let images = NSCache<NSString, IconImage>()
    private var failures: [String: Date] = [:]
    private var inFlight: [String: Task<IconImage?, Never>] = [:]

    init(
        load: @escaping Load,
        failureTtl: TimeInterval = 10 * 60,
        maxPixelSize: Int = 192,
        now: @escaping () -> Date = Date.init
    ) {
        self.load = load
        self.failureTtl = failureTtl
        self.maxPixelSize = maxPixelSize
        self.now = now
        images.totalCostLimit = 32 * 1024 * 1024
    }

    /// The icon if it is already loaded, so a row can show it in its first frame.
    func cachedImage(for key: String) -> IconImage? {
        images.object(forKey: key as NSString)
    }

    /// Returns `nil` if the icon is disabled, failed recently, or can't be decoded.
    func image(for key: String) async -> IconImage? {
        if let cached = cachedImage(for: key) {
            return cached
        }
        if let failedAt = failures[key], now().timeIntervalSince(failedAt) < failureTtl {
            return nil
        }
        // Rows that share a website share the load. The task is not
        // cancelled when a row disappears, so its result is not lost.
        let task = inFlight[key] ?? startLoading(key)
        return await task.value
    }

    private func startLoading(_ key: String) -> Task<IconImage?, Never> {
        let load = self.load
        let maxPixelSize = self.maxPixelSize
        let task = Task<IconImage?, Never> { [weak self] in
            let outcome: Outcome
            do {
                if let data = try await load(key) {
                    if let image = await Self.decode(data, maxPixelSize: maxPixelSize) {
                        outcome = .image(image)
                    } else {
                        outcome = .failure
                    }
                } else {
                    // Nothing to load; ask again next time, the settings may change.
                    outcome = .nothing
                }
            } catch {
                outcome = .failure
            }
            self?.finish(key, outcome)
            if case .image(let image) = outcome {
                return image
            }
            return nil
        }
        inFlight[key] = task
        return task
    }

    private func finish(_ key: String, _ outcome: Outcome) {
        inFlight[key] = nil
        switch outcome {
        case .image(let image):
            failures[key] = nil
            images.setObject(image, forKey: key as NSString, cost: image.cost)
        case .failure:
            // Drop the expired failures, so the map does not grow for the whole session.
            let failedAt = now()
            failures = failures.filter { failedAt.timeIntervalSince($0.value) < failureTtl }
            failures[key] = failedAt
        case .nothing:
            break
        }
    }

    private enum Outcome {
        case image(IconImage)
        case failure
        case nothing
    }

    /// Decodes off the main actor. A `.ico` holds several sizes; the largest one is used.
    nonisolated private static func decode(_ data: Data, maxPixelSize: Int) async -> IconImage? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        let count = CGImageSourceGetCount(source)
        guard count > 0 else { return nil }
        let largest = (0..<count).max { lhs, rhs in
            pixelWidth(source, at: lhs) < pixelWidth(source, at: rhs)
        }
        let index = largest ?? 0
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixelSize,
        ]
        guard let image = CGImageSourceCreateThumbnailAtIndex(source, index, options as CFDictionary) else {
            return nil
        }
        return IconImage(image)
    }

    nonisolated private static func pixelWidth(_ source: CGImageSource, at index: Int) -> Int {
        let properties = CGImageSourceCopyPropertiesAtIndex(source, index, nil) as? [CFString: Any]
        return properties?[kCGImagePropertyPixelWidth] as? Int ?? 0
    }
}

private struct IconImageLoaderKey: EnvironmentKey {
    static let defaultValue: IconImageLoader? = nil
}

extension EnvironmentValues {
    /// Loads the icons of snapshots; `nil` (previews) shows the placeholders.
    var iconImageLoader: IconImageLoader? {
        get { self[IconImageLoaderKey.self] }
        set { self[IconImageLoaderKey.self] = newValue }
    }
}
