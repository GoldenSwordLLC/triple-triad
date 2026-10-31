#if canImport(Darwin)
import Foundation
import Network

public final class TcpWire: Wire {
    private let conn: NWConnection
    private let queue = DispatchQueue(label: "triad.tcp")
    private var buffer = Data()
    private var pendingLines: [String] = []
    private var closedFired = false
    public var onLine: ((String) -> Void)? { didSet { flushPending() } }
    public var onClose: (() -> Void)?

    public init(_ conn: NWConnection) { self.conn = conn }
    convenience init(host: String, port: UInt16) {
        self.init(NWConnection(to: .hostPort(host: .init(host), port: NWEndpoint.Port(rawValue: port)!), using: .tcp))
    }

    public func start() {
        conn.stateUpdateHandler = { [weak self] st in
            guard let self else { return }
            if st == .ready { self.readLoop() }
            if case .failed = st { self.closed() }
            if case .cancelled = st { self.closed() }
        }
        conn.start(queue: queue)
    }
    private func flushPending() {
        guard let onLine else { return }
        while !pendingLines.isEmpty { onLine(pendingLines.removeFirst()) }
    }
    private func closed() { if !closedFired { closedFired = true; onClose?() } }

    public func send(_ line: String) {
        conn.send(content: Data((line + "\n").utf8), completion: .contentProcessed { _ in })
    }
    private func readLoop() {
        conn.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { [weak self] data, _, done, error in
            guard let self else { return }
            if let data { self.buffer += data }
            while let i = self.buffer.firstIndex(of: 0x0A) {
                let line = String(decoding: self.buffer[..<i], as: UTF8.self)
                self.buffer = Data(self.buffer[(i + 1)...])
                if !line.isEmpty {
                    if let cb = self.onLine { cb(line) } else { self.pendingLines.append(line) }
                }
            }
            if done || error != nil { self.closed(); return }
            self.readLoop()
        }
    }
    public func close() { conn.cancel() }
}

#endif
