import SwiftUI

extension View {
    func scrollsToTop(onChangeOf revision: some Equatable, topId: String?) -> some View {
        ScrollViewReader { proxy in
            self.onChange(of: revision) { _, _ in
                if let topId {
                    proxy.scrollTo(topId, anchor: .top)
                }
            }
        }
    }
}
