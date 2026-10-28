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

/// Per-card record of how the PLAYER fares with a card they bring: games brought / games won.
/// This is what the Expert AI "remembers" between sittings. (Mirrors Kotlin CardMemory.)
public struct CardMemory: Codable, Hashable {
    public var played: Int
    public var won: Int
    public init(played: Int = 0, won: Int = 0) {
        self.played = played; self.won = won
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
    public var cardMemory: [String: CardMemory]
    public var expertStreak: Int
    public var expertBeatenWith: [String]
    public var expertWonDecks: [String]

    public init(collection: [String: Int] = [:], decks: [NamedDeck] = [], activeDeck: String? = nil,
                neverUse: [String] = [], neverFace: [String] = [],
                wins: Int = 0, losses: Int = 0, draws: Int = 0, tutorialSeen: Bool = false,
                cardMemory: [String: CardMemory] = [:], expertStreak: Int = 0,
                expertBeatenWith: [String] = [], expertWonDecks: [String] = []) {
        self.collection = collection; self.decks = decks; self.activeDeck = activeDeck
        self.neverUse = neverUse; self.neverFace = neverFace
        self.wins = wins; self.losses = losses; self.draws = draws; self.tutorialSeen = tutorialSeen
        self.cardMemory = cardMemory; self.expertStreak = expertStreak
        self.expertBeatenWith = expertBeatenWith; self.expertWonDecks = expertWonDecks
    }

    enum CodingKeys: String, CodingKey {
        case collection, decks, activeDeck, neverUse, neverFace, wins, losses, draws, tutorialSeen
        case cardMemory, expertStreak, expertBeatenWith, expertWonDecks
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
        cardMemory = try c.decodeIfPresent([String: CardMemory].self, forKey: .cardMemory) ?? [:]
        expertStreak = try c.decodeIfPresent(Int.self, forKey: .expertStreak) ?? 0
        expertBeatenWith = try c.decodeIfPresent([String].self, forKey: .expertBeatenWith) ?? []
        expertWonDecks = try c.decodeIfPresent([String].self, forKey: .expertWonDecks) ?? []
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

    /// Learn from one finished single-player game: the cards the player brought + outcome.
    /// Every difficulty feeds cardMemory; Expert games alone move expertStreak.
    public func withGameMemory(handIds: [String], won: Bool, expert: Bool) -> PlayerSave {
        var s = self
        for id in Set(handIds) {
            let m = s.cardMemory[id] ?? CardMemory()
            s.cardMemory[id] = CardMemory(played: m.played + 1, won: m.won + (won ? 1 : 0))
        }
        if expert {
            if won { s.expertStreak = max(0, s.expertStreak) + 1 }
            else { s.expertStreak = min(0, s.expertStreak) - 1 }
        }
        if expert && won {
            s.expertBeatenWith = Array(Set(s.expertBeatenWith + handIds)).sorted()
            let sig = handIds.sorted().joined(separator: ",")
            if !s.expertWonDecks.contains(sig) { s.expertWonDecks.append(sig) }
        }
        return s
    }

    /// The player's PROVEN cards: brought often enough to be a pattern and winning with them.
    public func expertThreats(minPlays: Int = 3, minWinRate: Double = 0.5, limit: Int = 5) -> Set<String> {
        cardMemory
            .filter { $0.value.played >= minPlays && Double($0.value.won) / Double($0.value.played) >= minWinRate }
            .sorted { a, b in
                if a.value.won != b.value.won { return a.value.won > b.value.won }
                return a.value.played > b.value.played
            }
            .prefix(limit)
            .map { $0.key }
            .reduce(into: Set<String>()) { $0.insert($1) }
    }

    public func addCards(_ cards: [CardDef]) -> PlayerSave {
        var c = collection
        for card in cards { c[card.id, default: 0] += 1 }
        return PlayerSave(collection: c, decks: decks, activeDeck: activeDeck,
                          neverUse: neverUse, neverFace: neverFace,
                          wins: wins, losses: losses, draws: draws, tutorialSeen: tutorialSeen,
                          cardMemory: cardMemory, expertStreak: expertStreak,
                          expertBeatenWith: expertBeatenWith, expertWonDecks: expertWonDecks)
    }

    public func applyTransfers(_ transfers: [CardTransfer], myColor: Owner) -> PlayerSave {
        var c = collection
        for t in transfers {
            if t.to == myColor { c[t.card.id, default: 0] += 1 }
            if t.from == myColor { c[t.card.id] = max(0, (c[t.card.id] ?? 1) - 1) }
        }
        return PlayerSave(collection: c, decks: decks, activeDeck: activeDeck,
                          neverUse: neverUse, neverFace: neverFace,
                          wins: wins, losses: losses, draws: draws, tutorialSeen: tutorialSeen,
                          cardMemory: cardMemory, expertStreak: expertStreak,
                          expertBeatenWith: expertBeatenWith, expertWonDecks: expertWonDecks)
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

// MARK: - Trade risk (host gating + joiner warnings)

public extension TradeRule {
    /// Worst-case cards a player can lose in one match (brought = hand size).
    var maxLoss: Int {
        switch self {
        case .none: return 0
        case .one: return 1
        case .direct, .diff, .all: return 5
        }
    }
}

// MARK: - Campaign packs (mirrors Kotlin rollPack)

/// The five cards that ONLY drop from Expert packs.
public let EXPERT_EXCLUSIVES: Set<String> = ["doomtrain", "edea", "rinoa", "squall", "seifer"]

public func rollPack(difficulty: String, catalog: [CardDef],
                     exclusives: Set<String> = EXPERT_EXCLUSIVES) -> [CardDef] {
    guard !catalog.isEmpty else { return [] }
    let byLevel = Dictionary(grouping: catalog, by: { $0.level })
    func drawFrom(_ range: ClosedRange<Int>, pool: (CardDef) -> Bool = { _ in true }) -> CardDef {
        for _ in 0..<20 {
            if let lvl = range.randomElement(), let cards = byLevel[lvl]?.filter(pool),
               let card = cards.randomElement() { return card }
        }
        return catalog.filter(pool).randomElement() ?? catalog.randomElement()!
    }

    switch difficulty.lowercased() {
    case "easy":
        return (0..<3).map { _ in drawFrom(1...3) }
    case "medium":
        return [drawFrom(4...6)] + (0..<3).map { _ in drawFrom(2...6) }
    case "expert":
        // Exactly 2 cards, both level 9-10 — the ONLY source of the hidden five.
        let jackpot = catalog.filter { exclusives.contains($0.id) }.randomElement() ?? drawFrom(9...10)
        return [jackpot, drawFrom(9...10)]
    default:   // hard: 3 cards, levels 3-10 (one guaranteed 7+), never the hidden five
        let jackpot = drawFrom(7...10) { !exclusives.contains($0.id) }
        return [jackpot] + (0..<2).map { _ in drawFrom(3...10) { !exclusives.contains($0.id) } }
    }
}

// MARK: - Starter collection (fixed — identical for every fresh install)

public let STARTER_COLLECTION = [
    "geezard", "funguar", "bitebug", "redbat", "blobra", "gayla", "gesper",
    "fastitocalonf", "bloodsoul", "caterchipillar", "cockatrice", "caterchipillar",
]
