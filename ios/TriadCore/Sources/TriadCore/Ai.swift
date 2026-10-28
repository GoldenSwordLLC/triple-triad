import Foundation

/// Port of the Kotlin `Ai` — same tiers, same evaluation. Iterative deepening under a
/// wall-clock budget keeps hard() responsive (mirrors the Kotlin budget design).
public struct AiMove {
    public let cardIndex: Int
    public let cellIndex: Int
    public init(cardIndex: Int, cellIndex: Int) {
        self.cardIndex = cardIndex; self.cellIndex = cellIndex
    }
}

public enum Ai {
    public static func legalMoves(_ state: GameState) -> [AiMove] {
        let empty = state.board.cells.indices.filter { state.board[$0].card == nil }
        return state.hand(state.turn).indices.flatMap { c in empty.map { AiMove(cardIndex: c, cellIndex: $0) } }
    }

    public static func easy(_ state: GameState) -> AiMove {
        legalMoves(state).randomElement()!
    }

    public static func medium(_ state: GameState) -> AiMove {
        legalMoves(state).max {
            evaluate(Engine.place(state, cardIndex: $0.cardIndex, cellIndex: $0.cellIndex).state,
                     me: state.turn)
            <
            evaluate(Engine.place(state, cardIndex: $1.cardIndex, cellIndex: $1.cellIndex).state,
                     me: state.turn)
        }!
    }

    // NOTE: reads both hands (assumes Open). Run off the main thread.
    // `threats` = card ids the player is proven with (learned memory); the search pays a
    // bonus for holding those cards, so Expert actively hunts them. Default = plain hard.
    public static func hard(_ state: GameState, budgetMs: Int = 900,
                            threats: Set<String> = [], threatBonus: Int = 60) -> AiMove {
        let deadline = DispatchTime.now().uptimeNanoseconds + UInt64(budgetMs) * 1_000_000
        let root = legalMoves(state)
        if root.count == 1 { return root[0] }
        var best = root[0]

        // One full pass at `depth`; throws TimeUp if the budget runs out mid-pass, in which
        // case `best` from the last completed depth is already sound.
        func search(depth: Int) throws {
            var alpha = Int.min + 1
            var bestAtDepth = best
            let ordered = root.map { m -> (AiMove, PlacementResult) in
                (m, Engine.place(state, cardIndex: m.cardIndex, cellIndex: m.cellIndex))
            }.sorted { $0.1.flips.count > $1.1.flips.count }
            for (m, r) in ordered {
                let score = try minimax(r.state, depth: depth - 1, alpha: alpha,
                                        beta: Int.max - 1, me: state.turn, deadline: deadline,
                                        threats: threats, threatBonus: threatBonus)
                if score > alpha { alpha = score; bestAtDepth = m }
            }
            best = bestAtDepth
        }

        var depth = 2
        while depth <= 9 {
            do { try search(depth: depth) } catch { return best }
            depth += 1
        }
        return best
    }

    private struct TimeUp: Error {}

    /// Labeled-do emulation without extra keywords: the `try? whileTrue: do {}` above is not
    /// valid Swift — kept readable by wrapping the loop in a throwing closure instead.
    private static func minimax(_ state: GameState, depth: Int, alpha: Int, beta: Int,
                                me: Owner, deadline: UInt64,
                                threats: Set<String>, threatBonus: Int) throws -> Int {
        if DispatchTime.now().uptimeNanoseconds > deadline { throw TimeUp() }
        if state.isOver || depth == 0 { return evaluate(state, me: me, threats: threats, threatBonus: threatBonus) }
        var alpha = alpha, beta = beta
        let moves = legalMoves(state).map { m -> (AiMove, PlacementResult) in
            (m, Engine.place(state, cardIndex: m.cardIndex, cellIndex: m.cellIndex))
        }.sorted { $0.1.flips.count > $1.1.flips.count }
        if state.turn == me {
            var best = Int.min + 1
            for (_, r) in moves {
                best = max(best, try minimax(r.state, depth: depth - 1, alpha: alpha, beta: beta, me: me,
                                             deadline: deadline, threats: threats, threatBonus: threatBonus))
                if best >= beta { return best }
                alpha = max(alpha, best)
            }
            return best
        } else {
            var best = Int.max - 1
            for (_, r) in moves {
                best = min(best, try minimax(r.state, depth: depth - 1, alpha: alpha, beta: beta, me: me,
                                             deadline: deadline, threats: threats, threatBonus: threatBonus))
                if best <= alpha { return best }
                beta = min(beta, best)
            }
            return best
        }
    }

    public static func evaluate(_ state: GameState, me: Owner,
                                threats: Set<String> = [], threatBonus: Int = 60) -> Int {
        var score = (state.score(me) - state.score(me.other)) * 10
        for i in state.board.cells.indices {
            let cell = state.board[i]
            guard let card = cell.card else { continue }
            if card.owner == me && threats.contains(card.def.id) { score += threatBonus }
            var exposure = 0
            for side in Side.allCases {
                guard let n = Engine.neighborIndex(i, side) else { continue }
                if state.board[n].card == nil {
                    exposure += 10 - Engine.effectiveValue(card.def, side,
                                                           cellElement: cell.element,
                                                           elementalOn: state.rules.elemental)
                }
            }
            score += card.owner == me ? -exposure : exposure
        }
        return score
    }

    /// Expert effort follows the rivalry's history (PlayerSave.expertStreak): deeper search
    /// while the player keeps winning, relaxed while the player keeps losing.
    public static func expertBudget(_ streak: Int) -> Int {
        switch streak {
        case 3...: return 1800
        case 2: return 1400
        case ...(-3): return 500
        case -2: return 700
        default: return 1100
        }
    }
}

public enum TradeAi {
    // FF8-flavored: winners take the best cards. Level first, then rank total.
    public static func pick(candidates: [CardDef], n: Int) -> [String] {
        candidates.sorted { a, b in
            if a.level != b.level { return a.level > b.level }
            return (a.top + a.right + a.bottom + a.left) > (b.top + b.right + b.bottom + b.left)
        }.prefix(n).map { $0.id }
    }
}
