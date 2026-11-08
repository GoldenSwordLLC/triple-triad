package triad.core

import kotlin.random.Random

data class Move(val cardIndex: Int, val cellIndex: Int)

object Ai {
    fun legalMoves(state: GameState): List<Move> {
        val empty = state.board.cells.indices.filter { state.board[it].card == null }
        return state.hand(state.turn).indices.flatMap { c -> empty.map { Move(c, it) } }
    }

    fun easy(state: GameState, rng: Random = Random.Default): Move = legalMoves(state).random(rng)

    // 1-ply greedy could not see the player's reply at all; two plies (my best move,
    // your best answer) is the smallest search that doesn't hand cards back for free
    fun medium(state: GameState): Move = hard(state, budgetMs = 250, maxDepth = 2)

    fun hard(state: GameState, budgetMs: Long = 900, maxDepth: Int = 99,
             threats: Set<String> = emptySet(), threatBonus: Int = 60): Move {
        val deadline = System.nanoTime() + budgetMs * 1_000_000
        val root = legalMoves(state)
        if (root.size == 1) return root.first()
        var best = root.first()
        var depth = 2
        try {
            while (depth <= 9 && depth <= maxDepth) {
                var alpha = Int.MIN_VALUE + 1
                var bestAtDepth = best
                val ordered = root.map { m -> m to Engine.place(state, m.cardIndex, m.cellIndex) }
                    .sortedByDescending { it.second.flips.size }
                for ((m, r) in ordered) {
                    val score = minimax(r.state, depth - 1, alpha, Int.MAX_VALUE - 1, state.turn, deadline, threats, threatBonus)
                    if (score > alpha) { alpha = score; bestAtDepth = m }
                }
                best = bestAtDepth
                depth++
            }
        } catch (_: TimeUp) {

        }
        return best
    }

    private class TimeUp : RuntimeException()

    private fun minimax(state: GameState, depth: Int, alphaIn: Int, betaIn: Int, me: Owner, deadline: Long,
                        threats: Set<String>, threatBonus: Int): Int {
        if (System.nanoTime() > deadline) throw TimeUp()
        if (state.isOver || depth == 0) return evaluate(state, me, threats, threatBonus)
        var alpha = alphaIn; var beta = betaIn
        val moves = legalMoves(state).map { m -> m to Engine.place(state, m.cardIndex, m.cellIndex) }
            .sortedByDescending { it.second.flips.size }
        if (state.turn == me) {
            var best = Int.MIN_VALUE + 1
            for ((_, r) in moves) {
                best = maxOf(best, minimax(r.state, depth - 1, alpha, beta, me, deadline, threats, threatBonus))
                if (best >= beta) return best
                alpha = maxOf(alpha, best)
            }
            return best
        } else {
            var best = Int.MAX_VALUE - 1
            for ((_, r) in moves) {
                best = minOf(best, minimax(r.state, depth - 1, alpha, beta, me, deadline, threats, threatBonus))
                if (best <= alpha) return best
                beta = minOf(beta, best)
            }
            return best
        }
    }

    fun evaluate(state: GameState, me: Owner, threats: Set<String> = emptySet(), threatBonus: Int = 60): Int {
        var score = (state.score(me) - state.score(me.other)) * 10
        val oppHand = state.hand(me.other)
        val elemental = state.rules.elemental
        for (i in state.board.cells.indices) {
            val cell = state.board[i]; val card = cell.card ?: continue
            if (card.owner == me && card.def.id in threats) score += threatBonus
            var exposure = 0
            var openSides = 0
            var safe = true
            for (side in Side.entries) {
                val n = Engine.neighborIndex(i, side) ?: continue
                if (state.board[n].card != null) continue
                openSides++
                val v = Engine.effectiveValue(card.def, side, cell.element, elemental)
                exposure += 10 - v
                // a card can only ever be attacked through a side that is open NOW;
                // if no remaining opponent card beats any open side, this card is
                // locked in for good — the core endgame concept the raw score misses
                if (oppHand.any { o -> o.rank(side.opposite()) > v }) safe = false
            }
            if (openSides > 0 && safe) score += if (card.owner == me) 45 else -45
            score += if (card.owner == me) -exposure else exposure
        }
        return score
    }

    private fun Side.opposite(): Side = Side.entries[(ordinal + 2) % 4]

    // Expert is the top tier: real pressure on win streaks, and far less mercy on
    // losing ones (a soft Expert is an Expert nobody respects)
    fun expertBudget(streak: Int): Long = when {
        streak >= 3 -> 3000L
        streak >= 2 -> 2200L
        streak <= -3 -> 1100L
        streak <= -2 -> 1300L
        else -> 1600L
    }

    /** Curated AI hand: the strongest distinct cards in [pool], balanced (a card with
     *  one soft side is a liability, so the lowest side is weighted), drawn from the
     *  top candidates with a shuffle so repeat matches aren't identical. Random
     *  hands made every tier below Expert a pushover regardless of search quality. */
    fun bestHand(pool: List<CardDef>, n: Int = 5, rng: Random = Random.Default): List<CardDef> {
        fun strength(c: CardDef) =
            c.top + c.right + c.bottom + c.left + 3 * minOf(c.top, c.right, c.bottom, c.left)
        val ranked = pool.distinctBy { it.id }.sortedByDescending(::strength)
        if (ranked.size <= n) return ranked.take(n).shuffled(rng)
        return ranked.take(n + 3).shuffled(rng).take(n)
    }
}

object TradeAi {

    fun pick(candidates: List<CardDef>, n: Int): List<String> =
        candidates.sortedWith(compareByDescending<CardDef> { it.level }
            .thenByDescending { it.top + it.right + it.bottom + it.left })
            .take(n).map { it.id }
}
