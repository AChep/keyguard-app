import SwiftUI
import KeyguardShared

/// This device, waiting for another one, and what linking devices does.
struct AppLinkUnlinkedHero: View {
    var body: some View {
        VStack(spacing: 8) {
            PairingStageView(phase: .idle, height: 160)
            Text(L10n.appLinkHeroTitle)
                .font(.title2.weight(.semibold))
            Text(L10n.appLinkHeroText)
                .font(.callout)
                .foregroundStyle(.secondary)
        }
        .multilineTextAlignment(.center)
        .frame(maxWidth: .infinity)
        .padding(.bottom, 8)
    }
}

/// Plain notes under an info icon, as the backups and feedback screens explain
/// themselves: a bold title and a text each.
struct AppLinkInfoSection: View {
    let notes: [(title: String, text: String)]

    /// How linked devices work.
    static var howItWorks: [(title: String, text: String)] {
        [
            (L10n.appLinkHowPairTitle, L10n.appLinkHowPairText),
            (L10n.appLinkHowSyncTitle, L10n.appLinkHowSyncText),
            (L10n.appLinkHowPrivacyTitle, L10n.appLinkHowPrivacyText),
            (L10n.appLinkHowSecurityTitle, L10n.appLinkHowSecurityText),
        ]
    }

    /// How the relay is used, and what it learns.
    static var relay: [(title: String, text: String)] {
        [
            (L10n.appLinkRelayHowTitle, L10n.appLinkRelayHowText),
            (L10n.appLinkRelaySeesTitle, L10n.appLinkRelaySeesText),
        ]
    }

    var body: some View {
        Section {
            EmptyView()
        } footer: {
            VStack(alignment: .leading, spacing: 20) {
                Image(systemName: "info.circle")
                    .font(.title3)
                    .accessibilityHidden(true)
                ForEach(Array(notes.enumerated()), id: \.offset) { _, note in
                    VStack(alignment: .leading, spacing: 4) {
                        Text(note.title)
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(.primary)
                        Text(note.text)
                            .font(.subheadline)
                    }
                    .accessibilityElement(children: .combine)
                }
            }
            .foregroundStyle(.secondary)
            .padding(.top, 16)
        }
    }
}

/// The linked devices as a mesh: this device in the middle, the `peers` around it. A link
/// is solid and busy while the devices have a session, and dashed while they have none.
/// One peer sits beside this device, as on the pairing stage. Decoration: the list says
/// the same in words.
struct AppLinkMeshView: View {
    let peers: [KeyguardDevicesSnapshot.Device]

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private static let maxPeers = 6

    var body: some View {
        if peers.isEmpty {
            PairingStageView(phase: .idle, height: 160)
        } else {
            TimelineView(.animation(paused: reduceMotion)) { context in
                let time = reduceMotion ? 0 : context.date.timeIntervalSinceReferenceDate
                Canvas { context, size in
                    draw(in: &context, size: size, time: time)
                } symbols: {
                    Image(systemName: PairingStageView.thisDeviceSymbol)
                        .font(.system(size: 24, weight: .medium))
                        .foregroundStyle(Color.accentColor)
                        .tag(-1)
                    ForEach(Array(shown.enumerated()), id: \.offset) { index, device in
                        Image(systemName: keyguardPlatformSymbol(device.platform))
                            .font(.system(size: single ? 24 : 16, weight: .medium))
                            .foregroundStyle(color(device.status))
                            .tag(index)
                    }
                    if overflow > 0 {
                        // A count, as on a badge.
                        Text(verbatim: "+\(overflow)")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(.secondary)
                            .tag(shown.count)
                    }
                }
            }
            .frame(height: 200)
            .frame(maxWidth: .infinity)
            .accessibilityHidden(true)
        }
    }

    private var shown: [KeyguardDevicesSnapshot.Device] {
        peers.count > Self.maxPeers ? Array(peers.prefix(Self.maxPeers - 1)) : peers
    }

    private var overflow: Int { peers.count - shown.count }

    private var count: Int { shown.count + (overflow > 0 ? 1 : 0) }

    private var single: Bool { count == 1 }

    private func color(_ status: KeyguardDevicesSnapshot.Status?) -> Color {
        status?.color ?? .secondary
    }

    private func live(_ status: KeyguardDevicesSnapshot.Status?) -> Bool {
        status == .ok || status == .warning
    }

    private func draw(in context: inout GraphicsContext, size: CGSize, time: TimeInterval) {
        let center = CGPoint(x: size.width / 2, y: size.height / 2)
        let loop = (time / 2.6).truncatingRemainder(dividingBy: 1)
        let drift = time / 14
        let spin = Angle.radians(time / 36 * 2 * .pi)
        let centerRadius: CGFloat = single ? 36 : 32
        let peerRadius: CGFloat = single ? 36 : 24
        let halfDistance = min(size.width * 0.27, 112)
        let radiusX = min(size.width * 0.36, 148)
        let radiusY: CGFloat = 70

        let me = single ? CGPoint(x: center.x - halfDistance, y: center.y) : center
        func peerPoint(_ index: Int) -> CGPoint {
            if single { return CGPoint(x: center.x + halfDistance, y: center.y) }
            let wobble = 0.07 * sin(2 * .pi * (drift + Double(index) / Double(count)))
            let angle = -Double.pi / 2 + 2 * .pi * Double(index) / Double(count) + wobble
            return CGPoint(x: center.x + radiusX * cos(angle), y: center.y + radiusY * sin(angle))
        }

        // Every device syncs with every other one.
        if count > 2 {
            var mesh = Path()
            for index in 0..<count {
                mesh.move(to: peerPoint(index))
                mesh.addLine(to: peerPoint((index + 1) % count))
            }
            context.stroke(mesh, with: .color(.secondary.opacity(0.25)), lineWidth: 1)
        }

        for index in 0..<count {
            let status = index < shown.count ? shown[index].status : nil
            let peer = peerPoint(index)
            let dx = peer.x - me.x
            let dy = peer.y - me.y
            let distance = max(1, (dx * dx + dy * dy).squareRoot())
            let start = CGPoint(x: me.x + dx / distance * centerRadius, y: me.y + dy / distance * centerRadius)
            let end = CGPoint(x: peer.x - dx / distance * peerRadius, y: peer.y - dy / distance * peerRadius)
            let link = PairingLink(start: start, end: end)
            if live(status) {
                context.stroke(
                    link.path(), with: .color(color(status)), style: StrokeStyle(lineWidth: 2, lineCap: .round))
                // The sync goes both ways.
                let t = (loop + Double(index) * 0.37).truncatingRemainder(dividingBy: 1)
                let at = link.point(index % 2 == 0 ? t : 1 - t)
                let dot = CGRect(x: at.x - 3.5, y: at.y - 3.5, width: 7, height: 7)
                context.fill(Path(ellipseIn: dot), with: .color(color(status).opacity(sin(.pi * t))))
            } else {
                context.stroke(
                    link.path(),
                    with: .color(color(status).opacity(0.6)),
                    style: StrokeStyle(lineWidth: 2, lineCap: .round, dash: [5, 5])
                )
            }
            let more = index >= shown.count
            let node = cookiePath(
                center: peer, radius: peerRadius, lobes: more ? 1 : 7, depth: more ? 0 : 0.14,
                rotation: .radians(-spin.radians))
            context.fill(node, with: .color(color(status).opacity(0.18)))
            if let symbol = context.resolveSymbol(id: index) {
                context.draw(symbol, at: peer)
            }
        }

        let node = cookiePath(center: me, radius: centerRadius, lobes: 9, depth: 0.14, rotation: spin)
        context.fill(node, with: .color(Color.accentColor.opacity(0.22)))
        if let symbol = context.resolveSymbol(id: -1) {
            context.draw(symbol, at: me)
        }
    }
}
