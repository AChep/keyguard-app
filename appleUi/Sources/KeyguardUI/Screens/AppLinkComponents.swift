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
/// One peer sits beside this device, as on the pairing stage. A click on a device opens
/// a menu with its name and the actions of its row; on this device, the actions of its
/// section. VoiceOver skips the mesh: the list says and does the same.
struct AppLinkMeshView: View {
    let peers: [KeyguardDevicesSnapshot.Device]
    /// Removes a peer, after asking.
    let model: KeyguardDevicesModel
    let thisDeviceLabel: String
    let rename: @MainActor () -> Void
    let openConnection: @MainActor () -> Void

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private static let maxPeers = 6

    var body: some View {
        if peers.isEmpty {
            PairingStageView(phase: .idle, height: 160)
        } else {
            // Built once per change of the peers, not once per frame.
            let nodes = Array(shown.enumerated())
            GeometryReader { proxy in
                TimelineView(.animation(paused: reduceMotion)) { context in
                    let time = reduceMotion ? 0 : context.date.timeIntervalSinceReferenceDate
                    let geometry = MeshGeometry(size: proxy.size, time: time, count: count)
                    ZStack {
                        Canvas { context, _ in
                            draw(in: &context, geometry: geometry, time: time)
                        } symbols: {
                            Image(systemName: PairingStageView.thisDeviceSymbol)
                                .font(.system(size: 24, weight: .medium))
                                .foregroundStyle(Color.accentColor)
                                .tag(-1)
                            ForEach(nodes, id: \.offset) { index, device in
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
                        menus(geometry: geometry, nodes: nodes, now: context.date)
                    }
                }
            }
            .frame(height: 200)
            .frame(maxWidth: .infinity)
            .accessibilityHidden(true)
        }
    }

    /// A menu over each device, the `+N` count aside.
    @ViewBuilder
    private func menus(
        geometry: MeshGeometry,
        nodes: [EnumeratedSequence<[KeyguardDevicesSnapshot.Device]>.Element],
        now: Date
    ) -> some View {
        // The menus show the time since a device was last seen; a new minute is enough
        // to update it, and spares the frames in between.
        let minute = Date(timeIntervalSinceReferenceDate: (now.timeIntervalSinceReferenceDate / 60).rounded(.down) * 60)
        AppLinkMeshNodeMenu(diameter: geometry.centerRadius * 2) {
            Section(thisDeviceLabel) {
                Button(L10n.rename, systemImage: "pencil", action: rename)
                Button(L10n.appLinkConnectionTitle, systemImage: "server.rack", action: openConnection)
            }
        }
        .position(geometry.me)
        ForEach(nodes, id: \.element.id) { index, device in
            AppLinkMeshPeerMenu(
                device: device,
                model: model,
                now: minute,
                diameter: geometry.peerRadius * 2
            )
            .position(geometry.peerPoint(index))
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

    private func draw(in context: inout GraphicsContext, geometry: MeshGeometry, time: TimeInterval) {
        let loop = (time / 2.6).truncatingRemainder(dividingBy: 1)
        let spin = Angle.radians(time / 36 * 2 * .pi)
        let centerRadius = geometry.centerRadius
        let peerRadius = geometry.peerRadius
        let me = geometry.me

        // Every device syncs with every other one.
        if count > 2 {
            var mesh = Path()
            for index in 0..<count {
                mesh.move(to: geometry.peerPoint(index))
                mesh.addLine(to: geometry.peerPoint((index + 1) % count))
            }
            context.stroke(mesh, with: .color(.secondary.opacity(0.25)), lineWidth: 1)
        }

        for index in 0..<count {
            let status = index < shown.count ? shown[index].status : nil
            let peer = geometry.peerPoint(index)
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

/// Where the mesh puts its devices at a time: the drawing and the menus over it share it.
private struct MeshGeometry {
    let centerRadius: CGFloat
    let peerRadius: CGFloat
    /// This device: in the middle, or beside the one peer.
    let me: CGPoint
    private let center: CGPoint
    private let count: Int
    private let drift: Double
    private let halfDistance: CGFloat
    private let radiusX: CGFloat
    private let radiusY: CGFloat = 70

    /// - Parameter count: the peers on the mesh, with the one that stands for the rest.
    init(size: CGSize, time: TimeInterval, count: Int) {
        let single = count == 1
        self.count = count
        center = CGPoint(x: size.width / 2, y: size.height / 2)
        drift = time / 14
        centerRadius = single ? 36 : 32
        peerRadius = single ? 36 : 24
        halfDistance = min(size.width * 0.27, 112)
        radiusX = min(size.width * 0.36, 148)
        me = single ? CGPoint(x: center.x - halfDistance, y: center.y) : center
    }

    func peerPoint(_ index: Int) -> CGPoint {
        if count == 1 { return CGPoint(x: center.x + halfDistance, y: center.y) }
        let wobble = 0.07 * sin(2 * .pi * (drift + Double(index) / Double(count)))
        let angle = -Double.pi / 2 + 2 * .pi * Double(index) / Double(count) + wobble
        return CGPoint(x: center.x + radiusX * cos(angle), y: center.y + radiusY * sin(angle))
    }
}

/// A peer on the mesh: its name, how it is, and the actions of its row.
///
/// Its inputs compare by identity, so the frames of the mesh skip its body; `now` is
/// the minute the status line is up to.
private struct AppLinkMeshPeerMenu: View {
    let device: KeyguardDevicesSnapshot.Device
    let model: KeyguardDevicesModel
    let now: Date
    let diameter: CGFloat

    @Environment(\.locale) private var locale

    var body: some View {
        AppLinkMeshNodeMenu(diameter: diameter) {
            Section(device.label) {
                if let status = device.statusLine(now: now, locale: locale) {
                    Text(status)
                }
            }
            // A section of its own: the menu divides it from the device.
            Section {
                KeyguardDeviceActions(device: device) {
                    model.remove(id: device.id)
                }
            }
        }
    }
}

/// An invisible button over a device of the mesh that opens its menu.
private struct AppLinkMeshNodeMenu<Content: View>: View {
    let diameter: CGFloat
    @ViewBuilder let content: () -> Content

    var body: some View {
        Menu(content: content) {
            Color.clear
                .frame(width: diameter, height: diameter)
                .contentShape(Circle())
        }
        .menuStyle(.borderlessButton)
        .menuIndicator(.hidden)
        .fixedSize()
    }
}
