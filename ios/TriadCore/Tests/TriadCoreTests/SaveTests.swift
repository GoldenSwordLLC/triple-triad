import XCTest
@testable import TriadCore

final class SaveTests: XCTestCase {
    private func catalog() -> [CardDef] {
        (1...10).flatMap { lvl in
            (1...2).map { i in
                let rank = lvl >= 9 ? 10 : 1 + lvl * 8 / 10
                return CardDef(id: "c\(lvl)_\(i)", name: "C\(lvl)-\(i)", level: lvl, element: .none,
                               top: rank, right: rank, bottom: rank, left: rank, no: lvl * 2 + i)
            }
        }
    }

    private let exclusives: Set<String> = ["c9_1", "c10_1", "c10_2"]

    func testAddCardsStacksCopies() {
        let cat = catalog()
        var save = PlayerSave()
        save = save.addCards([cat[0], cat[0], cat[1]])
        XCTAssertEqual(save.collection[cat[0].id], 2)
        XCTAssertEqual(save.collection[cat[1].id], 1)
    }

    func testValidateDeck() {
        let cat = catalog()
        let ids = Set(cat.map { $0.id })
        var save = PlayerSave(collection: [cat[0].id: 1, cat[1].id: 1, cat[2].id: 1, cat[3].id: 1, cat[4].id: 1])
        let deck = [cat[0].id, cat[1].id, cat[2].id, cat[3].id, cat[4].id]
        XCTAssertTrue(validateDeck(deck: deck, catalog: ids, collection: save.collection,
                                   enforceBans: false, neverUse: []).isEmpty)
        save.collection[cat[0].id] = 1
        let dupDeck = [cat[0].id, cat[0].id, cat[1].id, cat[2].id, cat[3].id]
        XCTAssertEqual(validateDeck(deck: dupDeck, catalog: ids, collection: save.collection,
                                    enforceBans: false, neverUse: []).first,
                       .notOwned(id: cat[0].id, needed: 2, owned: 1))
    }

    func testActiveDeckAndRecord() {
        var save = PlayerSave(decks: [NamedDeck(id: "d1", name: "One", ids: ["a", "b", "c", "d", "e"])])
        XCTAssertEqual(save.activeDeckCards(), [])
        save.activeDeck = "d1"
        XCTAssertEqual(save.activeDeckCards().count, 5)
        save = save.withRecord(won: true, drawn: false)
        XCTAssertEqual(save.wins, 1)
    }
}
