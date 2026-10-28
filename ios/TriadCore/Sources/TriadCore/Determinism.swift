import Foundation
import CryptoKit

struct SplitMix64 {
    private var seed: UInt64
    init(seed: UInt64) { self.seed = seed }
    mutating func next() -> UInt64 {
        seed &+= 0x9E3779B97F4A7C15
        var z = seed
        z = (z ^ (z >> 30)) &* 0xBF58476D1CE4E5B9
        z = (z ^ (z >> 27)) &* 0x94D049BB133111EB
        return z ^ (z >> 31)
    }
    mutating func nextInt(_ bound: Int) -> Int { Int(next() >> 33) % bound }
}

extension SplitMix64 {
    mutating func sample5(_ pool: [String]) -> [String] {   // partial Fisher-Yates, pinned by fixture
        var p = pool, out: [String] = []
        for _ in 0..<5 { out.append(p.remove(at: nextInt(p.count))) }
        return out
    }
}

func sha256Hex(_ s: String) -> String {
    SHA256.hash(data: Data(s.utf8)).map { String(format: "%02x", $0) }.joined()
}

func seedFromHex(_ hex: String) -> UInt64 {
    UInt64(hex.prefix(16), radix: 16)!
}

func randomHex(_ bytes: Int) -> String {
    var rng = SystemRandomNumberGenerator()
    return (0..<bytes).map { _ in String(format: "%02x", UInt8(rng.next() % 256)) }.joined()
}
