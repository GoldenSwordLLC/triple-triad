package triad.core

object Engine {
    private const val WALL = 10

    fun neighborIndex(index: Int, side: Side): Int? {
        val row = index / 3; val col = index % 3
        return when (side) {
            Side.top    -> if (row > 0) index - 3 else null
            Side.bottom -> if (row < 2) index + 3 else null
            Side.left   -> if (col > 0) index - 1 else null
            Side.right  -> if (col < 2) index + 1 else null
        }
    }

    fun effectiveValue(card: CardDef, side: Side, cellElement: Element, elementalOn: Boolean): Int {
        if (!elementalOn || cellElement == Element.none) return card.rank(side)
        return card.rank(side) + if (card.element == cellElement) 1 else -1
    }

    fun place(state: GameState, cardIndex: Int, cellIndex: Int): PlacementResult {
        val hand = state.hand(state.turn)
        require(cardIndex in hand.indices) { "cardIndex out of bounds" }
        require(cellIndex in 0..8 && state.board[cellIndex].card == null) { "cell $cellIndex unavailable" }
        val card = hand[cardIndex]
        val me = state.turn

        val afterPlace = state.board.withCard(cellIndex, PlacedCard(card, me))
        val flips = computeFlips(afterPlace, cellIndex, state.rules)

        var board = afterPlace
        flips.forEach { board = board.withOwner(it.cell, me) }

        val next = state.copy(
            board = board,
            blueHand = if (me == Owner.blue) state.blueHand.filterIndexed { i, _ -> i != cardIndex } else state.blueHand,
            redHand  = if (me == Owner.red)  state.redHand.filterIndexed  { i, _ -> i != cardIndex } else state.redHand,
            turn = me.other
        )
        return PlacementResult(next, flips)
    }

    private data class Neighbor(val side: Side, val index: Int, val card: PlacedCard)

    internal fun computeFlips(board: Board, placedIndex: Int, rules: Rules): List<Flip> {
        val me = board[placedIndex].card!!.owner

        fun effAt(b: Board, index: Int, side: Side) =
            effectiveValue(b[index].card!!.def, side, b[index].element, rules.elemental)
        fun myEff(side: Side) =
            effectiveValue(board[placedIndex].card!!.def, side, board[placedIndex].element, rules.elemental)

        val neighbors: List<Neighbor> = Side.entries.mapNotNull { side ->
            neighborIndex(placedIndex, side)?.let { idx -> board[idx].card?.let { Neighbor(side, idx, it) } }
        }

        val basic = neighbors.filter { n ->
            n.card.owner != me && myEff(n.side) > effAt(board, n.index, n.side.opposite)
        }.map { Flip(it.index, FlipCause.basic, 0) }

        var special: List<Flip> = emptyList()
        if (rules.same) {
            val matches = Side.entries.count { side ->
                val idx = neighborIndex(placedIndex, side)
                when {
                    idx == null -> rules.sameWall && myEff(side) == WALL
                    board[idx].card == null -> false
                    else -> myEff(side) == effAt(board, idx, side.opposite)
                }
            }
            if (matches >= 2) {
                special = neighbors.filter { n ->
                    n.card.owner != me && myEff(n.side) == effAt(board, n.index, n.side.opposite)
                }.map { Flip(it.index, FlipCause.same, 0) }
            }
        }

        if (special.isEmpty() && rules.plus && neighbors.size >= 2) {
            special = neighbors
                .groupBy { n -> myEff(n.side) + effAt(board, n.index, n.side.opposite) }
                .values.filter { it.size >= 2 }.flatten()
                .filter { it.card.owner != me }
                .map { Flip(it.index, FlipCause.plus, 0) }
        }

        var work = board
        (basic + special).forEach { work = work.withOwner(it.cell, me) }

        val combo = mutableListOf<Flip>()
        var frontier = special.map { it.cell }
        var wave = 1
        while (frontier.isNotEmpty()) {
            val next = mutableListOf<Int>()
            for (src in frontier) {
                for (side in Side.entries) {
                    val dst = neighborIndex(src, side) ?: continue
                    val target = work[dst].card ?: continue
                    if (target.owner == me) continue
                    if (effAt(work, src, side) > effAt(work, dst, side.opposite)) {
                        work = work.withOwner(dst, me)
                        combo += Flip(dst, FlipCause.combo, wave)
                        next += dst
                    }
                }
            }
            frontier = next
            wave++
        }
        return basic + special + combo
    }
}
