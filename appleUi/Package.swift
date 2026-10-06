// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "KeyguardUI",
    // The development region for the bundled `Localizable.xcstrings` String
    // Catalog (Resources/). Required for SPM to localize `Bundle.module`.
    defaultLocalization: "en",
    // Synchronized from gradle/libs.versions.toml by generateAppleConfiguration.
    platforms: [
        .macOS("14.0"),
        .iOS("18.5"),
    ],
    products: [
        .library(name: "KeyguardUI", targets: ["KeyguardUI"]),
        // The Kotlin bridge on its own, without the SwiftUI screen layer. Lets the
        // AutoFill extensions link the exact artifact the apps link, instead of
        // reaching into `:appleApp`'s build directory by hand.
        .library(name: "KeyguardShared", targets: ["KeyguardShared"]),
    ],
    dependencies: [
        // Block-level Markdown rendering for note bodies (used by MarkdownTextView).
        .package(url: "https://github.com/gonzalezreal/swift-markdown-ui", from: "2.4.1")
    ],
    targets: [
        .binaryTarget(
            name: "KeyguardShared",
            path: "../appleApp/build/XCFrameworks/swiftpm/KeyguardShared.xcframework"
        ),
        .target(
            name: "KeyguardUI",
            dependencies: [
                "KeyguardShared",
                .product(name: "MarkdownUI", package: "swift-markdown-ui"),
            ],
            resources: [
                // Native String Catalog generated from the shared Compose
                // `strings.xml`/`plurals.xml` by the `:common:generateAppleStrings`
                // Gradle task. Read from `Bundle.module` via the generated `L10n`.
                .process("Resources/Localizable.xcstrings")
            ],
            swiftSettings: [
                // Complete actor-isolation checking. Warnings under the Swift 5
                // language mode, errors under Swift 6; keeps the main-thread
                // contract of the Kotlin bridge under static analysis.
                .enableUpcomingFeature("StrictConcurrency")
            ],
            linkerSettings: [
                // The shared Rust YubiKey backend uses macOS HID feature reports.
                .linkedFramework("IOKit", .when(platforms: [.macOS]))
            ]
        ),
        .testTarget(
            name: "KeyguardUITests",
            dependencies: ["KeyguardUI"],
            // The static Kotlin framework uses SQLite; app targets already link it.
            linkerSettings: [.linkedLibrary("sqlite3")]
        ),
    ]
)
