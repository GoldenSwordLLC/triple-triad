#if canImport(Darwin)
import Darwin
import Foundation
import Network

// MARK: - Hosting: a TCP listener that hands accepted connections to the session

public final class TcpMatchListener {
    private let listener: NWListener
    private let queue = DispatchQueue(label: "triad.listen")
    public private(set) var port: UInt16 = 0
    public var onAccept: ((TcpWire) -> Void)?

    public init() throws {
        listener = try NWListener(using: .tcp)   // ephemeral port
        listener.newConnectionHandler = { [weak self] conn in
            guard let self else { return }
            self.queue.async { self.onAccept?(TcpWire(conn)) }
        }
    }

    public func start() {
        listener.start(queue: queue)
        port = listener.port?.rawValue ?? 0
    }

    public func cancel() { listener.cancel() }
}

// MARK: - QR invite payload (mirrors the Android triad://v1 format)

public struct QrInvite: Equatable {
    public let transport: String
    public let ip: String?
    public let port: Int?
    public let name: String
    public let token: String
    public init(transport: String, ip: String?, port: Int?, name: String, token: String) {
        self.transport = transport; self.ip = ip; self.port = port
        self.name = name; self.token = token
    }
}

public func parseInvite(_ raw: String) -> QrInvite? {
    guard let url = URL(string: raw), url.scheme == "triad", url.host == "v1" else { return nil }
    let q = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
    func param(_ key: String) -> String? { q.first(where: { $0.name == key })?.value }
    return QrInvite(
        transport: param("tr") ?? "tcp",
        ip: param("ip"),
        port: param("port").flatMap(Int.init),
        name: param("name") ?? "?",
        token: param("t") ?? "")
}

public func invitePayload(ip: String?, port: UInt16, name: String, token: String) -> String {
    let n = name.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? name
    var s = "triad://v1?tr=tcp&name=\(n)&t=\(token)"
    if let ip { s += "&ip=\(ip)&port=\(port)" }
    return s
}

/// First non-loopback IPv4 address (Wi-Fi interface preferred). */
public func lanIpAddress() -> String? {
    var ifaddr: UnsafeMutablePointer<ifaddrs>?
    guard getifaddrs(&ifaddr) == 0, let first = ifaddr else { return nil }
    defer { freeifaddrs(ifaddr) }
    var en0: String? = nil
    var any: String? = nil
    var ptr: UnsafeMutablePointer<ifaddrs>? = first
    while let current = ptr {
        let interface = current.pointee
        let name = String(cString: interface.ifa_name)
        if let sa = interface.ifa_addr, sa.pointee.sa_family == UInt8(AF_INET) {
            var addr = sockaddr_in()
            memcpy(&addr, sa, MemoryLayout<sockaddr_in>.size)
            var buf = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
            if inet_ntop(AF_INET, &addr.sin_addr, &buf, socklen_t(INET_ADDRSTRLEN)) != nil {
                let ip = String(cString: buf)
                if name == "en0" { en0 = en0 ?? ip }
                else if name != "lo0" { any = any ?? ip }
            }
        }
        ptr = interface.ifa_next
    }
    return en0 ?? any
}

#endif
