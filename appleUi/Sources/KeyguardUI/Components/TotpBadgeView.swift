import SwiftUI
import KeyguardShared

struct TotpBadgeView: View {
    let totp: TotpFieldSnapshot
    var showsBackground = true
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        if totp.isLoading && !showsBackground {
            ProgressView().controlSize(.small)
        } else if totp.isError {
            errorContent
        } else {
            badgeContent
        }
    }

    private var badgeContent: some View {
        HStack(spacing: 8) {
            codeContent
                // Numeric groups must keep their order in an RTL form.
                .environment(\.layoutDirection, .leftToRight)
            if totp.isTimeBased {
                counterContent
            }
        }
        .padding(.leading, showsBackground ? 10 : 0)
        .padding(.trailing, showsBackground ? (totp.isTimeBased ? 6 : 10) : 0)
        .padding(.vertical, showsBackground ? 5 : 0)
        .background {
            if showsBackground {
                RoundedRectangle(cornerRadius: 8).fill(.quaternary)
            }
        }
    }

    private var codeContent: some View {
        HStack(spacing: 0) {
            if totp.groups.isEmpty {
                Text(verbatim: "••••••")
                    .font(showsBackground ? .title3.monospaced() : .body.monospaced())
                    .foregroundStyle(.tertiary)
            } else {
                ForEach(Array(totp.groups.enumerated()), id: \.offset) { index, group in
                    if index > 0 {
                        Circle()
                            .fill(.secondary)
                            .frame(width: 3, height: 3)
                            .padding(.horizontal, 5)
                    }
                    let text = group.joined()
                    Text(text)
                        .font(showsBackground ? .title3.monospaced() : .body.monospaced())
                        .contentTransition(reduceMotion ? .identity : .numericText())
                        .animation(reduceMotion ? nil : .spring(response: 0.4, dampingFraction: 0.9), value: text)
                        .textSelection(.enabled)
                }
            }
        }
    }

    private var counterContent: some View {
        let color = countdownColor(progress: totp.progress)
        let fraction = CGFloat(max(0, min(1, totp.progress)))
        return HStack(spacing: 5) {
            ZStack {
                Circle()
                    .stroke(color.opacity(0.25), lineWidth: 2)
                Circle()
                    .trim(from: 0, to: fraction)
                    .stroke(color, style: StrokeStyle(lineWidth: 2, lineCap: .round))
                    .rotationEffect(.degrees(-90))
            }
            .frame(width: 15, height: 15)
            .accessibilityHidden(true)
            .animation(reduceMotion ? nil : .linear(duration: 1), value: totp.progress)

            Text(totp.counterText)
                .font(.caption.weight(.bold).monospacedDigit())
                .foregroundStyle(color)
                .contentTransition(reduceMotion ? .identity : .numericText(countsDown: true))
                .animation(reduceMotion ? nil : .default, value: totp.counterText)
        }
        .padding(.leading, showsBackground ? 6 : 0)
        .padding(.trailing, showsBackground ? 8 : 0)
        .padding(.vertical, showsBackground ? 3 : 0)
        .background {
            if showsBackground {
                Capsule().fill(color.opacity(0.12))
            }
        }
    }

    private var errorContent: some View {
        HStack(spacing: 6) {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundStyle(.red)
            Text(L10n.errorInvalidKey)
                .foregroundStyle(.secondary)
        }
        .padding(.horizontal, showsBackground ? 10 : 0)
        .padding(.vertical, showsBackground ? 5 : 0)
        .background {
            if showsBackground {
                RoundedRectangle(cornerRadius: 8).fill(.quaternary)
            }
        }
    }

    // Mirrors the Compose AhContainer: fade accent -> red on a pow(0.4) curve as
    // the remaining fraction drops from 1 to 0.
    private func countdownColor(progress: Float) -> Color {
        let t = pow(Double(max(0, min(1, progress))), 0.4)
        let accent = PlatformColor.platformAccent.rgbaComponents()
        let danger = PlatformColor.platformDanger.rgbaComponents()
        func mix(_ a: CGFloat, _ b: CGFloat) -> CGFloat { b + (a - b) * CGFloat(t) }
        return Color(
            red: Double(mix(accent.red, danger.red)),
            green: Double(mix(accent.green, danger.green)),
            blue: Double(mix(accent.blue, danger.blue))
        )
    }
}
