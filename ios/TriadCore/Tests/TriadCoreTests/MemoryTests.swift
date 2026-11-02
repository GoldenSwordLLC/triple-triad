import XCTest
@testable import TriadCore

/// Mirror of Kotlin MemoryTest — the Expert AI's learned memory.
final class MemoryTests: XCTestCase {
    private func card(_ id: String, _ r: Int) -> CardDef {
        CardDef(id: id, name: id, level: 5, element: .none, top: r, right: r, bottom: r, left: r)
    }

    func testEveryFinishedGameRecordsTheCardsThePlayerBrought() {
        let s = PlayerSave().withGameMemory(handIds: ["a", "b", "c", "d", "e"], won: true, expert: false)
        for id in ["a", "b", "c", "d", "e"] {
            XCTAssertEqual(CardMemory(played: 1, won: 1), s.cardMemory[id])
        }
        XCTAssertEqual(0, s.expertStreak)   // non-expert games never move the streak
    }

    func testDuplicatesInHandCountOncePerGame() {
        let s = PlayerSave().withGameMemory(handIds: ["a", "a", "a", "b", "b"], won: false, expert: false)
        XCTAssertEqual(CardMemory(played: 1, won: 0), s.cardMemory["a"])
        XCTAssertEqual(CardMemory(played: 1, won: 0), s.cardMemory["b"])
    }

    func testExpertStreakCountsConsecutiveRunsInBothDirections() {
        var s = PlayerSave()
        for _ in 0..<3 { s = s.withGameMemory(handIds: ["a"], won: true, expert: true) }
        XCTAssertEqual(3, s.expertStreak)
        s = s.withGameMemory(handIds: ["a"], won: false, expert: true)
        XCTAssertEqual(-1, s.expertStreak)   // a loss breaks the run, starts counting down
        for _ in 0..<2 { s = s.withGameMemory(handIds: ["a"], won: false, expert: true) }
        XCTAssertEqual(-3, s.expertStreak)
        s = s.withGameMemory(handIds: ["a"], won: true, expert: true)
        XCTAssertEqual(1, s.expertStreak)
    }

    func testThreatsNeedEnoughPlaysAndAWinningRecord() {
        let s = PlayerSave(cardMemory: [
            "proven": CardMemory(played: 5, won: 4),   // 80% — marked
            "coinflip": CardMemory(played: 4, won: 2), // 50% — marked (>= rate)
            "unlucky": CardMemory(played: 6, won: 1),  // 17% — not a threat
            "new": CardMemory(played: 1, won: 1),      // 100% but 1 play — not yet a pattern
        ])
        XCTAssertEqual(["coinflip", "proven"], s.expertThreats().sorted())
    }

    func testThreatListIsCappedBestRecordsFirst() {
        var m: [String: CardMemory] = [:]
        for i in 1...8 { m["c\(i)"] = CardMemory(played: 5, won: 8 - i) }
        let t = PlayerSave(cardMemory: m).expertThreats()
        XCTAssertEqual(5, t.count)
        XCTAssertTrue(t.contains("c1"))
    }

    func testExpertEffortScalesWithTheStreak() {
        XCTAssertEqual(1800, Ai.expertBudget(5))
        XCTAssertEqual(1400, Ai.expertBudget(2))
        XCTAssertEqual(1100, Ai.expertBudget(1))
        XCTAssertEqual(1100, Ai.expertBudget(0))
        XCTAssertEqual(1100, Ai.expertBudget(-1))
        XCTAssertEqual(700, Ai.expertBudget(-2))
        XCTAssertEqual(500, Ai.expertBudget(-4))
    }

    func testEvaluatePaysTheBonusOnlyForCapturedMarkedCards() {
        let threat = card("threat", 6)
        let filler = card("filler", 6)
        let marked: Set<String> = ["threat"]

        func held(_ def: CardDef, _ owner: Owner) -> GameState {
            var cells = (0..<9).map { _ in Cell() }
            cells[4] = Cell(card: PlacedCard(def: def, owner: owner))
            return GameState(board: Board(cells: cells))
        }
        let withThreat = held(threat, .red)
        let withFiller = held(filler, .red)
        XCTAssertEqual(60, Ai.evaluate(withThreat, me: .red, threats: marked) - Ai.evaluate(withThreat, me: .red))
        XCTAssertEqual(0, Ai.evaluate(withFiller, me: .red, threats: marked) - Ai.evaluate(withFiller, me: .red))
        // still blue's card: nothing banked yet — the bonus is for CAPTURING it
        let blueSide = held(threat, .blue)
        XCTAssertEqual(0, Ai.evaluate(blueSide, me: .red, threats: marked) - Ai.evaluate(blueSide, me: .red))
    }
}
