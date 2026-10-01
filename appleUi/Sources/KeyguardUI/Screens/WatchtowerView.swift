import SwiftUI
import KeyguardShared

/// Native SwiftUI rendering of the Watchtower dashboard. Shared state and
/// navigation actions are supplied by the Kotlin producer.
struct WatchtowerView: View {
    var entry: ScreenEntrySnapshot? = nil
    @Environment(NavigationModel.self) private var navigationModel
    @Environment(WatchtowerModel.self) private var watchtowerModel
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    #if os(iOS)
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    #endif
    #if os(macOS)
    @State private var filterSidebarShown = FilterSidebarMemory.watchtowerWide
    #else
    @State private var filterSidebarShown = false
    #endif

    private var watchtower: WatchtowerSnapshot { entry?.watchtower ?? watchtowerModel.watchtower }
    #if os(macOS)
    private var filterToolbar: FilterToolbarState {
        entry == nil ? watchtowerModel.watchtowerFilterToolbar : FilterToolbarState(snapshot: watchtower)
    }
    private var options: [WatchtowerOptionSnapshot] {
        entry == nil ? watchtowerModel.watchtowerOptionsToolbar.options : watchtower.options
    }
    #endif

    private func invokeAction(id: String) {
        if let entry {
            navigationModel.invokeEntryAction(instanceId: entry.instanceId, actionId: id)
        } else {
            watchtowerModel.invokeWatchtowerAction(id: id)
        }
    }
    private func invokeFilter(id: String) {
        if let entry {
            navigationModel.invokeEntryAction(instanceId: entry.instanceId, actionId: "filter:" + id)
        } else {
            watchtowerModel.invokeWatchtowerFilter(id: id)
        }
    }
    private func clearFilters() {
        if let entry {
            navigationModel.invokeEntryAction(instanceId: entry.instanceId, actionId: "clearFilters")
        } else {
            watchtowerModel.clearWatchtowerFilters()
        }
    }

    /// Content width below which the filter sidebar collapses into a toolbar
    /// menu. Clears the fixed sidebar + dashboard min width (320) with some slack.
    private let filterSidebarThreshold: CGFloat = SidebarLayout.width + 400

    var body: some View {
        Group {
            if entry != nil {
                dashboard
            } else {
                NavStackContainer(scope: "watchtower") { dashboard }
            }
        }
        .observing(
            enabled: entry == nil,
            start: { watchtowerModel.startWatchtowerObservation() },
            stop: { watchtowerModel.stopWatchtowerObservation() }
        )
    }

    private var dashboard: some View {
        twoPane
            .navigationTitle(L10n.watchtowerHeaderTitle)
            .toolbar { optionsToolbar }
    }

    // MARK: - Layout

    /// The dashboard with an optional left-hand filter sidebar. Mirrors the
    /// `HomeView` / `SendView` two-pane behaviour: the filters become a sidebar
    /// when wide enough, otherwise they collapse into the toolbar filter menu.
    private var twoPane: some View {
        // GeometryReader + HStack (deterministic fill) — same approach as
        // `MasterDetailLayout`; the screen title is rendered by the system in the
        // titlebar from `.navigationTitle`.
        GeometryReader { proxy in
            HStack(spacing: 0) {
                if filterSidebarShown {
                    FilterSidebar(
                        filters: watchtower.filters,
                        count: nil,
                        canClearFilters: watchtower.canClearFilters,
                        invoke: { invokeFilter(id: $0) },
                        clear: { clearFilters() }
                    )
                    .frame(width: SidebarLayout.width)
                    Divider()
                }
                dashboardColumn
                    .frame(minWidth: 320, maxWidth: .infinity, maxHeight: .infinity)
                    .background(GroupedSurfaceStyle.background)
            }
            .frame(width: proxy.size.width, height: proxy.size.height)
            .onChange(of: proxy.size.width) { _, width in recomputeFilterSidebar(width) }
            .onAppear { recomputeFilterSidebar(proxy.size.width) }
        }
    }

    /// Keep resize churn out of the toolbar/body state path: the boolean only
    /// changes when the available width crosses the sidebar collapse threshold.
    private func recomputeFilterSidebar(_ width: CGFloat) {
        #if os(iOS)
        let shouldShow = horizontalSizeClass == .regular && width >= filterSidebarThreshold
        #else
        let shouldShow = width >= filterSidebarThreshold
        #endif
        if filterSidebarShown != shouldShow {
            filterSidebarShown = shouldShow
            #if os(macOS)
            // Remember for the next mount's first render (see `FilterSidebarMemory`).
            FilterSidebarMemory.watchtowerWide = shouldShow
            #endif
        }
    }

    private var dashboardColumn: some View {
        Group {
            if !watchtower.loaded {
                LoadingIndicator()
            } else {
                content
            }
        }
    }

    private var content: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                if watchtower.unreadCount > 0 {
                    newAlerts
                }
                if !watchtower.strength.isEmpty {
                    strengthSection
                }
                if !watchtower.security.isEmpty {
                    cardSection(title: L10n.watchtowerSectionSecurityLabel, cards: watchtower.security)
                }
                if !watchtower.maintenance.isEmpty {
                    cardSection(title: L10n.watchtowerSectionMaintenanceLabel, cards: watchtower.maintenance)
                }
            }
            .padding(24)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    // MARK: - Options toolbar

    #if os(macOS)
    @ToolbarContentBuilder
    private var optionsToolbar: some CustomizableToolbarContent {
        if !filterSidebarShown && !filterToolbar.filters.isEmpty {
            ToolbarItem(id: "watchtower.filters") {
                WatchtowerFilterToolbarButton(
                    state: filterToolbar, invoke: { invokeFilter(id: $0) }, clear: clearFilters)
            }
        }
        if !options.isEmpty {
            ToolbarItem(id: "watchtower.more") {
                WatchtowerOptionsToolbarButton(options: options, invoke: { invokeAction(id: $0) })
            }
        }
    }
    #else
    @ToolbarContentBuilder
    private var optionsToolbar: some ToolbarContent {
        if !filterSidebarShown && !watchtower.filters.isEmpty {
            ToolbarItem {
                FilterMenu(
                    filters: watchtower.filters,
                    canClearFilters: watchtower.canClearFilters,
                    activeFilterCount: Int(watchtower.activeFilterCount),
                    invoke: { invokeFilter(id: $0) },
                    clear: { clearFilters() }
                )
            }
        }
        if !watchtower.options.isEmpty {
            ToolbarItem {
                WatchtowerOptionsToolbarButton(options: watchtower.options, invoke: { invokeAction(id: $0) })
            }
        }
    }
    #endif

    // MARK: - New alerts

    private var newAlerts: some View {
        Button {
            // Fires the producer's unread navigation; the stack pushes the alerts list.
            invokeAction(id: "unread")
        } label: {
            HStack(spacing: 12) {
                Image(systemName: "bell.badge.fill")
                    .foregroundStyle(.tint)
                Text(L10n.watchtowerAlertsNewTitle)
                Spacer()
                Text("\(watchtower.unreadCount)")
                    .font(.body.monospaced())
                    .foregroundStyle(.secondary)
                if watchtower.canClickUnread {
                    // chevron.forward (layout-direction aware, flips for RTL) to
                    // match the Tools rows and the package-wide convention.
                    Image(systemName: "chevron.forward")
                        .foregroundStyle(.secondary)
                }
            }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(GroupedSurfaceStyle.content, in: RoundedRectangle(cornerRadius: 12))
        }
        .buttonStyle(.plain)
        .disabled(!watchtower.canClickUnread)
    }

    // MARK: - Password strength

    private var strengthSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(L10n.watchtowerSectionPasswordStrengthLabel)
                .font(.headline)
            if watchtower.strength.contains(where: { $0.count > 0 }) {
                strengthBar
            }
            FlowLayout(spacing: 8, clampsToWidth: true) {
                ForEach(watchtower.strength, id: \.id) { item in
                    strengthChip(item)
                }
            }
        }
    }

    private var strengthDistributionKey: [String] {
        watchtower.strength.map { "\($0.id):\($0.count)" }
    }

    private var strengthBar: some View {
        let segments = watchtower.strength.filter { $0.count > 0 }
        let total = segments.reduce(0) { $0 + Int($1.count) }
        return GeometryReader { proxy in
            let gap: CGFloat = 4
            let totalGap = CGFloat(max(0, segments.count - 1)) * gap
            let available = max(0, proxy.size.width - totalGap)
            HStack(spacing: gap) {
                ForEach(segments, id: \.id) { item in
                    RoundedRectangle(cornerRadius: 8, style: .continuous)
                        .fill(scoreColor(item.score))
                        .frame(
                            width: total > 0
                                ? available * CGFloat(Int(item.count)) / CGFloat(total)
                                : 0)
                }
            }
            .frame(width: proxy.size.width, height: proxy.size.height, alignment: .leading)
            .animation(reduceMotion ? nil : .spring(duration: 0.3), value: strengthDistributionKey)
        }
        .frame(height: 24)
        // The bar is a colour-only summary; collapse it into one VoiceOver element
        // whose value reads each bucket's label + count, so the distribution is
        // available non-visually (mirrors the labelled chips below it).
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(L10n.watchtowerSectionPasswordStrengthLabel)
        .accessibilityValue(
            segments
                .map { "\(scoreLabel($0.score)) \($0.count)" }
                .joined(separator: ", ")
        )
    }

    private func strengthChip(_ item: WatchtowerStrengthSnapshot) -> some View {
        Button {
            invokeAction(id: item.id)
        } label: {
            WatchtowerStrengthChipContent(
                title: scoreLabel(item.score),
                color: scoreColor(item.score),
                count: item.count
            ) {
                if item.new_ > 0 {
                    newBadge(item.new_)
                }
            }
        }
        .buttonStyle(.plain)
        .disabled(!item.canClick)
    }

    // MARK: - Card sections

    private func cardSection(title: String, cards: [WatchtowerCardSnapshot]) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(title)
                .font(.headline)
            CardGridLayout {
                ForEach(cards, id: \.id) { card in
                    cardView(card)
                }
            }
        }
    }

    private func cardView(_ card: WatchtowerCardSnapshot) -> some View {
        Button {
            invokeAction(id: card.id)
        } label: {
            VStack(alignment: .leading, spacing: 8) {
                HStack(alignment: .top, spacing: 4) {
                    Text("\(card.count)")
                        .font(.largeTitle.bold())
                    if card.new_ > 0 {
                        newBadge(card.new_)
                            .padding(.top, 6)
                    }
                    Spacer()
                    statusIcon(card.status)
                        .padding(.top, 6)
                }
                Text(card.title)
                    .font(.headline)
                    .foregroundStyle(.primary)
                    .fixedSize(horizontal: false, vertical: true)
                Text(card.text)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding(16)
            .frame(maxWidth: .infinity, minHeight: 150, maxHeight: .infinity, alignment: .topLeading)
            .background(GroupedSurfaceStyle.content, in: RoundedRectangle(cornerRadius: 12))
        }
        .buttonStyle(.plain)
        .disabled(!card.canClick)
        .accessibilityElement(children: .combine)
        .accessibilityValue(statusLabel(card.status))
    }

    // MARK: - Helpers

    @ViewBuilder
    private func statusIcon(_ status: WatchtowerCardStatus) -> some View {
        // The status is announced via the enclosing card's combined
        // .accessibilityValue (see cardView), so the colour-coded glyph itself is
        // decorative here — hiding it avoids the status being spoken twice.
        Group {
            if status == WatchtowerCardStatus.error {
                Image(systemName: "exclamationmark.octagon.fill")
                    .foregroundStyle(.red)
            } else if status == WatchtowerCardStatus.warning {
                Image(systemName: "exclamationmark.triangle.fill")
                    .foregroundStyle(.orange)
            } else if status == WatchtowerCardStatus.info {
                Image(systemName: "info.circle.fill")
                    .foregroundStyle(.blue)
            } else {
                Image(systemName: "checkmark.circle.fill")
                    .foregroundStyle(.green)
            }
        }
        .accessibilityHidden(true)
    }

    /// Spoken status equivalent for the colour-coded `statusIcon`.
    private func statusLabel(_ status: WatchtowerCardStatus) -> String {
        if status == WatchtowerCardStatus.error {
            return L10n.prefItemAutomaticBackupsPanelErrorLabel
        } else if status == WatchtowerCardStatus.warning {
            return L10n.warning
        } else if status == WatchtowerCardStatus.info {
            return L10n.info
        } else {
            return L10n.ok
        }
    }

    private func newBadge(_ count: Int32) -> some View {
        Text("+\(count)")
            .font(.caption2.bold())
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(Color.accentColor, in: Capsule())
            // The system / in-app accent is user-configurable (e.g. Yellow,
            // Graphite on macOS), so white can fall below contrast. Pick the
            // luminance-contrasting foreground instead of hardcoding white.
            .foregroundStyle(Color.accentColor.contrastingTextColor)
    }

    private func scoreColor(_ score: String) -> Color {
        switch score {
        case "Weak": return .red
        case "Fair": return .orange
        case "Good": return .yellow
        case "Strong": return .green
        case "VeryStrong": return .teal
        default: return .gray
        }
    }

    private func scoreLabel(_ score: String) -> String {
        switch score {
        case "Weak": return L10n.passwordStrengthWeakLabel
        case "Fair": return L10n.passwordStrengthFairLabel
        case "Good": return L10n.passwordStrengthGoodLabel
        case "Strong": return L10n.passwordStrengthStrongLabel
        case "VeryStrong": return L10n.passwordStrengthVeryStrongLabel
        default: return score
        }
    }
}

#if os(macOS)
private struct WatchtowerFilterToolbarButton: View {
    let state: FilterToolbarState
    let invoke: (String) -> Void
    let clear: () -> Void

    var body: some View {
        FilterMenu(
            filters: state.filters,
            canClearFilters: state.canClearFilters,
            activeFilterCount: state.activeFilterCount,
            invoke: invoke,
            clear: clear
        )
    }
}
#endif

private struct WatchtowerOptionsToolbarButton: View {
    let options: [WatchtowerOptionSnapshot]
    let invoke: (String) -> Void

    var body: some View {
        Menu {
            ForEach(options, id: \.id) { option in
                Button(option.title) {
                    invoke(option.id)
                }
            }
        } label: {
            Label(L10n.moreActions, systemImage: "ellipsis.circle")
        }
    }
}
