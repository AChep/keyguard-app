#if os(macOS)
import SwiftUI
import XCTest
@testable import KeyguardUI

final class CardGridLayoutTests: XCTestCase {
    @MainActor
    func testEveryCardFillsItsOwnRowHeight() {
        let probes = (0..<5).map { _ in NSView() }
        let heights: [CGFloat] = [40, 90, 20, 30, 50]
        let grid = NSHostingView(
            rootView: CardGridLayout {
                ForEach(heights.indices, id: \.self) { index in
                    self.card(height: heights[index])
                        .background(FrameProbe(view: probes[index]))
                }
            }.frame(width: 332))
        grid.frame.size = grid.fittingSize
        grid.layoutSubtreeIfNeeded()

        for (index, expectedHeight) in [90.0, 90, 30, 30, 50].enumerated() {
            XCTAssertEqual(probes[index].frame.height, expectedHeight, accuracy: 0.5)
            XCTAssertEqual(probes[index].frame.width, 160, accuracy: 0.5)
        }
    }

    @MainActor
    func testRightToLeftPlacesTheFirstCardAtTheLeadingEdge() {
        let first = NSView()
        let second = NSView()
        let grid = NSHostingView(
            rootView: CardGridLayout {
                card(height: 40).background(FrameProbe(view: first))
                card(height: 90).background(FrameProbe(view: second))
            }.frame(width: 332).environment(\.layoutDirection, .rightToLeft))
        grid.frame.size = grid.fittingSize
        grid.layoutSubtreeIfNeeded()

        XCTAssertGreaterThan(first.convert(first.bounds, to: grid).minX, second.convert(second.bounds, to: grid).minX)
    }

    @MainActor
    func testRowsUseTheirOwnTallestContentIncludingAnIncompleteRow() {
        let grid = NSHostingView(
            rootView: CardGridLayout {
                card(height: 40)
                card(height: 90)
                card(height: 20)
                card(height: 30)
                card(height: 50)
            }.frame(width: 332))

        XCTAssertEqual(grid.fittingSize.height, 90 + 12 + 30 + 12 + 50, accuracy: 0.5)
    }

    @MainActor
    func testNarrowContainerCollapsesToContentSizedSingleColumn() {
        let grid = NSHostingView(
            rootView: CardGridLayout {
                card(height: 40)
                card(height: 90)
            }.frame(width: 300))

        XCTAssertEqual(grid.fittingSize.height, 40 + 12 + 90, accuracy: 0.5)
    }

    @MainActor
    func testWrappedContentIsMeasuredAtTheResolvedColumnWidth() {
        let text = "Облікові записи, які потребують уваги після витоку даних."
        let content = Text(text).font(.body).fixedSize(horizontal: false, vertical: true)
        let direct = NSHostingView(rootView: content.frame(width: 160))
        let grid = NSHostingView(
            rootView: CardGridLayout {
                content.frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                card(height: 10)
            }.frame(width: 332))

        XCTAssertEqual(grid.fittingSize.height, direct.fittingSize.height, accuracy: 0.5)
    }

    private func card(height: CGFloat) -> some View {
        Color.clear
            .frame(height: height)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }

    private struct FrameProbe: NSViewRepresentable {
        let view: NSView

        func makeNSView(context: Context) -> NSView { view }

        func updateNSView(_ nsView: NSView, context: Context) {}
    }
}
#endif
