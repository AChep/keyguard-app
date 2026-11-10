import SwiftUI
import KeyguardShared

/// The other devices of a sync group, and what adds one: a menu asks which of the two
/// devices shows the code.
struct KeyguardDevicesListSection: View {
    let title: String
    let model: KeyguardDevicesModel
    /// Adds a device, scanning its code if `true`, or showing one; `nil` hides the row.
    let addDevice: (@MainActor (_ scan: Bool) -> Void)?

    private var snapshot: KeyguardDevicesSnapshot { model.snapshot }

    var body: some View {
        let devices = snapshot.devices.filter { !$0.isThisDevice }
        if !devices.isEmpty || addDevice != nil {
            Section(title) {
                ForEach(devices, id: \.id) { device in
                    KeyguardDeviceRow(
                        device: device,
                        remove: { model.remove(id: device.id) }
                    )
                }
                if let addDevice {
                    Menu {
                        KeyguardPairMethodButtons(pair: addDevice)
                    } label: {
                        Label(snapshot.addDeviceTitle, systemImage: "plus")
                            .contentShape(Rectangle())
                    }
                    .menuStyle(.borderlessButton)
                    .menuIndicator(.hidden)
                }
            }
        }
    }
}

/// Warns that this device was removed from the group, if it was.
struct KeyguardDevicesRemovedRow: View {
    let snapshot: KeyguardDevicesSnapshot

    var body: some View {
        if snapshot.removed {
            Label(
                snapshot.removedByRemoved
                    ? L10n.appLinkAlertRemovedThisByFormerText : L10n.appLinkRemovedThisDeviceText,
                systemImage: "exclamationmark.triangle.fill"
            )
            .foregroundStyle(.orange)
        }
    }
}

/// Leaves the sync group.
struct KeyguardDevicesLeaveSection: View {
    let model: KeyguardDevicesModel

    private var snapshot: KeyguardDevicesSnapshot { model.snapshot }

    var body: some View {
        if snapshot.canLeave {
            Section {
                Button(role: .destructive, action: model.leave) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(snapshot.leaveTitle)
                        Text(snapshot.leaveText)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            }
        }
    }
}

struct KeyguardDevicesHeaderRow: View {
    let header: KeyguardDevicesSnapshot.Header

    var body: some View {
        HStack(spacing: 12) {
            KeyguardStatusDot(status: header.status)
            VStack(alignment: .leading, spacing: 2) {
                if let upToDateText = header.upToDateText {
                    Text(upToDateText)
                        .font(.headline)
                    Text(header.relayText)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                } else {
                    Text(header.relayText)
                        .font(.headline)
                }
                if let storageText = header.storageText {
                    Text(storageText)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
            }
        }
        .accessibilityElement(children: .combine)
    }
}

private struct KeyguardDeviceRow: View {
    let device: KeyguardDevicesSnapshot.Device
    let remove: () -> Void

    @Environment(\.locale) private var locale

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: platformSymbol)
                .font(.title3)
                .foregroundStyle(.tint)
                .frame(width: 28)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(device.label)
                        .font(.body.weight(.medium))
                        .lineLimit(1)
                    if device.isThisDevice {
                        Text(L10n.appLinkThisDevice)
                            .font(.caption2.weight(.semibold))
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(.tint.opacity(0.15), in: Capsule())
                    }
                }
                Text(device.fingerprint)
                    .font(.caption.monospaced())
                    .foregroundStyle(.secondary)
                    .textSelection(.enabled)
                Text(device.addedText)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                TimelineView(.periodic(from: .now, by: 60)) { context in
                    if let text = device.statusLine(now: context.date, locale: locale) {
                        Text(text)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            }
            Spacer(minLength: 8)
            if let status = device.status {
                KeyguardStatusDot(status: status)
            }
            if device.canRemove {
                Menu {
                    actions
                } label: {
                    Image(systemName: "ellipsis.circle")
                        .touchTarget()
                }
                .menuStyle(.borderlessButton)
                .fixedSize()
                .accessibilityLabel(L10n.moreActions)
            }
        }
        .contextMenu {
            actions
        }
    }

    private var actions: some View {
        KeyguardDeviceActions(device: device, remove: remove)
    }

    private var platformSymbol: String { keyguardPlatformSymbol(device.platform) }
}

/// What a device offers in its menus: removing it, if this device may.
struct KeyguardDeviceActions: View {
    let device: KeyguardDevicesSnapshot.Device
    let remove: () -> Void

    var body: some View {
        if device.canRemove {
            Button(role: .destructive, action: remove) {
                Label(L10n.remove, systemImage: "trash")
            }
        }
    }
}

extension KeyguardDevicesSnapshot.Device {
    /// "Last seen 5 min. ago via relay" for a device seen before; otherwise how it is connected.
    func statusLine(now: Date, locale: Locale) -> String? {
        guard let lastSeenAtMs = lastSeenAtMs?.int64Value, let transport = lastTransportText else {
            return statusText
        }
        let lastSeenAt = Date(timeIntervalSince1970: TimeInterval(lastSeenAtMs) / 1000)
        let formatter = RelativeDateTimeFormatter()
        formatter.locale = locale
        formatter.unitsStyle = .abbreviated
        return L10n.appLinkDeviceLastSeen(formatter.localizedString(for: lastSeenAt, relativeTo: now), transport)
    }
}

/// The SF Symbol of the platform a device record names.
func keyguardPlatformSymbol(_ platform: String) -> String {
    switch platform {
    case "android": "smartphone"
    case "ios": "iphone"
    case "wear": "applewatch"
    case "macos": "laptopcomputer"
    case "windows": "pc"
    case "linux", "desktop": "desktopcomputer"
    default: "laptopcomputer.and.iphone"
    }
}

/// The color of a sync status: up to date, behind, failing, or offline.
struct KeyguardStatusDot: View {
    let status: KeyguardDevicesSnapshot.Status

    var body: some View {
        Circle()
            .fill(color)
            .frame(width: 10, height: 10)
            .accessibilityHidden(true)
    }

    private var color: Color { status.color }
}

extension KeyguardDevicesSnapshot.Status {
    /// The color of the status: up to date, behind, failing, or offline.
    var color: Color {
        switch self {
        case .ok: .green
        case .warning: .orange
        case .error: .red
        default: .secondary
        }
    }
}
