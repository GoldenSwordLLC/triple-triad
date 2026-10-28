import Foundation

enum ColorPref: String, Codable, Sendable { case blue, red, any }

enum Message: Sendable {
    case hello(name: String, token: String, proto: Int)
    case commit(hash: String)
    case reveal(nonce: String)
    case settings(settings: MatchSettings)
    case accept
    case decline
    case deck(ids: [String], bans: [String], pref: ColorPref)
    case deckAck
    case move(handIndex: Int, cell: Int, card: String, seq: Int, hash: String)
    case tradePick(ids: [String])
    case resign
    case error(code: String, detail: String)
}

extension Message: Codable {
    private enum K: String, CodingKey {
        case type, name, token, proto, hash, nonce, settings, ids, bans, pref,
             handIndex, cell, card, seq, code, detail
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: K.self)
        switch try c.decode(String.self, forKey: .type) {
        case "hello":     self = .hello(name: try c.decode(String.self, forKey: .name),
                                        token: try c.decodeIfPresent(String.self, forKey: .token) ?? "",
                                        proto: try c.decodeIfPresent(Int.self, forKey: .proto) ?? 1)
        case "commit":    self = .commit(hash: try c.decode(String.self, forKey: .hash))
        case "reveal":    self = .reveal(nonce: try c.decode(String.self, forKey: .nonce))
        case "settings":  self = .settings(settings: try c.decode(MatchSettings.self, forKey: .settings))
        case "accept":    self = .accept
        case "decline":   self = .decline
        case "deck":      self = .deck(ids: try c.decode([String].self, forKey: .ids),
                                       bans: try c.decodeIfPresent([String].self, forKey: .bans) ?? [],
                                       pref: try c.decodeIfPresent(ColorPref.self, forKey: .pref) ?? .any)
        case "deckAck":   self = .deckAck
        case "move":      self = .move(handIndex: try c.decode(Int.self, forKey: .handIndex),
                                       cell: try c.decode(Int.self, forKey: .cell),
                                       card: try c.decode(String.self, forKey: .card),
                                       seq: try c.decode(Int.self, forKey: .seq),
                                       hash: try c.decode(String.self, forKey: .hash))
        case "tradePick": self = .tradePick(ids: try c.decode([String].self, forKey: .ids))
        case "resign":    self = .resign
        case "error":     self = .error(code: try c.decode(String.self, forKey: .code),
                                        detail: try c.decodeIfPresent(String.self, forKey: .detail) ?? "")
        default: throw DecodingError.dataCorrupted(
            .init(codingPath: [], debugDescription: "unknown message type"))
        }
    }
    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: K.self)
        switch self {
        case let .hello(name, token, proto):
            try c.encode("hello", forKey: .type); try c.encode(name, forKey: .name)
            try c.encode(token, forKey: .token); try c.encode(proto, forKey: .proto)
        case let .commit(hash):
            try c.encode("commit", forKey: .type); try c.encode(hash, forKey: .hash)
        case let .reveal(nonce):
            try c.encode("reveal", forKey: .type); try c.encode(nonce, forKey: .nonce)
        case let .settings(s):
            try c.encode("settings", forKey: .type); try c.encode(s, forKey: .settings)
        case .accept:  try c.encode("accept", forKey: .type)
        case .decline: try c.encode("decline", forKey: .type)
        case let .deck(ids, bans, pref):
            try c.encode("deck", forKey: .type); try c.encode(ids, forKey: .ids)
            try c.encode(bans, forKey: .bans); try c.encode(pref, forKey: .pref)
        case .deckAck: try c.encode("deckAck", forKey: .type)
        case let .move(handIndex, cell, card, seq, hash):
            try c.encode("move", forKey: .type)
            try c.encode(handIndex, forKey: .handIndex); try c.encode(cell, forKey: .cell)
            try c.encode(card, forKey: .card); try c.encode(seq, forKey: .seq)
            try c.encode(hash, forKey: .hash)
        case let .tradePick(ids):
            try c.encode("tradePick", forKey: .type); try c.encode(ids, forKey: .ids)
        case .resign: try c.encode("resign", forKey: .type)
        case let .error(code, detail):
            try c.encode("error", forKey: .type); try c.encode(code, forKey: .code)
            try c.encode(detail, forKey: .detail)
        }
    }
}

// JSONDecoder ignores unknown keys by default (Kotlin needs ignoreUnknownKeys = true).
func encodeMessage(_ m: Message) -> String {
    String(data: try! JSONEncoder().encode(m), encoding: .utf8)!
}
func decodeMessage(_ line: String) -> Message? {
    try? JSONDecoder().decode(Message.self, from: Data(line.utf8))
}
