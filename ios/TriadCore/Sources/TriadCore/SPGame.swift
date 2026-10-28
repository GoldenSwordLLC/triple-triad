import Combine
import Foundation

/// iOS mirror of the Android SPGameViewModel — single player vs AI (or pass & play),
/// driving the same Engine with the player's battle deck. EXPERT counter-picks its
/// hand around yours from the level 8-10 pool.
public final class SinglePlayerGame: ObservableObject {
    @Published public private(set) var state: GameState
    @Published public private(set) var lastFlips: [Flip] = []
    @Published public private(set) var selected: Int?
    @Published public private(set) var aiThinking = false
    public let passAndPlay: Bool

    private let catalog: [CardDef]
    private let difficulty: String
    private let myDeck: [CardDef]
    private let delayMs: UInt32
    private var recorded = false
    private let onRecord: (Bool, Bool) -> Void
    private let onMemory: ([String], Bool) -> Void
    private let threats: Set<String>          // Expert memory: the player's proven cards
    private let expertBudgetMs: Int           // Expert memory: effort follows the streak
    private let aiQueue = DispatchQueue(label: "triad.ai", qos: .userInitiated)

    // AI card quality scales with difficulty; pass & play red stays casual.
    private let aiRange: ClosedRange<Int>

    public init(catalog: [String: CardDef], difficulty: String, myDeck: [CardDef],
                delayMs: UInt32 = 450, threats: Set<String> = [], expertBudgetMs: Int = 1400,
                onRecord: @escaping (Bool, Bool) -> Void,
                onMemory: @escaping ([String], Bool) -> Void = { _, _ in }) {
        self.catalog = Array(catalog.values)
        self.difficulty = difficulty
        self.myDeck = myDeck
        self.delayMs = delayMs
        self.threats = threats
        self.expertBudgetMs = expertBudgetMs
        self.onRecord = onRecord
        self.onMemory = onMemory
        self.passAndPlay = difficulty == "pass"
        switch difficulty {
        case "easy": aiRange = 1...3
        case "hard": aiRange = 3...10
        case "expert": aiRange = 8...10   // GFs and character cards only
        case "pass": aiRange = 1...5
        default: aiRange = 1...6
        }
        state = Self.newGame(catalog: self.catalog, difficulty: difficulty, myDeck: myDeck, aiRange: aiRange)
        maybeAiTurn()   // red moves first in ~half of games — the AI must open too
    }

    private static func newGame(catalog: [CardDef], difficulty: String, myDeck: [CardDef],
                                aiRange: ClosedRange<Int>) -> GameState {
        let byLevel = Dictionary(grouping: catalog) { $0.level }
        func sample() -> [CardDef] {
            (0..<5).compactMap { _ in
                aiRange.randomElement().flatMap { byLevel[$0]?.randomElement() }
            }
        }
        let red: [CardDef] = difficulty == "expert" ? counterDeck(myDeck: myDeck, catalog: catalog) : sample()
        return GameState(board: Board(), blueHand: myDeck.shuffled(), redHand: red,
                         turn: Bool.random() ? .blue : .red, rules: Rules())
    }

    /// For each of your cards, field the strongest available GF/character card whose
    /// edges beat that card's edges.
    private static func counterDeck(myDeck: [CardDef], catalog: [CardDef]) -> [CardDef] {
        var pool = catalog.filter { $0.level >= 8 }
        return myDeck.map { p in
            let pick = pool.max { a, b in
                func score(_ c: CardDef) -> Int {
                    Side.allCases.reduce(0) { $0 + (c.rank($1) > p.rank($1) ? 100 : 0) + (c.rank($1) - p.rank($1)) }
                }
                return score(a) < score(b)
            } ?? catalog.randomElement()!
            pool.removeAll { $0.id == pick.id }
            return pick
        }
    }

    public func rematch() {
        recorded = false; aiThinking = false; lastFlips = []; selected = nil
        state = Self.newGame(catalog: catalog, difficulty: difficulty, myDeck: myDeck, aiRange: aiRange)
        maybeAiTurn()
    }

    public func tapCard(index: Int) {
        if state.isOver || aiThinking { return }
        selected = selected == index ? nil : index
    }

    public func tapCell(cell: Int) {
        guard let sel = selected, !state.isOver, !aiThinking, state.board[cell].card == nil else { return }
        if !passAndPlay && state.turn != .blue { return }
        guard sel < state.hand(state.turn).count else { return }
        selected = nil
        apply(sel, cell)
    }

    private func apply(_ cardIndex: Int, _ cell: Int) {
        let r = Engine.place(state, cardIndex: cardIndex, cellIndex: cell)
        state = r.state; lastFlips = r.flips
        if state.isOver { finish() } else { maybeAiTurn() }
    }

    private func maybeAiTurn() {
        if passAndPlay || state.isOver || state.turn != .red || aiThinking { return }
        aiThinking = true
        aiQueue.async { [weak self] in
            guard let self else { return }
            if self.delayMs > 0 { usleep(self.delayMs * 1000) }
            let move: AiMove
            switch self.difficulty {
            case "easy": move = Ai.easy(self.state)
            case "hard": move = Ai.hard(self.state, budgetMs: 900)
            case "expert": move = Ai.hard(self.state, budgetMs: self.expertBudgetMs, threats: self.threats)
            default: move = Ai.medium(self.state)
            }
            DispatchQueue.main.async {
                let r = Engine.place(self.state, cardIndex: move.cardIndex, cellIndex: move.cellIndex)
                self.state = r.state; self.lastFlips = r.flips; self.aiThinking = false
                if self.state.isOver { self.finish() }
            }
        }
    }

    private func finish() {
        guard !recorded else { return }
        recorded = true
        let blue = state.score(.blue), red = state.score(.red)
        let won = blue > red
        onRecord(won, blue == red)
        // the cards you brought feed the AI memory (Expert reads it next sitting)
        onMemory(myDeck.map { $0.id }, won)
    }
}
