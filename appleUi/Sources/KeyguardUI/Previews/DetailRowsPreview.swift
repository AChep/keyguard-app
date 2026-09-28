#if DEBUG
import SwiftUI
import KeyguardShared

/// Interactive gallery backed by production rows, with no core or vault access.
struct DetailRowsPreview: View {
    @State private var scenario = "Mixed rows"
    @State private var presentation = "Vault / Send"
    @State private var dark = false
    @State private var largeText = false
    @State private var rtl = false
    @State private var narrow = false
    @State private var revealed = false
    @State private var cardRevealed = false
    @State private var tick = 0
    @State private var lastAction = "No action"
    @State private var path: [String] = []

    private var items: [VaultItemSnapshot] {
        switch scenario {
        case "Linked URIs": return DetailRowsPreviewFixtures.linkedUris
        case "Long values": return DetailRowsPreviewFixtures.longValues
        case "Attachment states": return DetailRowsPreviewFixtures.attachmentStates
        case "Supporting labels": return DetailRowsPreviewFixtures.supportingLabels
        case "Security notices": return DetailRowsPreviewFixtures.securityNotices
        case "Identity": return DetailRowsPreviewFixtures.identities
        case "No fields": return []
        case "Named first section":
            return [DetailRowsPreviewFixtures.item("first-section", .section, title: "Credentials")]
                + DetailRowsPreviewFixtures.longValues
        default:
            return DetailRowsPreviewFixtures.mixed(revealed: revealed, cardRevealed: cardRevealed, tick: tick)
        }
    }

    var body: some View {
        NavigationStack(path: $path) {
            VStack(spacing: 0) {
                VStack(alignment: .leading) {
                    Picker("Scenario", selection: $scenario) {
                        ForEach(
                            [
                                "Mixed rows", "Linked URIs", "Long values", "Attachment states", "Supporting labels",
                                "Identity",
                                "Security notices", "No fields", "Named first section",
                            ],
                            id: \.self
                        ) { Text($0) }
                    }
                    Picker("Presentation", selection: $presentation) {
                        ForEach(["Vault / Send", "Account", "Quick Search"], id: \.self) { Text($0) }
                    }
                    HStack {
                        Toggle("Dark", isOn: $dark)
                        Toggle("Large text", isOn: $largeText)
                        Toggle("RTL", isOn: $rtl)
                        Toggle("Narrow", isOn: $narrow)
                    }
                }
                .dynamicTypeSize(.large)
                .padding()
                Divider()
                galleryContent
                    .frame(maxWidth: narrow ? 320 : .infinity)
                    .frame(maxWidth: .infinity)
            }
            .navigationTitle("Detail rows preview")
            .navigationDestination(for: String.self) { _ in
                DetailForm(items: DetailRowsPreviewFixtures.longValues, invoke: { lastAction = "Pushed: \($0)" }) {
                    DetailIdentityHeader(title: "Pushed item") { _ in Image(systemName: "key") }
                }
            }
            .safeAreaInset(edge: .bottom) {
                Text(verbatim: lastAction)
                    .font(.footnote)
                    .frame(maxWidth: .infinity)
                    .padding(8)
                    .background(.bar)
            }
        }
        .preferredColorScheme(dark ? .dark : .light)
        .environment(\.dynamicTypeSize, largeText ? .accessibility3 : .large)
        .environment(\.layoutDirection, rtl ? .rightToLeft : .leftToRight)
        .environment(
            \.openURL,
            OpenURLAction { url in
                lastAction = "Open URL: \(url.absoluteString)"
                return .handled
            }
        )
        .task {
            while !Task.isCancelled {
                do { try await Task.sleep(for: .seconds(1)) } catch { return }
                tick += 1
            }
        }
    }

    @ViewBuilder
    private var galleryContent: some View {
        if presentation == "Account" {
            DetailScaffold(title: "Synthetic account", items: items, invoke: invoke) {
                DetailHeaderSymbol(systemName: "person.crop.circle")
            } actions: {
                Button("Edit") { lastAction = "Edit account" }
            }
        } else if presentation == "Quick Search" {
            Form {
                FieldCell(
                    title: "Username", actions: [], invoke: invoke, layout: .stacked,
                    accessibilityValueOverride: "demo@example.com"
                ) {
                    Text("demo@example.com")
                } accessories: {
                    DetailIconButton(title: "Copy", systemImage: "doc.on.doc") { invoke("quick-copy") }
                }
                FieldCell(
                    title: "One-time password", actions: [], invoke: invoke, layout: .stacked,
                    accessibilityValueOverride: "123456"
                ) {
                    TotpBadgeView(
                        totp: TotpFieldSnapshot(
                            groups: [["1", "2", "3"], ["4", "5", "6"]], codeRaw: "123456", isLoading: false,
                            isError: false, isTimeBased: true, counterText: "25", progress: 0.8))
                }
            }
            .formStyle(.grouped)
        } else {
            DetailForm(items: items, invoke: invoke) {
                DetailIdentityHeader(title: "Обліковий запис — 開発用アカウント — حساب العمل") { size in
                    FaviconView(url: nil, placeholder: "GH", size: size)
                }
            }
        }
    }

    private func invoke(_ id: String) {
        lastAction = "Root: \(id)"
        switch id {
        case "reveal-password": revealed.toggle()
        case "reveal-card": cardRevealed.toggle()
        case "push": path.append("pushed")
        default: break
        }
    }
}

#Preview("Detail rows") {
    DetailRowsPreview()
}
#endif
