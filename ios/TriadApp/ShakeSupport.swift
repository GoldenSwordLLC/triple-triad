import SwiftUI
import TriadCore

/// Shake detection for the one-time menu reward. GAME-rate sampling (~50 Hz):
/// NORMAL's ~5 Hz misses transient peaks entirely. The callback fires on the main
/// thread; all state writes stay there.
#if canImport(UIKit)
struct ShakeModifier: ViewModifier {
    let enabled: Bool
    let makeCards: () -> [TriadCore.CardDef]
    @State private var detector: CMMotionShakeDetector?

    func body(content: Content) -> some View {
        content.onAppear {
            if enabled && detector == nil {
                detector = CMMotionShakeDetector { [makeCards] in
                    let cards = makeCards()
                    NotificationCenter.default.post(
                        name: .triadShakeDetected, object: cards)
                }
            }
        }
    }
}

extension Notification.Name {
    static let triadShakeDetected = Notification.Name("triadShakeDetected")
}

extension View {
    /// One-time shake reward: fires `onShake` with freshly drawn cards on the main thread.
    func onShake(enabled: Bool, makeCards: @escaping () -> [TriadCore.CardDef],
                 onShake: @escaping ([TriadCore.CardDef]) -> Void) -> some View {
        modifier(ShakeModifier(enabled: enabled, makeCards: makeCards))
            .onReceive(NotificationCenter.default.publisher(for: .triadShakeDetected)) { note in
                if let cards = note.object as? [TriadCore.CardDef] { onShake(cards) }
            }
    }
}
#endif

#if canImport(UIKit)
import UIKit
import CoreMotion

final class CMMotionShakeDetector: NSObject {
    private let motion = CMMotionManager()
    private var last = Date.distantPast
    private let handler: () -> Void

    init(handler: @escaping () -> Void) {
        self.handler = handler
        super.init()
        motion.accelerometerUpdateInterval = 1.0 / 50.0
        motion.startAccelerometerUpdates(to: .main) { [weak self] data, _ in
            guard let self, let v = data?.acceleration else { return }
            let mag = (v.x * v.x + v.y * v.y + v.z * v.z).squareRoot()
            let now = Date()
            if mag > 15 && now.timeIntervalSince(self.last) > 1.2 {
                self.last = now
                self.handler()
            }
        }
    }
    deinit { motion.stopAccelerometerUpdates() }
}
#endif
