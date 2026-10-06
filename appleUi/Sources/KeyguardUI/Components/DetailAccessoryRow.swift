import SwiftUI

/// Gives content the full width when its independent controls cannot fit beside it.
struct DetailAccessoryRow<Content: View, Accessories: View>: View {
    @ViewBuilder var content: () -> Content
    @ViewBuilder var accessories: () -> Accessories

    var body: some View {
        ViewThatFits(in: .horizontal) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                content()
                    .fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 0)
                accessories()
                    .fixedSize(horizontal: true, vertical: false)
            }
            VStack(alignment: .leading, spacing: 8) {
                content()
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                HStack(spacing: 8) {
                    Spacer(minLength: 0)
                    accessories()
                }
            }
        }
    }
}
