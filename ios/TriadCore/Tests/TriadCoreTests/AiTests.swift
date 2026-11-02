import XCTest
@testable import TriadCore

final class AiTests: XCTestCase {
    private func opening() -> GameState {
        let deck = (1...10).flatMap { lvl in
            (1...2).map { i -> CardDef in
                let rank = 1 + lvl * 8 / 10
                return CardDef(id: "c\(lvl)_\(i)", name: "C\(lvl)_\(i)", level: lvl,
                               element: .none, top: rank, right: rank,
                               bottom: rank, left: rank)
            }
        }
        return GameState(board: Board(), blueHand: Array(deck.prefix(5)), redHand: Array(deck.suffix(5)))
    }

    func testAllTiersReturnLegalMovesOnAnOpening() {
        let s = opening()
        for m in [Ai.easy(s), Ai.medium(s), Ai.hard(s)] {
            XCTAssertTrue(s.hand(s.turn).indices.contains(m.cardIndex), "cardIndex out of hand")
            XCTAssertTrue((0...8).contains(m.cellIndex), "cellIndex out of board")
        }
    }

    func testHardAnswersWithinItsTimeBudget() {
        let s = opening()   // widest branching the search will ever see
        let t0 = DispatchTime.now().uptimeNanoseconds
        let m = Ai.hard(s, budgetMs: 500)
        let ms = (DispatchTime.now().uptimeNanoseconds - t0) / 1_000_000
        XCTAssertTrue((0...8).contains(m.cellIndex))
        XCTAssertLessThan(ms, 2_500, "hard took \(ms)ms against a 500ms budget")
    }

    func testAFullAiVsAiGameAlwaysFinishes() {
        var s = opening()
        s.turn = .red   // red-first: the reported stall shape
        var plies = 0
        var flip = false
        while !s.isOver {
            let m = flip ? Ai.medium(s) : Ai.easy(s)
            s = Engine.place(s, cardIndex: m.cardIndex, cellIndex: m.cellIndex).state
            flip.toggle()
            plies += 1
            if plies > 20 { XCTFail("game did not finish in 20 plies"); return }
        }
        XCTAssertEqual(plies, 9)
    }
}
