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
        #if canImport(CoreGraphics)
        let card = CardArt.load(1, .red)!
        XCTAssertEqual(card.width, 375)
        XCTAssertEqual(card.height, 525)
        #else
        // Linux loads raw JPEG bytes; dimensions live in the Apple-only decode path
        XCTAssertNotNil(CardArt.load(1, .red))
        #endif
    }

    func testDifferentCardsProduceDifferentArt() {
        let a = CardArt.load(1, .red)!
        let b = CardArt.load(52, .red)!
        #if canImport(CoreGraphics)
        let ab = (a?.dataProvider?.data as Data?) ?? Data()
        let bb = (b?.dataProvider?.data as Data?) ?? Data()
        #else
        let ab = a; let bb = b
        #endif
        XCTAssertNotEqual(ab, bb, "distinct cards must render distinct art")
    }

    func testExcludedCardsAreAbsent() {
        // 111 (how-to-play) and 112 (white mage) were deliberately not shipped.
        XCTAssertNil(CardArt.load(111, .red))
        XCTAssertNil(CardArt.load(112, .blue))
    }
}
