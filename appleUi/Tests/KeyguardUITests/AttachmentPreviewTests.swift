import Foundation
import XCTest
@testable import KeyguardUI
#if os(iOS)
import UIKit
#endif

final class AttachmentPreviewTests: XCTestCase {
    func testImageTaskIdentitySurvivesNewNSDataInstances() {
        let bytes = [UInt8](repeating: 42, count: 32)
        let first = NSData(bytes: bytes, length: bytes.count)
        let second = NSData(bytes: bytes, length: bytes.count)
        XCTAssertFalse(first === second)
        XCTAssertEqual(
            AttachmentImageDecodeKey(data: first, decodeErrorMessage: nil),
            AttachmentImageDecodeKey(data: second, decodeErrorMessage: nil)
        )
    }

    func testImageTaskIdentityChangesWithBytesOrError() {
        let first = AttachmentImageDecodeKey(data: Data([1, 2]) as NSData, decodeErrorMessage: nil)
        XCTAssertNotEqual(first, AttachmentImageDecodeKey(data: Data([1, 3]) as NSData, decodeErrorMessage: nil))
        XCTAssertNotEqual(first, AttachmentImageDecodeKey(data: first.data, decodeErrorMessage: "decode failed"))
        XCTAssertNotEqual(first, AttachmentImageDecodeKey(data: nil, decodeErrorMessage: nil))
        XCTAssertEqual(
            AttachmentImageDecodeKey(data: nil, decodeErrorMessage: nil),
            AttachmentImageDecodeKey(data: nil, decodeErrorMessage: nil)
        )
    }

    #if os(iOS)
    @MainActor
    func testImageFitsFirstNonzeroLayoutAndResizesWithViewport() {
        let scrollView = AttachmentImageScrollView()
        let imageView = UIImageView()
        scrollView.imageView = imageView
        scrollView.addSubview(imageView)
        scrollView.layoutIfNeeded()

        scrollView.frame = CGRect(x: 0, y: 0, width: 320, height: 480)
        scrollView.setNeedsLayout()
        scrollView.layoutIfNeeded()
        XCTAssertEqual(imageView.frame, CGRect(origin: .zero, size: scrollView.bounds.size))
        XCTAssertEqual(scrollView.contentSize, scrollView.bounds.size)

        scrollView.frame = CGRect(x: 0, y: 0, width: 480, height: 320)
        scrollView.setNeedsLayout()
        scrollView.layoutIfNeeded()
        XCTAssertEqual(imageView.frame, CGRect(origin: .zero, size: scrollView.bounds.size))
        XCTAssertEqual(scrollView.contentSize, scrollView.bounds.size)
    }
    #endif
}
