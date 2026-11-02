import Foundation

// MARK: - Host identity (mirrors Kotlin RelayIdentity)

public protocol RelayIdentity {
    var pubkeyHex: String { get }                    // 64 hex chars (32 raw bytes)
    func sign(_ message: String) -> String           // 128 hex chars (64-byte ed25519 sig)
}

public enum RelayIdentityProto {
    public static func hostMessage(pubkeyHex: String, unixSeconds: Int64) -> String {
        "TRIAD-HOST|\(pubkeyHex)|\(unixSeconds)"
    }
    public static func pongMessage(code: String, nonce: String) -> String {
        "TRIAD-PONG|\(code)|\(nonce)"
    }
}

#if canImport(CryptoKit)
import CryptoKit

/// Apple platforms: the device keypair via CryptoKit Curve25519 signing.
public final class DeviceRelayIdentity: RelayIdentity {
    private let key: Curve25519.Signing.PrivateKey
    public let pubkeyHex: String

    public init(key: Curve25519.Signing.PrivateKey) {
        self.key = key
        self.pubkeyHex = key.publicKey.rawRepresentation.map { String(format: "%02x", $0) }.joined()
    }

    public convenience init() { self.init(key: .init()) }

    /// Persisted identity: store rawRepresentation (64 bytes) in Keychain/defaults.
    public var privateKeyBytes: Data { key.rawRepresentation }
    public convenience init?(privateKeyBytes: Data) {
        guard let k = try? Curve25519.Signing.PrivateKey(rawRepresentation: privateKeyBytes) else { return nil }
        self.init(key: k)
    }

    public func sign(_ message: String) -> String {
        let sig = try! key.signature(for: Data(message.utf8))
        return sig.map { String(format: "%02x", $0) }.joined()
    }
}
#else
/// Linux core tests: ed25519 via swift-crypto when present; falls back to an
/// insecure no-op identity ONLY for parse-only builds (tests inject their own).
public final class DeviceRelayIdentity: RelayIdentity {
    public let pubkeyHex: String = ""
    public func sign(_ message: String) -> String { "" }
    public init() {}
}
#endif

// MARK: - Standalone trading (mirrors Kotlin TradeSession)

public enum TradeEvent: Sendable {
    case ready(peerCollection: [String: Int])
    case peerOffer(ids: [String])
    case done(gained: [CardDef], lost: [CardDef])
    case fault(code: String, detail: String)
    case peerGone
}

public final class TradeSession {
    private let wire: Wire
    private let isHost: Bool
    private let token: String
    private let catalog: [String: CardDef]
    private let myCollection: [String: Int]
    private let eventsCont: AsyncStream<TradeEvent>.Continuation
    public let events: AsyncStream<TradeEvent>
    private let lock = NSLock()   // wire callbacks may arrive on any thread; state
                                    // is serialized by this lock (no DispatchQueue:
                                    // corelibs dispatch can deadlock under test runners)

    private var peerCollection: [String: Int] = [:]
    private var myOffer: [String] = []
    private var peerOffer: [String] = []
    private var iAccepted = false
    private var peerAccepted = false
    private var concluded = false

    public init(wire: Wire, isHost: Bool, token: String,
                catalog: [String: CardDef], myCollection: [String: Int]) {
        self.wire = wire
        self.isHost = isHost
        self.token = token
        self.catalog = catalog
        self.myCollection = myCollection
        var cont: AsyncStream<TradeEvent>.Continuation!
        self.events = AsyncStream { cont = $0 }
        self.eventsCont = cont
    }

    public func start() {
        wire.onLine = { [weak self] line in self?.locked { self?.handle(line) } }
        wire.onClose = { [weak self] in
            self?.locked { if self?.concluded == false { self?.eventsCont.yield(.peerGone) } }
        }
        // Hello carries the token both sides verify
        send(json: ["type": "hello", "name": "peer", "token": token, "proto": 1])
    }

    private func locked(_ body: () -> Void) { lock.lock(); body(); lock.unlock() }

    public func propose(ids: [String]) {
        locked {
            let mine = Dictionary(grouping: ids, by: { $0 }).mapValues(\.count)
            for (id, n) in mine where (self.myCollection[id] ?? 0) < n { return }
            let kept = self.myCollection.values.reduce(0, +) - ids.count
            if kept < 5 {
                self.eventsCont.yield(.fault(code: "floor", detail: "that would leave you fewer than 5 cards"))
                return
            }
            self.myOffer = ids
            self.iAccepted = false; self.peerAccepted = false
            self.send(json: ["type": "fOffer", "ids": ids])
            self.maybeResolve()
        }
    }

    public func accept() {
        locked {
            guard !self.myOffer.isEmpty, !self.peerOffer.isEmpty, !self.iAccepted else { return }
            self.iAccepted = true
            self.send(json: ["type": "fAccept"])
            self.maybeResolve()
        }
    }

    private func maybeResolve() {
        guard !myOffer.isEmpty, !peerOffer.isEmpty, iAccepted, peerAccepted, !concluded else { return }
        concluded = true
        let gained = peerOffer.compactMap { catalog[$0] }
        let lost = myOffer.compactMap { catalog[$0] }
        eventsCont.yield(.done(gained: gained, lost: lost))
    }

    private func handle(_ line: String) {
        guard let data = line.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let type = obj["type"] as? String else {
            eventsCont.yield(.fault(code: "json", detail: String(line.prefix(80))))
            return
        }
        switch type {
        case "hello":
            let tok = obj["token"] as? String ?? ""
            guard tok == token else {
                eventsCont.yield(.fault(code: "token", detail: "token mismatch")); return
            }
            send(json: ["type": "tColl", "counts": myCollection])

        case "tColl":
            guard let counts = obj["counts"] as? [String: Int] else { return }
            if counts.count > 256 || counts.values.contains(where: { $0 < 1 || $0 > 99 }) {
                eventsCont.yield(.fault(code: "coll", detail: "implausible collection declaration")); return
            }
            peerCollection = counts.filter { $0.value > 0 }
            eventsCont.yield(.ready(peerCollection: peerCollection))

        case "fOffer":
            guard let ids = obj["ids"] as? [String] else { return }
            let counts = Dictionary(grouping: ids, by: { $0 }).mapValues(\.count)
            if counts.contains(where: { (peerCollection[$0.key] ?? 0) < $0.value }) {
                eventsCont.yield(.fault(code: "offer", detail: "offered cards outside their collection")); return
            }
            peerOffer = ids
            iAccepted = false; peerAccepted = false
            eventsCont.yield(.peerOffer(ids: ids))
            maybeResolve()

        case "fAccept":
            peerAccepted = true
            maybeResolve()

        case "error":
            eventsCont.yield(.fault(code: obj["code"] as? String ?? "?", detail: obj["detail"] as? String ?? ""))

        default:
            break
        }
    }

    private func send(json: [String: Any]) {
        if let data = try? JSONSerialization.data(withJSONObject: json),
           let line = String(data: data, encoding: .utf8) {
            wire.send(line)
        }
    }

    public func teardown() {
        concluded = true
        wire.close()
    }
}
