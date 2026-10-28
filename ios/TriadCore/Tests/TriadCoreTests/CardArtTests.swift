import XCTest
@testable import TriadCore

final class CardArtTests: XCTestCase {
    func testCardsLoadFromBundle() {
        for owner in [Owner.blue, .red] {
            let card = CardArt.load(1, owner)   // G-1 Geezard
            XCTAssertNotNil(card, "missing \(owner) card 1 asset")
        }
        XCTAssertNotNil(CardArt.playmat(), "missing playmat asset")
    }

    func testCardDimensions() {
        let card = CardArt.load(1, .red)!
        XCTAssertEqual(card.width, 375)
        XCTAssertEqual(card.height, 525)
    }

    func testDifferentCardsProduceDifferentArt() {
        let a = CardArt.load(1, .red)!
        let b = CardArt.load(52, .red)!
        let ab = a.dataProvider?.data.map { $0 as Data } ?? Data()
        let bb = b.dataProvider?.data.map { $0 as Data } ?? Data()
        XCTAssertNotEqual(ab, bb, "distinct cards must render distinct art")
    }

    func testExcludedCardsAreAbsent() {
        // 111 (how-to-play) and 112 (white mage) were deliberately not shipped.
        XCTAssertNil(CardArt.load(111, .red))
        XCTAssertNil(CardArt.load(112, .blue))
    }
}
