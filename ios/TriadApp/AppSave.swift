import Foundation
import TriadCore

/// App-side save/prefs: PlayerSave JSON in Documents, small prefs in UserDefaults.
enum AppSave {
    static func load(catalog: [String: CardDef]) -> PlayerSave {
        let url = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("triad-save.json")
        var save = (try? Data(contentsOf: url)).flatMap { try? JSONDecoder().decode(PlayerSave.self, from: $0) }
            ?? PlayerSave()
        if save.collection.values.filter { $0 > 0 }.isEmpty {
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

    /// Battle deck: the active deck if still owned, else owned cards (copy-aware),
    /// else starter-style catalog cards (single player only — multiplayer is gated).
    static func battleDeck(_ save: PlayerSave, catalog: [String: CardDef]) -> [CardDef] {
        let ids = Set(catalog.keys)
        let active = save.activeDeckCards()
        if validateDeck(deck: active, catalog: ids, collection: save.collection,
                        enforceBans: false, neverUse: []).isEmpty, active.count == 5 {
            return active.compactMap { catalog[$0] }
        }
        let ownedFlat = save.collection.flatMap { (id, n) in Array(repeating: id, count: n) }.compactMap { catalog[$0] }
        if ownedFlat.count >= 5 { return Array(ownedFlat.shuffled().prefix(5)) }
        let low = catalog.values.filter { $0.level <= 5 }
        return (0..<5).map { _ in low.randomElement() ?? CardDef(id: "x", name: "?", level: 1, element: .none,
                                                                  top: 1, right: 1, bottom: 1, left: 1) }
    }

    static func hasActiveDeck(_ save: PlayerSave) -> Bool {
        save.decks.first { $0.id == save.activeDeck }?.ids.count == 5
    }

    static var cardCount: (PlayerSave) -> Int = { $0.collection.values.reduce(0, +) }

    // MARK: prefs
    static var difficulty: String {
        get { UserDefaults.standard.string(forKey: "difficulty") ?? "easy" }
        set { UserDefaults.standard.set(newValue, forKey: "difficulty") }
    }
    static var hardWins: Int {
        get { UserDefaults.standard.integer(forKey: "hardWins") }
        set { UserDefaults.standard.set(newValue, forKey: "hardWins") }
    }
}
