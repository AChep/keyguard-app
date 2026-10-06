import SwiftUI

struct ToastStackView: View {
    @Environment(NotificationsModel.self) private var notificationsModel
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        VStack(spacing: 8) {
            ForEach(notificationsModel.toasts) { toast in
                ToastCardView(toast: toast) { notificationsModel.dismissToast(toast.id) }
            }
        }
        .animation(
            reduceMotion ? .easeInOut(duration: 0.2) : .spring(duration: 0.25),
            value: notificationsModel.toasts.map(\.id)
        )
        // Transient toasts are otherwise silent to assistive tech: post the newest
        // toast's text as a VoiceOver announcement so error/success feedback is
        // spoken even though the pill auto-dismisses before it can be focused.
        .onChange(of: notificationsModel.toasts.last?.id) { _, _ in
            if let line = notificationsModel.toasts.last?.line, !line.isEmpty {
                AccessibilityNotification.Announcement(line).post()
            }
        }
        // iOS renders centered HUD pills that size to their content, so the stack
        // is kept narrow; macOS keeps its wider top-anchored card.
        #if os(iOS)
        .frame(maxWidth: 360)
        #else
        .frame(maxWidth: 420)
        #endif
    }
}

private struct ToastCardView: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let toast: ToastItem
    let onDismiss: () -> Void

    private var iconName: String {
        switch toast.type {
        case "ERROR": return "exclamationmark.triangle.fill"
        case "SUCCESS": return "checkmark.circle.fill"
        default: return "info.circle.fill"
        }
    }

    private var iconColor: Color {
        switch toast.type {
        case "ERROR": return .red
        case "SUCCESS": return .green
        default: return .accentColor
        }
    }

    var body: some View {
        #if os(iOS)
        Button(action: onDismiss) {
            HStack(spacing: 8) {
                Image(systemName: iconName)
                    .foregroundStyle(iconColor)
                    .accessibilityHidden(true)
                Text(toast.line)
                    .font(.subheadline.weight(.medium))
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding(.vertical, 12)
            .padding(.horizontal, 18)
            .frame(minHeight: 44)
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
        .shadow(color: .black.opacity(0.18), radius: 16, y: 6)
        .transition(reduceMotion ? .opacity : .scale(scale: 0.9).combined(with: .opacity))
        // The pill is the only dismiss affordance (tap/drag); expose it to
        // VoiceOver as a single labelled, dismissable element rather than a
        // bare icon + text with no actionable hint.
        .accessibilityElement(children: .combine)
        .accessibilityLabel(toast.line)
        .accessibilityHint(L10n.close)
        .simultaneousGesture(
            DragGesture(minimumDistance: 12)
                .onEnded { value in
                    if abs(value.translation.height) > 24 || abs(value.translation.width) > 48 {
                        onDismiss()
                    }
                }
        )
        #else
        // macOS: top-anchored material card with a hairline border and an explicit
        // close button.
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: iconName)
                .foregroundStyle(iconColor)
            Text(toast.line)
                .font(.callout)
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
            Button(action: onDismiss) {
                Image(systemName: "xmark")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)
            }
            .buttonStyle(.plain)
            .help(L10n.close)
            .accessibilityLabel(L10n.close)
        }
        .padding(.vertical, 10)
        .padding(.horizontal, 14)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 10))
        .overlay(
            RoundedRectangle(cornerRadius: 10)
                .strokeBorder(.separator, lineWidth: 0.5)
        )
        .shadow(color: .black.opacity(0.12), radius: 8, y: 2)
        .frame(maxWidth: .infinity, alignment: .leading)
        .transition(reduceMotion ? .opacity : .move(edge: .top).combined(with: .opacity))
        #endif
    }
}

extension View {
    /// Attach to the active presentation surface so feedback stays above sheets.
    func appToastOverlay(isEnabled: Bool = true) -> some View {
        #if os(iOS)
        overlay(alignment: .center) {
            if isEnabled {
                ToastStackView()
            }
        }
        #else
        overlay(alignment: .top) {
            if isEnabled {
                ToastStackView()
                    .padding(.top, 12)
            }
        }
        #endif
    }
}
