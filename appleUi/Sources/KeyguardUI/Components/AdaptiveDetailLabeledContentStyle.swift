import SwiftUI

/// Measures the complete value and its controls before choosing a horizontal row.
/// The fallback wraps text at the available width, including inside a menu label.
struct AdaptiveDetailLabeledContentStyle: LabeledContentStyle {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    func makeBody(configuration: Configuration) -> some View {
        ViewThatFits(in: .horizontal) {
            if !dynamicTypeSize.isAccessibilitySize {
                HStack(alignment: .firstTextBaseline, spacing: 16) {
                    configuration.label
                        .fixedSize()
                    Spacer(minLength: 0)
                    configuration.content
                        .fixedSize(horizontal: true, vertical: false)
                }
            }
            VStack(alignment: .leading, spacing: stackedSpacing) {
                configuration.label
                    .fixedSize(horizontal: false, vertical: true)
                configuration.content
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(.top, 4)
        }
    }

    private var stackedSpacing: CGFloat {
        #if os(iOS)
        0
        #else
        4
        #endif
    }
}
