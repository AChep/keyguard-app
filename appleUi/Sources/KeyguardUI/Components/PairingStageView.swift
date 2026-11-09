import SwiftUI
import KeyguardShared
#if canImport(UIKit)
import UIKit
#endif

/// What the stage of a pairing shows, as `KeyguardPairingSnapshot.Stage`.
enum PairingStagePhase: Equatable {
    /// Nothing happens yet: this device waits for another one.
    case idle
    case starting
    /// This device shows a code: it calls out to the other one.
    case showCode
    /// This device scans the code of the other one.
    case scan
    /// The devices shake hands.
    case connecting
    /// The devices met: compare the digits.
    case verify
    /// The data travels to the device that joins.
    case transferring
    /// The devices are one link now.
    case done
    case failed

    init(_ stage: KeyguardPairingSnapshot.Stage) {
        switch stage {
        case .starting: self = .starting
        case .showCode: self = .showCode
        case .scan: self = .scan
        case .connecting: self = .connecting
        case .verify: self = .verify
        case .transferring: self = .transferring
        case .done: self = .done
        case .failed: self = .failed
        default: self = .idle
        }
    }
}

/// The stage of a pairing: this device and the other one as cookie shapes, and the link
/// between them. It calls out while showing a code, scans, shakes hands, carries the data
/// and merges the two devices into one once they are linked. Decoration: the steps say
/// the same in words. Still while Reduce Motion is on.
struct PairingStageView: View {
    let phase: PairingStagePhase
    /// `true` while the data travels from this device to the other one.
    var sending = true
    var thisSymbol = PairingStageView.thisDeviceSymbol
    var height: CGFloat = 176

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var values = PairingStageValues.target(for: .idle)
    @State private var failures = 0

    var body: some View {
        TimelineView(.animation(paused: reduceMotion)) { context in
            PairingStageCanvas(
                values: values,
                time: reduceMotion ? 0 : context.date.timeIntervalSinceReferenceDate,
                sending: sending,
                thisSymbol: thisSymbol
            )
        }
        .frame(height: height)
        .frame(maxWidth: .infinity)
        .keyframeAnimator(initialValue: 0.0, trigger: failures) { content, offset in
            content.offset(x: offset)
        } keyframes: { _ in
            KeyframeTrack {
                CubicKeyframe(-14, duration: 0.06)
                CubicKeyframe(12, duration: 0.07)
                CubicKeyframe(-9, duration: 0.07)
                CubicKeyframe(6, duration: 0.07)
                CubicKeyframe(-3, duration: 0.07)
                CubicKeyframe(0, duration: 0.08)
            }
        }
        .sensoryFeedback(.success, trigger: phase) { _, new in new == .done }
        .sensoryFeedback(.error, trigger: phase) { _, new in new == .failed }
        .accessibilityHidden(true)
        .onAppear {
            values = PairingStageValues.target(for: phase)
            if phase == .done {
                values.check = 1
            }
        }
        .onChange(of: phase) { _, new in
            apply(new)
        }
    }

    private func apply(_ phase: PairingStagePhase) {
        var target = PairingStageValues.target(for: phase)
        target.check = 0
        target.burst = 0
        withAnimation(reduceMotion ? nil : .spring(duration: 0.8, bounce: 0.2)) {
            values = target
        }
        switch phase {
        case .done:
            withAnimation(reduceMotion ? nil : .bouncy(duration: 0.6, extraBounce: 0.25).delay(0.38)) {
                values.check = 1
            }
            if !reduceMotion {
                withAnimation(.easeOut(duration: 1.1).delay(0.38)) {
                    values.burst = 1
                }
            }
        case .failed:
            if !reduceMotion {
                failures += 1
            }
        default:
            break
        }
    }

    /// The symbol of this device.
    static var thisDeviceSymbol: String {
        #if os(macOS)
        "laptopcomputer"
        #else
        UIDevice.current.userInterfaceIdiom == .pad ? "ipad" : "iphone"
        #endif
    }
}

/// How far each part of the stage is, from 0 to 1, so that SwiftUI animates them together.
struct PairingStageValues: VectorArithmetic {
    private var storage = SIMD16<Double>()

    static var zero: PairingStageValues { PairingStageValues() }

    static func + (lhs: PairingStageValues, rhs: PairingStageValues) -> PairingStageValues {
        var result = lhs
        result.storage += rhs.storage
        return result
    }

    static func - (lhs: PairingStageValues, rhs: PairingStageValues) -> PairingStageValues {
        var result = lhs
        result.storage -= rhs.storage
        return result
    }

    mutating func scale(by rhs: Double) {
        storage *= rhs
    }

    var magnitudeSquared: Double { (storage * storage).sum() }

    /// How far apart the devices are: 1 is apart, 0 together.
    var separation: Double {
        get { storage[0] }
        set { storage[0] = newValue }
    }
    /// 0 while the other device is a ghost, 1 once it is there.
    var peer: Double {
        get { storage[1] }
        set { storage[1] = newValue }
    }
    /// 0 while the link is dashed, 1 once it is solid.
    var solid: Double {
        get { storage[2] }
        set { storage[2] = newValue }
    }
    var wave: Double {
        get { storage[3] }
        set { storage[3] = newValue }
    }
    var broadcast: Double {
        get { storage[4] }
        set { storage[4] = newValue }
    }
    var scan: Double {
        get { storage[5] }
        set { storage[5] = newValue }
    }
    var handshake: Double {
        get { storage[6] }
        set { storage[6] = newValue }
    }
    var transfer: Double {
        get { storage[7] }
        set { storage[7] = newValue }
    }
    var breathe: Double {
        get { storage[8] }
        set { storage[8] = newValue }
    }
    var failure: Double {
        get { storage[9] }
        set { storage[9] = newValue }
    }
    var merge: Double {
        get { storage[10] }
        set { storage[10] = newValue }
    }
    var check: Double {
        get { storage[11] }
        set { storage[11] = newValue }
    }
    var burst: Double {
        get { storage[12] }
        set { storage[12] = newValue }
    }

    static func target(for phase: PairingStagePhase) -> PairingStageValues {
        var values = PairingStageValues()
        values.separation =
            switch phase {
            case .done: 0
            case .failed: 1.15
            default: 1
            }
        values.peer =
            switch phase {
            case .idle, .starting, .showCode, .scan: 0
            case .connecting: 0.6
            default: 1
            }
        values.solid = [.verify, .transferring, .done].contains(phase) ? 1 : 0
        values.wave =
            switch phase {
            case .connecting: 1
            case .transferring: 0.3
            default: 0
            }
        values.broadcast = phase == .showCode ? 1 : 0
        values.scan = phase == .scan ? 1 : 0
        values.handshake = phase == .connecting ? 1 : 0
        values.transfer = phase == .transferring ? 1 : 0
        values.breathe = [.idle, .starting, .showCode, .scan].contains(phase) ? 1 : 0
        values.failure = phase == .failed ? 1 : 0
        values.merge = phase == .done ? 1 : 0
        return values
    }
}

/// Draws one frame of the stage at `time`, with the `values` SwiftUI animates.
private struct PairingStageCanvas: View, Animatable {
    var values: PairingStageValues
    var time: TimeInterval
    var sending: Bool
    var thisSymbol: String

    var animatableData: PairingStageValues {
        get { values }
        set { values = newValue }
    }

    private static let thisColor = Color.accentColor
    private static let peerColor = Color.indigo
    private static let confetti: [Color] = [.accentColor, .indigo, .mint, .orange]
    private static let particles = PairingParticle.burst(count: 18)

    var body: some View {
        Canvas { context, size in
            draw(in: &context, size: size)
        } symbols: {
            Image(systemName: thisSymbol)
                .font(.system(size: 26, weight: .medium))
                .foregroundStyle(Self.thisColor)
                .tag(Symbol.this)
            Image(systemName: "laptopcomputer.and.iphone")
                .font(.system(size: 22, weight: .medium))
                .foregroundStyle(Self.peerColor)
                .tag(Symbol.peer)
            Image(systemName: "checkmark")
                .font(.system(size: 40, weight: .bold))
                .foregroundStyle(.white)
                .tag(Symbol.check)
        }
    }

    private enum Symbol: Hashable {
        case this, peer, check
    }

    /// One frame: each part reads its value and the clock.
    private func draw(in context: inout GraphicsContext, size: CGSize) {
        let radius: CGFloat = 38
        let center = CGPoint(x: size.width / 2, y: size.height / 2)
        let halfDistance = min(size.width * 0.27, 112) * values.separation
        let left = CGPoint(x: center.x - halfDistance, y: center.y)
        let right = CGPoint(x: center.x + halfDistance, y: center.y)
        let loop = (time / 2.4).truncatingRemainder(dividingBy: 1)
        let spin = Angle.radians(time / 28 * 2 * .pi)
        let breathe = 1 + 0.04 * values.breathe * sin(2 * .pi * loop)
        let nodes = 1 - smoothStep(0.55, 0.95, values.merge)

        // This device calls out to the other one.
        if values.broadcast > 0 {
            for i in 0..<3 {
                let t = (loop + Double(i) / 3).truncatingRemainder(dividingBy: 1)
                let echo = cookiePath(
                    center: left, radius: radius * (1 + 1.1 * t), lobes: 9, depth: 0.14, rotation: spin)
                context.stroke(
                    echo,
                    with: .color(Self.thisColor.opacity(values.broadcast * (1 - t) * (1 - t) * 0.7 * nodes)),
                    lineWidth: 2
                )
            }
        }

        // The link, from the edge of one device to the other.
        let start = CGPoint(x: left.x + radius, y: center.y)
        let end = CGPoint(x: right.x - radius, y: center.y)
        if end.x - start.x > 4, nodes > 0 {
            let link = PairingLink(start: start, end: end, amplitude: values.wave * 9, phase: loop)
            let gap = values.failure * 0.55
            let dashed = StrokeStyle(lineWidth: 2, lineCap: .round, dash: [6, 6], dashPhase: -loop * 24)
            let solidStyle = StrokeStyle(lineWidth: 3, lineCap: .round)
            let idle = (1 - values.solid) * (1 - values.failure) * nodes
            let solid = values.solid * (1 - values.failure) * nodes
            context.stroke(link.path(gap: gap), with: .color(.secondary.opacity(idle)), style: dashed)
            context.stroke(link.path(gap: gap), with: .color(Self.thisColor.opacity(solid)), style: solidStyle)
            context.stroke(link.path(gap: gap), with: .color(.red.opacity(values.failure)), style: solidStyle)

            // Both devices talk while they shake hands.
            drawDot(&context, at: link.point(loop), color: Self.thisColor, alpha: values.handshake * sin(.pi * loop))
            drawDot(
                &context, at: link.point(1 - loop), color: Self.peerColor, alpha: values.handshake * sin(.pi * loop))
            // The data travels to the device that joins.
            for i in 0..<4 {
                let t = (loop * 2 + Double(i) / 4).truncatingRemainder(dividingBy: 1)
                let at = link.point(sending ? t : 1 - t)
                drawDot(&context, at: at, color: Self.thisColor, alpha: values.transfer * sin(.pi * t), radius: 5)
            }
        }

        // This device scans the code of the other one.
        if values.scan > 0 {
            drawViewfinder(
                &context, center: right, half: radius * 1.45 * breathe, sweep: loop, alpha: values.scan * nodes)
        }

        // The other device: a dashed ghost until it is there.
        let peerPath = cookiePath(
            center: right, radius: radius * breathe, lobes: 7, depth: 0.14, rotation: .radians(-spin.radians))
        context.stroke(
            peerPath,
            with: .color(.secondary.opacity((1 - values.peer) * nodes)),
            style: StrokeStyle(lineWidth: 2, dash: [5, 5], dashPhase: -loop * 20)
        )
        let filledPeer = cookiePath(
            center: right, radius: radius * (0.7 + 0.3 * values.peer) * breathe, lobes: 7, depth: 0.14,
            rotation: .radians(-spin.radians))
        context.fill(filledPeer, with: .color(Self.peerColor.opacity(0.2 * values.peer * nodes)))
        context.fill(filledPeer, with: .color(.red.opacity(0.25 * values.failure * values.peer * nodes)))

        // This device.
        let thisPath = cookiePath(center: left, radius: radius * breathe, lobes: 9, depth: 0.14, rotation: spin)
        context.fill(thisPath, with: .color(Self.thisColor.opacity(0.22 * nodes)))

        if let symbol = context.resolveSymbol(id: Symbol.this) {
            draw(&context, symbol, at: left, scale: breathe, alpha: 1 - smoothStep(0.3, 0.7, values.merge))
        }
        if let symbol = context.resolveSymbol(id: Symbol.peer) {
            let alpha = (0.45 + 0.55 * values.peer) * (1 - smoothStep(0.3, 0.7, values.merge))
            draw(&context, symbol, at: right, scale: 1, alpha: alpha)
        }

        // Linked: the two devices are one.
        if values.merge > 0 {
            let progress = smoothStep(0.4, 1, values.merge)
            let merged = cookiePath(
                center: center, radius: radius + 18 * progress, lobes: 9, depth: 0.14 * (1 - progress), rotation: spin)
            context.fill(merged, with: .color(Self.thisColor.opacity(smoothStep(0.45, 0.75, values.merge))))
        }
        if values.check > 0, let symbol = context.resolveSymbol(id: Symbol.check) {
            draw(&context, symbol, at: center, scale: values.check, alpha: min(1, values.check))
        }
        drawBurst(&context, center: center, progress: values.burst)
    }

    private func draw(
        _ context: inout GraphicsContext,
        _ symbol: GraphicsContext.ResolvedSymbol,
        at point: CGPoint,
        scale: Double,
        alpha: Double
    ) {
        guard alpha > 0, scale > 0 else { return }
        context.drawLayer { layer in
            layer.opacity = alpha
            layer.translateBy(x: point.x, y: point.y)
            layer.scaleBy(x: scale, y: scale)
            layer.draw(symbol, at: .zero)
        }
    }

    private func drawDot(
        _ context: inout GraphicsContext,
        at point: CGPoint,
        color: Color,
        alpha: Double,
        radius: CGFloat = 4
    ) {
        guard alpha > 0 else { return }
        let rect = CGRect(x: point.x - radius, y: point.y - radius, width: radius * 2, height: radius * 2)
        context.fill(Path(ellipseIn: rect), with: .color(color.opacity(alpha)))
    }

    private func drawViewfinder(
        _ context: inout GraphicsContext,
        center: CGPoint,
        half: CGFloat,
        sweep: Double,
        alpha: Double
    ) {
        let corner = half * 0.32
        var corners = Path()
        for (x, y) in [(-1.0, -1.0), (1.0, -1.0), (-1.0, 1.0), (1.0, 1.0)] {
            let point = CGPoint(x: center.x + half * x, y: center.y + half * y)
            corners.move(to: CGPoint(x: point.x - corner * x, y: point.y))
            corners.addLine(to: point)
            corners.addLine(to: CGPoint(x: point.x, y: point.y - corner * y))
        }
        context.stroke(
            corners,
            with: .color(Self.thisColor.opacity(alpha)),
            style: StrokeStyle(lineWidth: 3, lineCap: .round, lineJoin: .round)
        )
        // Back and forth.
        let y = center.y - half + 2 * half * (1 - abs(2 * sweep - 1))
        var line = Path()
        line.move(to: CGPoint(x: center.x - half, y: y))
        line.addLine(to: CGPoint(x: center.x + half, y: y))
        context.stroke(
            line,
            with: .linearGradient(
                Gradient(colors: [.clear, Self.thisColor.opacity(alpha), .clear]),
                startPoint: CGPoint(x: center.x - half, y: y),
                endPoint: CGPoint(x: center.x + half, y: y)
            ),
            style: StrokeStyle(lineWidth: 2, lineCap: .round)
        )
    }

    private func drawBurst(_ context: inout GraphicsContext, center: CGPoint, progress: Double) {
        guard progress > 0, progress < 1 else { return }
        for particle in Self.particles {
            let distance = 56 + 34 * particle.distance * progress
            let point = CGPoint(
                x: center.x + distance * cos(particle.angle),
                y: center.y + distance * sin(particle.angle)
            )
            let size = 7 * particle.size * (1 - 0.4 * progress)
            let shape = cookiePath(
                center: point, radius: size, lobes: particle.lobes, depth: 0.2,
                rotation: .degrees(particle.spin * progress))
            let color = Self.confetti[particle.color % Self.confetti.count]
            context.fill(shape, with: .color(color.opacity(1 - progress)))
        }
    }
}

/// A bit of confetti: where it flies, how big it is, how it spins, and its look.
struct PairingParticle {
    let angle: Double
    let distance: Double
    let size: Double
    let spin: Double
    let lobes: Int
    let color: Int

    /// The same `count` bits every time.
    static func burst(count: Int) -> [PairingParticle] {
        var seed: UInt64 = 7
        func next() -> Double {
            // A small linear congruential generator: the burst must look the same each time.
            seed = seed &* 6_364_136_223_846_793_005 &+ 1_442_695_040_888_963_407
            return Double(seed >> 11) / Double(1 << 53)
        }
        return (0..<count).map { i in
            PairingParticle(
                angle: 2 * .pi * Double(i) / Double(count) + (next() - 0.5) * 0.5,
                distance: 0.55 + next() * 0.45,
                size: 0.55 + next() * 0.45,
                spin: (next() - 0.5) * 540,
                lobes: [4, 6, 8, 12][i % 4],
                color: Int(next() * 4)
            )
        }
    }
}

/// A link from `start` to `end`: a wave of `amplitude` that travels with `phase`, pinned
/// at both ends.
struct PairingLink {
    let start: CGPoint
    let end: CGPoint
    var amplitude: Double = 0
    var phase: Double = 0
    var waves: Double = 2

    func point(_ t: Double) -> CGPoint {
        let dx = end.x - start.x
        let dy = end.y - start.y
        let length = max(1, (dx * dx + dy * dy).squareRoot())
        let offset = amplitude * sin(.pi * t) * sin(2 * .pi * (waves * t - phase))
        return CGPoint(x: start.x + dx * t - dy / length * offset, y: start.y + dy * t + dx / length * offset)
    }

    /// The link, cut in the middle by a `gap` in 0...1.
    func path(gap: Double = 0) -> Path {
        let half = min(0.5, max(0, gap / 2))
        let segments = half > 0 ? [(0.0, 0.5 - half), (0.5 + half, 1.0)] : [(0.0, 1.0)]
        var path = Path()
        for (from, to) in segments where to > from {
            let steps = 48
            path.move(to: point(from))
            for i in 1...steps {
                path.addLine(to: point(from + (to - from) * Double(i) / Double(steps)))
            }
        }
        return path
    }
}

/// A circle with `lobes` soft bumps of `depth`, the way Material draws its cookies. A
/// depth of 0 is a circle, so animating the depth morphs a cookie into a circle.
func cookiePath(center: CGPoint, radius: CGFloat, lobes: Int, depth: Double, rotation: Angle) -> Path {
    var path = Path()
    let steps = max(lobes * 12, 48)
    for i in 0...steps {
        let t = Double(i) / Double(steps) * 2 * .pi
        let r = radius * (1 - depth * 0.5 * (1 - cos(Double(lobes) * t)))
        let a = t + rotation.radians
        let point = CGPoint(x: center.x + r * cos(a), y: center.y + r * sin(a))
        if i == 0 {
            path.move(to: point)
        } else {
            path.addLine(to: point)
        }
    }
    path.closeSubpath()
    return path
}

/// A cookie that fits its frame, as a SwiftUI shape.
struct CookieShape: Shape {
    var lobes: Int
    var depth: Double = 0.14
    var rotation: Angle = .zero

    func path(in rect: CGRect) -> Path {
        cookiePath(
            center: CGPoint(x: rect.midX, y: rect.midY),
            radius: min(rect.width, rect.height) / 2,
            lobes: lobes,
            depth: depth,
            rotation: rotation
        )
    }
}

/// A smooth step from 0 at `from` to 1 at `to`.
func smoothStep(_ from: Double, _ to: Double, _ value: Double) -> Double {
    let t = min(1, max(0, (value - from) / (to - from)))
    return t * t * (3 - 2 * t)
}
