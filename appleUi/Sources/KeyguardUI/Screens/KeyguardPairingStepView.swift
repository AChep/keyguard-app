import CoreImage
import SwiftUI
import KeyguardShared
#if os(macOS)
import AppKit
import UniformTypeIdentifiers
#endif

/// A Keyguard pairing: the stage, and under it the panel of the step, or the `idle`
/// content while there is no step yet. A new kind of step blurs in; an update of the
/// same step does not move.
struct KeyguardPairingFlowView<Idle: View, DoneActions: View>: View {
    let model: KeyguardPairingModel
    /// Warns that a shown code links another device.
    var sensitiveCode = false
    @ViewBuilder var idle: () -> Idle
    /// What the user does once the devices are linked.
    @ViewBuilder var doneActions: () -> DoneActions

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    /// The data travels from the device of the link to the one that joins it; only the
    /// comparison of the digits tells which this is.
    @State private var sending = true

    private var snapshot: KeyguardPairingSnapshot { model.snapshot }

    var body: some View {
        VStack(spacing: 16) {
            PairingStageView(phase: PairingStagePhase(snapshot.stage), sending: sending)
            Group {
                if let step = snapshot.step {
                    KeyguardPairingStepView(
                        model: model,
                        step: step,
                        sensitiveCode: sensitiveCode,
                        doneActions: doneActions
                    )
                } else {
                    idle()
                }
            }
            .id(stepKey)
            .transition(reduceMotion ? AnyTransition.opacity : AnyTransition(.blurReplace))
        }
        .animation(reduceMotion ? nil : .spring(duration: 0.45, bounce: 0.15), value: stepKey)
        .onChange(of: stepKey) { _, _ in
            if let verify = snapshot.step as? KeyguardPairingStepSnapshot.Verify {
                sending = !verify.isJoiner
            } else if let entry = snapshot.step as? KeyguardPairingStepSnapshot.EnterSas {
                sending = !entry.isJoiner
            }
        }
    }

    /// The kind of the step.
    private var stepKey: String {
        snapshot.step.map { String(describing: type(of: $0)) } ?? "idle"
    }
}

/// Shows a step of a Keyguard pairing: the code to show or to scan, the digits to
/// compare, the progress, or the result.
struct KeyguardPairingStepView<DoneActions: View>: View {
    let model: KeyguardPairingModel
    let step: KeyguardPairingStepSnapshot
    /// Warns that a shown code links another device.
    var sensitiveCode = false
    @ViewBuilder var doneActions: () -> DoneActions

    var body: some View {
        switch step {
        case let showCode as KeyguardPairingStepSnapshot.ShowCode:
            PairingCodePanel(model: model, step: showCode, sensitive: sensitiveCode)
        case let scan as KeyguardPairingStepSnapshot.Scan:
            PairingScanPanel(model: model, step: scan)
        case let entry as KeyguardPairingStepSnapshot.EnterSas:
            PairingSasEntryPanel(model: model, step: entry)
        case let verify as KeyguardPairingStepSnapshot.Verify:
            PairingSasPanel(model: model, step: verify)
        case let done as KeyguardPairingStepSnapshot.Done:
            PairingDonePanel(step: done, actions: doneActions)
        case let failed as KeyguardPairingStepSnapshot.Failed:
            PairingFailedPanel(model: model, step: failed)
        case is KeyguardPairingStepSnapshot.Connecting:
            PairingWaitingPanel(text: L10n.appLinkPairingConnectingText)
        case is KeyguardPairingStepSnapshot.Transferring:
            PairingWaitingPanel(text: L10n.appLinkPairingTransferringText)
        default:
            PairingWaitingPanel(text: L10n.appLinkPairingStartingText)
        }
    }
}

// MARK: - Show a code

private struct PairingCodePanel: View {
    let model: KeyguardPairingModel
    let step: KeyguardPairingStepSnapshot.ShowCode
    let sensitive: Bool

    var body: some View {
        if step.expired {
            PairingErrorPanel(
                message: step.errorText ?? L10n.appLinkPairingErrorExpired,
                actionTitle: L10n.appLinkPairingNewCode,
                action: model.newCode
            )
        } else {
            VStack(spacing: 16) {
                Text(L10n.appLinkPairingShowCodeText)
                    .font(.callout)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: .infinity)
                PairingCodeRing(code: step.code, expiresAtMs: step.expiresAtMs)
                PairingCountdown(expiresAtMs: step.expiresAtMs)
                Text(step.code)
                    .font(.caption.monospaced())
                    .foregroundStyle(.secondary)
                    .lineLimit(3)
                    .truncationMode(.middle)
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Button {
                    model.copyCode()
                } label: {
                    Label(L10n.appLinkPairingCopyCode, systemImage: "doc.on.doc")
                }
                .buttonStyle(.bordered)
                if sensitive {
                    PairingNote(text: L10n.appLinkPairingCodeSensitiveNote, systemImage: "exclamationmark.shield")
                }
            }
        }
    }
}

/// The code, framed by a ring that runs out with it from twelve o'clock, and warns once
/// it is almost gone.
private struct PairingCodeRing: View {
    let code: String
    let expiresAtMs: Int64
    /// The code does not say when it was made: its life starts once the screen shows it.
    @State private var total: TimeInterval = 1

    private static let size: CGFloat = 220
    private static let inset: CGFloat = 12

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { context in
            let left = remaining(at: context.date)
            let fraction = min(1, max(0, left / total))
            ZStack {
                TopStartRoundedRectangle(cornerRadius: 28)
                    .stroke(.quaternary, lineWidth: 4)
                TopStartRoundedRectangle(cornerRadius: 28)
                    .trim(from: 0, to: fraction)
                    .stroke(color(left), style: StrokeStyle(lineWidth: 4, lineCap: .round))
                    .animation(.linear(duration: 1), value: fraction)
                QRCodeCard(text: code, size: Self.size)
            }
            .frame(width: Self.size + 24 + Self.inset * 2, height: Self.size + 24 + Self.inset * 2)
        }
        .task(id: code) {
            total = max(1, remaining(at: .now))
        }
    }

    private func remaining(at date: Date) -> TimeInterval {
        let expiresAt = Date(timeIntervalSince1970: TimeInterval(expiresAtMs) / 1000)
        return max(0, expiresAt.timeIntervalSince(date))
    }

    private func color(_ left: TimeInterval) -> Color {
        if left < 10 { return .red }
        if left < 30 { return .orange }
        return .accentColor
    }
}

/// A rounded rectangle whose outline starts at the middle of the top edge and runs
/// clockwise, so that trimming it reads as a countdown.
private struct TopStartRoundedRectangle: Shape {
    let cornerRadius: CGFloat

    func path(in rect: CGRect) -> Path {
        let r = min(cornerRadius, min(rect.width, rect.height) / 2)
        var path = Path()
        path.move(to: CGPoint(x: rect.midX, y: rect.minY))
        path.addLine(to: CGPoint(x: rect.maxX - r, y: rect.minY))
        path.addArc(
            center: CGPoint(x: rect.maxX - r, y: rect.minY + r), radius: r,
            startAngle: .degrees(-90), endAngle: .degrees(0), clockwise: false)
        path.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY - r))
        path.addArc(
            center: CGPoint(x: rect.maxX - r, y: rect.maxY - r), radius: r,
            startAngle: .degrees(0), endAngle: .degrees(90), clockwise: false)
        path.addLine(to: CGPoint(x: rect.minX + r, y: rect.maxY))
        path.addArc(
            center: CGPoint(x: rect.minX + r, y: rect.maxY - r), radius: r,
            startAngle: .degrees(90), endAngle: .degrees(180), clockwise: false)
        path.addLine(to: CGPoint(x: rect.minX, y: rect.minY + r))
        path.addArc(
            center: CGPoint(x: rect.minX + r, y: rect.minY + r), radius: r,
            startAngle: .degrees(180), endAngle: .degrees(270), clockwise: false)
        path.closeSubpath()
        return path
    }
}

/// The time left until the code expires, as `m:ss`.
private struct PairingCountdown: View {
    let expiresAtMs: Int64

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { context in
            Label(L10n.appLinkPairingExpiresIn(remainingText(at: context.date)), systemImage: "timer")
                .font(.callout.monospacedDigit())
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(.quaternary.opacity(0.6), in: Capsule())
        }
    }

    private func remainingText(at date: Date) -> String {
        let expiresAt = Date(timeIntervalSince1970: TimeInterval(expiresAtMs) / 1000)
        let seconds = max(0, Int(expiresAt.timeIntervalSince(date).rounded(.down)))
        return String(format: "%d:%02d", seconds / 60, seconds % 60)
    }
}

// MARK: - Scan a code

private struct PairingScanPanel: View {
    let model: KeyguardPairingModel
    let step: KeyguardPairingStepSnapshot.Scan
    @State private var scanning = false
    @State private var imageError: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(L10n.appLinkPairingScanText)
                .font(.callout)
            sources
            if let imageError {
                PairingErrorText(text: imageError)
            }
            KeyguardFormField(
                field: step.code,
                title: L10n.appLinkPairingPasteCodeLabel,
                // The code must reach the producer exactly as typed.
                verbatim: true,
                submitLabel: .go,
                send: { model.setCode(text: $0) }
            )
            LoginPrimaryButton(
                title: L10n.continue,
                isLoading: false,
                enabled: step.canSubmit,
                action: model.submitCode
            )
        }
    }

    @ViewBuilder
    private var sources: some View {
        #if os(iOS)
        Button {
            scanning = true
        } label: {
            Label(L10n.scanqrTitle, systemImage: "qrcode.viewfinder")
                .frame(maxWidth: .infinity)
        }
        .buttonStyle(.bordered)
        .controlSize(.large)
        .sheet(isPresented: $scanning) {
            QRScannerView { value in
                model.submitScannedCode(text: value)
            }
        }
        #else
        Button {
            loadImage()
        } label: {
            Label(L10n.scanqrLoadFromImage, systemImage: "photo")
                .frame(maxWidth: .infinity)
        }
        .buttonStyle(.bordered)
        .controlSize(.large)
        .help(L10n.scanqrLoadFromImageNote)
        #endif
    }

    #if os(macOS)
    private func loadImage() {
        let panel = NSOpenPanel()
        panel.allowsMultipleSelection = false
        panel.canChooseDirectories = false
        panel.canChooseFiles = true
        panel.allowedContentTypes = [.image]
        guard panel.runModal() == .OK, let url = panel.url else { return }
        if let code = PairingQRImageReader.code(in: url) {
            imageError = nil
            model.submitScannedCode(text: code)
        } else {
            imageError = L10n.appLinkPairingErrorInvalidCode
        }
    }
    #endif
}

#if os(macOS)
/// Reads the text of the first QR code in an image file.
enum PairingQRImageReader {
    static func code(in url: URL) -> String? {
        guard let image = CIImage(contentsOf: url) else { return nil }
        let detector = CIDetector(
            ofType: CIDetectorTypeQRCode,
            context: nil,
            options: [CIDetectorAccuracy: CIDetectorAccuracyHigh]
        )
        return detector?.features(in: image)
            .compactMap { ($0 as? CIQRCodeFeature)?.messageString }
            .first
    }
}
#endif

// MARK: - Check the digits

/// The digits both devices must agree on. Until its user confirmed, this device scanned
/// the code: the user enters the digits on the other device, then confirms that both show
/// the same, or rejects. Once confirmed, it shows the digits as matching and waits for
/// the other device.
private struct PairingSasPanel: View {
    let model: KeyguardPairingModel
    let step: KeyguardPairingStepSnapshot.Verify

    var body: some View {
        VStack(spacing: 16) {
            if step.confirmed {
                Label(L10n.appLinkPairingSasMatchedTitle, systemImage: "checkmark.circle.fill")
                    .font(.headline)
                    .foregroundStyle(.green)
            } else {
                Text(L10n.appLinkPairingSasTitle)
                    .font(.headline)
                    .multilineTextAlignment(.center)
            }
            KeyguardPairingSasDigits(sas: step.sasFormatted, matched: step.confirmed)
            Text(step.peerLabel)
                .font(.subheadline.weight(.medium))
                .foregroundStyle(.secondary)
                .lineLimit(1)
            Text(text)
                .font(.callout)
                .multilineTextAlignment(.center)
            HStack(spacing: 12) {
                Button(role: .destructive, action: model.reject) {
                    Text(step.confirmed ? L10n.cancel : L10n.no)
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                if step.canApprove {
                    Button(action: model.approve) {
                        Text(L10n.appLinkPairingSasMatchYes)
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .layoutPriority(1)
                }
            }
            .controlSize(.large)
        }
        .frame(maxWidth: .infinity)
    }

    private var text: String {
        if !step.confirmed { return L10n.appLinkPairingSasCompareText }
        // The user of the other device still confirms there.
        return step.entered ? L10n.appLinkPairingSasMatchedText : L10n.appLinkPairingSasWaitingText
    }
}

/// Asks to type the digits the other device shows: this device showed the code. A wrong
/// entry shows an error; the user types again, or cancels.
private struct PairingSasEntryPanel: View {
    let model: KeyguardPairingModel
    let step: KeyguardPairingStepSnapshot.EnterSas

    var body: some View {
        VStack(spacing: 16) {
            VStack(spacing: 8) {
                Text(L10n.appLinkPairingSasEnterTitle)
                    .font(.headline)
                    .multilineTextAlignment(.center)
                Text(step.peerLabel)
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity)
            KeyguardFormField(
                field: step.digits,
                title: L10n.appLinkPairingSasEnterLabel,
                numeric: true,
                submitLabel: .go,
                send: { model.setDigits(text: $0) }
            )
            .onSubmit { model.submitDigits() }
            HStack(spacing: 12) {
                Button(role: .cancel, action: model.reject) {
                    Text(L10n.cancel)
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                Button(action: model.submitDigits) {
                    Text(L10n.continue)
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .disabled(!step.canSubmit)
                .layoutPriority(1)
            }
            .controlSize(.large)
        }
    }
}

/// The six digits both devices must agree on, as two groups of tiles. Each tile rolls
/// through a few digits before it lands on its own, one after another, with a tick.
private struct KeyguardPairingSasDigits: View {
    let sas: String
    /// Shows the digits as matching the ones of the other device.
    var matched = false

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var shown: [Int] = []
    @State private var landed = 0

    private var digits: [Int] { sas.compactMap(\.wholeNumberValue) }

    var body: some View {
        HStack(spacing: 6) {
            ForEach(Array(digits.indices), id: \.self) { index in
                if index > 0, index % 3 == 0 {
                    Spacer()
                        .frame(width: 10)
                }
                tile(index)
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(digits.map(String.init).joined(separator: " "))
        .sensoryFeedback(.selection, trigger: landed)
        .task(id: sas) {
            await roll()
        }
    }

    private func tile(_ index: Int) -> some View {
        let value = index < shown.count ? shown[index] : digits[index]
        let isLanded = reduceMotion || index < landed
        return Text(String(value))
            .font(.system(size: 34, weight: .semibold, design: .monospaced))
            .contentTransition(.numericText(value: Double(value)))
            .frame(width: 44, height: 60)
            .background(
                (matched ? Color.green : Color.accentColor).opacity(0.14),
                in: RoundedRectangle(cornerRadius: 14, style: .continuous)
            )
            .scaleEffect(isLanded ? 1 : 0.86)
            .animation(.bouncy(duration: 0.35, extraBounce: 0.2), value: isLanded)
    }

    /// Rolls the tiles in, a few frames apart.
    private func roll() async {
        let final = digits
        guard !reduceMotion else {
            shown = final
            landed = final.count
            return
        }
        let ticks = 5
        let stagger = 2
        shown = final.indices.map { (final[$0] + $0 + 1) % 10 }
        landed = 0
        for frame in 0...(ticks + (final.count - 1) * stagger) {
            var next = shown
            var nextLanded = landed
            for index in final.indices {
                let local = frame - index * stagger
                if local >= 0, local < ticks {
                    next[index] = (final[index] + local * 3 + index + 1) % 10
                } else if local == ticks {
                    next[index] = final[index]
                    nextLanded = max(nextLanded, index + 1)
                }
            }
            withAnimation(.snappy(duration: 0.1)) {
                shown = next
            }
            landed = nextLanded
            try? await Task.sleep(for: .milliseconds(40))
            if Task.isCancelled { return }
        }
    }
}

// MARK: - Progress and results

private struct PairingWaitingPanel: View {
    let text: String

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        VStack(spacing: 12) {
            // The stage shows the progress, unless it holds still.
            if reduceMotion {
                ProgressView()
            }
            Text(text)
                .font(.body)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 8)
    }
}

/// The devices are linked: says with which one, then what to do next.
private struct PairingDonePanel<Actions: View>: View {
    let step: KeyguardPairingStepSnapshot.Done
    @ViewBuilder var actions: () -> Actions

    var body: some View {
        VStack(spacing: 24) {
            VStack(spacing: 4) {
                Text(L10n.appLinkPairingDoneTitle)
                    .font(.title2.weight(.semibold))
                if let peerLabel = step.peerLabel {
                    Text(peerLabel)
                        .font(.headline)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }
            .multilineTextAlignment(.center)
            .accessibilityElement(children: .combine)
            actions()
        }
        .frame(maxWidth: .infinity)
    }
}

private struct PairingFailedPanel: View {
    let model: KeyguardPairingModel
    let step: KeyguardPairingStepSnapshot.Failed

    var body: some View {
        let retry: (@MainActor () -> Void)?
        if step.canRetry {
            retry = { [model] in model.retry() }
        } else {
            retry = nil
        }
        return PairingErrorPanel(
            message: step.errorText,
            actionTitle: L10n.retry,
            action: retry
        )
    }
}

/// Why a pairing failed, as the passkey screens say it: a warning, a title, what went
/// wrong, and the way on.
private struct PairingErrorPanel: View {
    let message: String
    let actionTitle: String
    let action: (@MainActor () -> Void)?

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Image(systemName: "exclamationmark.triangle.fill")
                .font(.system(size: 44))
                .foregroundStyle(.red)
                .accessibilityHidden(true)
                .padding(.bottom, 8)
            Text(L10n.appLinkPairingFailedTitle)
                .font(.title2)
            Text(message)
                .font(.body)
                .textSelection(.enabled)
            if let action {
                HStack {
                    Spacer()
                    Button(actionTitle, action: action)
                        .buttonStyle(.borderedProminent)
                        .controlSize(.large)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct PairingErrorText: View {
    let text: String

    var body: some View {
        PairingNote(text: text, systemImage: "exclamationmark.triangle.fill")
            .foregroundStyle(.red)
    }
}

private struct PairingNote: View {
    let text: String
    let systemImage: String

    var body: some View {
        Label {
            Text(text)
                .foregroundStyle(.primary)
                .frame(maxWidth: .infinity, alignment: .leading)
        } icon: {
            Image(systemName: systemImage)
        }
        .font(.callout)
        .padding(12)
        .background(.quaternary.opacity(0.5), in: RoundedRectangle(cornerRadius: 8))
    }
}
