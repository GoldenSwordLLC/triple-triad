import XCTest
@testable import TriadCore

final class ParityTests: XCTestCase {
    // .../ios/TriadCore/Tests/TriadCoreTests/ParityTests.swift → repo root
    static let repoRoot = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent()
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()

    func testEngineFixtures() throws {
        let dir = Self.repoRoot.appendingPathComponent("shared-data/fixtures")
        for file in try FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)
            where file.pathExtension == "json" {
            let name = file.deletingPathExtension().lastPathComponent
            if name.hasPrefix("color_") || name.hasPrefix("rng_") { continue }
            let fx = try JSONDecoder().decode(Fixture.self, from: Data(contentsOf: file))
            let state = fx.makeState()
            let cardIndex = state.hand(state.turn).firstIndex { $0.id == fx.place.card }!
            let result = Engine.place(state, cardIndex: cardIndex, cellIndex: fx.place.cell)
            XCTAssertEqual(result.flips, fx.expectFlips, "\(file.lastPathComponent) (\(fx.name))")
        }
    }

    func testColorResolutionMatrix() throws {
        struct ColorCase: Codable { let host: String, joiner: String, coin: Int, hostColor: String, joinerColor: String }
        struct Matrix: Codable { let cases: [ColorCase] }
        let url = Self.repoRoot.appendingPathComponent("shared-data/fixtures/color_resolution_matrix.json")
        let fx = try JSONDecoder().decode(Matrix.self, from: Data(contentsOf: url))
        for (i, c) in fx.cases.enumerated() {
            let (h, j) = ColorResolution.resolve(hostPref: ColorPref(rawValue: c.host)!,
                                                 joinerPref: ColorPref(rawValue: c.joiner)!, coin: c.coin)
            XCTAssertEqual(c.hostColor, h.rawValue, "case \(i)")
            XCTAssertEqual(c.joinerColor, j.rawValue, "case \(i)")
        }
    }

    func testRngRound1Sequence() throws {
        struct Expect: Codable { let coin: Int, cells: [Int], blueSample: [String], redSample: [String], first: Int }
        struct RngFixture: Codable { let seedHex: String, elemental: Bool, randomHands: Bool, pool: [String], expect: Expect }
        let url = Self.repoRoot.appendingPathComponent("shared-data/fixtures/rng_round1_sequence.json")
        let fx = try JSONDecoder().decode(RngFixture.self, from: Data(contentsOf: url))
        var r = SplitMix64(seed: seedFromHex(fx.seedHex))
        let coin = r.nextInt(2)
        var cells: [Int] = []
        for _ in 0..<9 { cells.append(fx.elemental && r.nextInt(5) == 0 ? r.nextInt(9) + 1 : 0) }
        let blueSample = fx.randomHands ? r.sample5(fx.pool) : Array(fx.pool.prefix(5))
        let redSample  = fx.randomHands ? r.sample5(fx.pool) : Array(fx.pool.prefix(5))
        let first = r.nextInt(2)
        if fx.expect.coin == -1 || fx.expect.blueSample.contains("GENERATE") {
            XCTFail("fixture not generated yet — run the JVM test first, paste its printed values into the JSON, commit")
        }
        XCTAssertEqual(fx.expect.coin, coin)
        XCTAssertEqual(fx.expect.cells, cells)
        XCTAssertEqual(fx.expect.blueSample, blueSample)
        XCTAssertEqual(fx.expect.redSample, redSample)
        XCTAssertEqual(fx.expect.first, first)
    }
}

struct Fixture: Codable {
    struct CellEntry: Codable { let card: String?; let owner: Owner?; let element: Element? }
    struct Hands: Codable { let blue: [String]; let red: [String] }
    struct Place: Codable { let card: String; let cell: Int }
    let name: String, rules: Rules, cards: [String: CardDef]
    let board: [CellEntry?], hands: Hands, turn: Owner, place: Place, expectFlips: [Flip]

    func makeState() -> GameState {
        // fixtures omit id → patch from map key (parity with the Kotlin loader)
        let patched: [String: CardDef] = Dictionary(uniqueKeysWithValues:
            cards.map { k, v in (k, CardDef(id: k, name: v.name, level: v.level, element: v.element,
                                            top: v.top, right: v.right, bottom: v.bottom, left: v.left,
                                            no: v.no, type: v.type)) })
        return GameState(
            board: Board(cells: board.map { e in
                Cell(card: e?.card.flatMap { id in PlacedCard(def: patched[id]!, owner: e!.owner ?? .blue) },
                     element: e?.element ?? .none)
            }),
            blueHand: hands.blue.map { patched[$0]! },
            redHand: hands.red.map { patched[$0]! },
            turn: turn, rules: rules)
    }
}
