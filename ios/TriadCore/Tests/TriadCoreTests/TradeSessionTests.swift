import XCTest
@testable import TriadCore

// Linux corelibs-xctest hangs on async test functions — the trade exchange runs
// in the macOS CI job (CryptoKit is available there too).
#if canImport(Darwin)
final class TradeSessionTests: XCTestCase {
    private final class Loopback: Wire {
        var onLine: ((String) -> Void)?
        var onClose: (() -> Void)?
        private var peerBox: Loopback?
        private let lock = NSLock()
        var peerWire: Loopback? {
            get { lock.lock(); defer { lock.unlock() }; return peerBox }
            set { lock.lock(); defer { lock.unlock() }; peerBox = newValue }
        }
        func send(_ line: String) { peerWire?.onLine?(line) }
        func close() { peerWire?.onClose?(); onClose?() }
        static func pair() -> (Loopback, Loopback) {
            let a = Loopback(); let b = Loopback()
            a.peerWire = b; b.peerWire = a
            return (a, b)
        }
    }

    @discardableResult
    private func drive(_ s: TradeSession, collect: @escaping (TradeEvent) -> Void) -> Task<Void, Never> {
        Task { for await e in s.events { collect(e) } }
    }

    func testFullTradeExchangeOnMutualAccept() async {
        let catalog = (1...10).map { CardDef(id: "c\($0)", name: "C\($0)", level: 1, element: .none,
                                              top: 5, right: 5, bottom: 5, left: 5) }
            .reduce(into: [String: CardDef]()) { $0[$1.id] = $1 }
        let (wa, wb) = Loopback.pair()
        let a = TradeSession(wire: wa, isHost: true, token: "TK", catalog: catalog,
                             myCollection: ["c1": 3, "c2": 1, "c3": 2, "c4": 2, "c5": 1, "c6": 1])
        let b = TradeSession(wire: wb, isHost: false, token: "TK", catalog: catalog,
                             myCollection: ["c5": 2, "c9": 1, "c6": 2, "c7": 2, "c8": 1, "c10": 1])

        let aReady = Expectation<[String: Int]>()
        let aDone = Expectation<[String]>()
        let bDone = Expectation<[String]>()
        let bOffer = Expectation<[String]>()
        let faulted = Expectation<Bool>()
        drive(a) { e in
            switch e {
            case .ready(let c): aReady.fulfill(c)
            case .done(_, let lost): aDone.fulfill(lost.map(\.id).sorted())
            case .fault: faulted.fulfill(true)
            default: break
            }
        }
        drive(b) { e in
            switch e {
            case .peerOffer(let ids): bOffer.fulfill(ids)
            case .done(let gained, _): bDone.fulfill(gained.map(\.id).sorted())
            case .fault: faulted.fulfill(true)
            default: break
            }
        }

        a.start(); b.start()
        await aReady.wait()
        XCTAssertEqual(2, aReady.value?["c5"] ?? 0)

        a.propose(ids: ["c1", "c1", "c2"])
        await bOffer.wait()
        XCTAssertEqual(["c1", "c1", "c2"], bOffer.value)
        b.propose(ids: ["c9"])
        b.accept()
        a.accept()
        await aDone.wait(); await bDone.wait()

        XCTAssertEqual(["c9"], bDone.value)
        XCTAssertEqual(["c1", "c1", "c2"], aDone.value)
        XCTAssertEqual(aDone.value, bDone.value, "both phones derive the IDENTICAL exchange")
        XCTAssertFalse(faulted.value ?? false)
        a.teardown(); b.teardown()
    }

    func testOverOfferingNeverReachesThePeer() async {
        let catalog = (1...4).map { CardDef(id: "c\($0)", name: "C\($0)", level: 1, element: .none,
                                              top: 5, right: 5, bottom: 5, left: 5) }
            .reduce(into: [String: CardDef]()) { $0[$1.id] = $1 }
        let (wa, wb) = Loopback.pair()
        let a = TradeSession(wire: wa, isHost: true, token: "TK", catalog: catalog,
                             myCollection: ["c1": 1, "c2": 1, "c3": 2, "c4": 2])
        let b = TradeSession(wire: wb, isHost: false, token: "TK", catalog: catalog,
                             myCollection: ["c2": 1, "c1": 1, "c3": 2, "c4": 2])
        let bReady = Expectation<Bool>()
        let stray = Expectation<Bool>()
        drive(b) { e in
            switch e {
            case .ready: bReady.fulfill(true)
            case .peerOffer: stray.fulfill(true)
            default: break
            }
        }
        drive(a) { _ in }
        a.start(); b.start()
        await bReady.wait()

        a.propose(ids: ["c1", "c1", "c1"])   // owns ONE copy: the local guard drops it
        try? await Task.sleep(nanoseconds: 300_000_000)
        XCTAssertFalse(stray.value ?? false, "over-offer leaked to the peer")
        a.teardown(); b.teardown()
    }
}

/// Tiny async-friendly expectation helper for these tests.
private final class Expectation<T> {
    private let lock = NSLock()
    private var _value: T?
    private var cont: CheckedContinuation<Void, Never>?
    var value: T? {
        get { lock.lock(); defer { lock.unlock() }; return _value }
        set { if let v = newValue { fulfill(v) } }
    }
    init(initial: T? = nil) { _value = initial }
    init() { _value = nil }
    func fulfill(_ v: T) {
        lock.lock(); _value = v
        let c = cont; cont = nil; lock.unlock()
        c?.resume()
    }
    func wait() async {
        await withCheckedContinuation { (cc: CheckedContinuation<Void, Never>) in
            lock.lock()
            if _value != nil { lock.unlock(); cc.resume(); return }
            cont = cc
            lock.unlock()
        }
    }
}

#endif
