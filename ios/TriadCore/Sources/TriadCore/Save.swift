import Foundation

// MARK: - Player save (mirrors Kotlin core/Save.kt — field names match for future save-file compat)

public struct NamedDeck: Codable, Hashable {
    public let id: String
    public let name: String
    public let ids: [String]
    public init(id: String, name: String, ids: [String]) {
        self.id = id; self.name = name; self.ids = ids
    }
}

public struct PlayerSave: Codable, Hashable {
    public var collection: [String: Int]     // cardId -> copies owned
    public var decks: [NamedDeck]
    public var activeDeck: String?
    public var neverUse: [String]
    public var neverFace: [String]
    public var wins: Int, losses: Int, draws: Int
    public var tutorialSeen: Bool

    public init(collection: [String: Int] = [:], decks: [NamedDeck] = [], activeDeck: String? = nil,
                neverUse: [String] = [], neverFace: [String] = [],
                wins: Int = 0, losses: Int = 0, draws: Int = 0, tutorialSeen: Bool = false) {
        self.collection = collection; self.decks = decks; self.activeDeck = activeDeck
        self.neverUse = neverUse; self.neverFace = neverFace
        self.wins = wins; self.losses = losses; self.draws = draws; self.tutorialSeen = tutorialSeen
    }

    enum CodingKeys: String, CodingKey {
        case collection, decks, activeDeck, neverUse, neverFace, wins, losses, draws, tutorialSeen
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        collection = try c.decodeIfPresent([String: Int].self, forKey: .collection) ?? [:]
        decks = try c.decodeIfPresent([NamedDeck].self, forKey: .decks) ?? []
        activeDeck = try c.decodeIfPresent(String.self, forKey: .activeDeck)
        neverUse = try c.decodeIfPresent([String].self, forKey: .neverUse) ?? []
        neverFace = try c.decodeIfPresent([String].self, forKey: .neverFace) ?? []
        wins = try c.decodeIfPresent(Int.self, forKey: .wins) ?? 0
        losses = try c.decodeIfPresent(Int.self, forKey: .losses) ?? 0
        draws = try c.decodeIfPresent(Int.self, forKey: .draws) ?? 0
        tutorialSeen = try c.decodeIfPresent(Bool.self, forKey: .tutorialSeen) ?? false
    }

    public func activeDeckCards() -> [String] {
        decks.first(where: { $0.id == activeDeck })?.ids ?? []
    }

    public func withRecord(won: Bool, drawn: Bool) -> PlayerSave {
        var s = self
        if won && !drawn { s.wins += 1 }
        if !won && !drawn { s.losses += 1 }
        if drawn { s.draws += 1 }
        return s
    }

    public func addCards(_ cards: [CardDef]) -> PlayerSave {
        var c = collection
        for card in cards { c[card.id, default: 0] += 1 }
        return PlayerSave(collection: c, decks: decks, activeDeck: activeDeck,
                          neverUse: neverUse, neverFace: neverFace,
                          wins: wins, losses: losses, draws: draws, tutorialSeen: tutorialSeen)
    }
}

// MARK: - Deck validation

public enum DeckError: Error, Equatable {
    case unknownCard(id: String)
    case notOwned(id: String, needed: Int, owned: Int)
    case wrongSize(size: Int)
    case banned(id: String)
}

public func validateDeck(deck: [String], catalog: Set<String>, collection: [String: Int],
                         enforceBans: Bool, neverUse: Set<String>) -> [DeckError] {
    var errors: [DeckError] = []
    if !(5...10).contains(deck.count) { errors.append(.wrongSize(size: deck.count)); return errors }
    let counts = Dictionary(grouping: deck, by: { $0 }).mapValues { $0.count }
    for (id, n) in counts {
        if !catalog.contains(id) { errors.append(.unknownCard(id: id)) }
        else if (collection[id] ?? 0) < n { errors.append(.notOwned(id: id, needed: n, owned: collection[id] ?? 0)) }
        if enforceBans && neverUse.contains(id) { errors.append(.banned(id: id)) }
    }
    return errors
}
