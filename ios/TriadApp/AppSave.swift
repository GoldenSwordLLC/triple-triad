import Foundation
import TriadCore

/// App-side save/prefs: PlayerSave JSON in Documents, small prefs in UserDefaults.
enum AppSave {
    static var difficulty: String {
        get { UserDefaults.standard.string(forKey: "difficulty") ?? "easy" }
        set { UserDefaults.standard.set(newValue, forKey: "difficulty") }
    }
    static var hardWins: Int {
        get { UserDefaults.standard.integer(forKey: "hardWins") }
        set { UserDefaults.standard.set(newValue, forKey: "hardWins") }
    }
    /// SP abandonment marker: set at match start, cleared on conclude — a mid-match
    /// app death records a loss on next launch.
    static var spMatchActive: String? {
        get { UserDefaults.standard.string(forKey: "spMatchActive") }
        set { UserDefaults.standard.set(newValue, forKey: "spMatchActive") }
    }
    static var shakePackClaimed: Bool {
        get { UserDefaults.standard.bool(forKey: "shakePackClaimed") }
        set { UserDefaults.standard.set(newValue, forKey: "shakePackClaimed") }
    }

    static func load(catalog: [String: CardDef]) -> PlayerSave {
        let url = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("triad-save.json")
        var save = (try? Data(contentsOf: url)).flatMap { try? JSONDecoder().decode(PlayerSave.self, from: $0) }
            ?? PlayerSave()
        if save.collection.values.filter({ $0 > 0 }).isEmpty {
            save = save.addCards(STARTER_COLLECTION.compactMap { catalog[$0] })
            persist(save)
        }
        return save
    }

    static func persist(_ save: PlayerSave) {
        let url = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("triad-save.json")
        if let data = try? JSONEncoder().encode(save) {
            try? data.write(to: url, options: .atomic)
        }
    }

    /// Battle deck: the active deck if still valid (size + ownership + SINGLE
    /// INSTANCE), else DISTINCT owned cards (single-instance rule — random deals
    /// never repeat a card), else weak catalog cards (single player only).
    static func battleDeck(_ save: PlayerSave, catalog: [String: CardDef]) -> [CardDef] {
        let active = save.activeDeckCards()
        if active.count == 5, Set(active).count == 5,
           active.allSatisfy({ (save.collection[$0] ?? 0) > 0 }) {
            return active.compactMap { catalog[$0] }
        }
        let ownedDistinct = save.collection.filter { $0.value > 0 }.keys.compactMap { catalog[$0] }
        if ownedDistinct.count >= 5 {
            return Array(ownedDistinct.shuffled().prefix(5))
        }
        let low = catalog.values.filter { $0.level <= 5 }.shuffled()
        return Array(low.prefix(5))
    }

    /// Pass & play: the distinct pool red picks from (owned minus blue's cards).
    static func redPassDeckPool(_ save: PlayerSave, blue: [CardDef], catalog: [String: CardDef]) -> [CardDef] {
        let used = Set(blue.map(\.id))
        return save.collection
            .filter { $0.value > 0 && !used.contains($0.key) }
            .keys.compactMap { catalog[$0] }
            .sorted { ($0.level, $0.no) < ($1.level, $1.no) }
    }

    /// Pass & play red hand: distinct owned cards minus blue's picks.
    static func redPassDeck(_ save: PlayerSave, blue: [CardDef], catalog: [String: CardDef]) -> [CardDef] {
        let used = Set(blue.map(\.id))
        let pool = save.collection.filter { $0.value > 0 && !used.contains($0.key) }
            .keys.compactMap { catalog[$0] }
        var picked = Array(pool.shuffled().prefix(5))
        let low = catalog.values.filter { $0.level <= 5 }
        while picked.count < 5 {
            let taken = Set(picked.map(\.id))
            guard let next = low.first(where: { !taken.contains($0.id) }) else { break }
            picked.append(next)
        }
        return picked
    }

    static func hasActiveDeck(_ save: PlayerSave) -> Bool {
        save.activeDeckCards().count == 5
    }

    static var cardCount: (PlayerSave) -> Int = { $0.collection.values.reduce(0, +) }
}
