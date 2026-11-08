package triad

import triad.core.*
import kotlin.test.*

class AiTest {
    private fun opening(): GameState {
        val deck = (1..10).flatMap { lvl ->
            (1..2).map { i ->
                val rank = 1 + lvl * 8 / 10
                CardDef("c${lvl}_$i", "C${lvl}_$i", lvl, Element.none, rank, rank, rank, rank)
            }
        }
        return GameState(board = Board(List(9) { Cell() }), blueHand = deck.take(5), redHand = deck.drop(5))
    }

    @Test fun `all tiers return legal moves on an opening`() {
        val s = opening()
        for (m in listOf(Ai.easy(s), Ai.medium(s), Ai.hard(s))) {
            assertTrue(m.cardIndex in s.hand(s.turn).indices, "cardIndex out of hand")
            assertTrue(m.cellIndex in 0..8, "cellIndex out of board")
        }
    }

    @Test fun `hard answers within its time budget`() {
        val s = opening()
        val t0 = System.nanoTime()
        val m = Ai.hard(s, budgetMs = 500)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue(m.cellIndex in 0..8)
        assertTrue(ms < 2_500, "hard took ${ms}ms against a 500ms budget")
    }

    @Test fun `a full ai-vs-ai game always finishes`() {
        var s = opening().copy(turn = Owner.red)
        var plies = 0
        var flip = false
        while (!s.isOver) {
            val m = if (flip) Ai.medium(s) else Ai.easy(s)
            s = Engine.place(s, m.cardIndex, m.cellIndex).state
            flip = !flip
            if (++plies > 20) fail("game did not finish in 20 plies")
        }
        assertEquals(9, plies)
    }

    @Test fun `bestHand picks the strongest distinct cards`() {
        val pool = (1..20).map { i ->
            val r = 1 + i / 2
            CardDef("c$i", "C$i", 1, Element.none, r, r, r, r)
        }
        val hand = Ai.bestHand(pool, 5)
        assertEquals(5, hand.size)
        assertEquals(5, hand.distinctBy { it.id }.size)
        // every pick must come from the top of the strength ranking (the shuffle
        // window is n+3), and the strongest card is always in
        val ranked = pool.sortedByDescending { it.top + it.right + it.bottom + it.left }
        assertTrue(ranked.take(8).containsAll(hand), "hand=${hand.map { it.id }}")
    }

    @Test fun `medium sees replies and dominates random play`() {
        // MIRRORED hands on both sides: card quality is identical, so the only
        // difference between blue (random) and red (2-ply search) is play quality
        val hand = listOf(
            CardDef("wall", "W", 1, Element.none, 6, 1, 6, 1),
            CardDef("spike", "S", 1, Element.none, 1, 6, 1, 6),
            CardDef("round", "R", 1, Element.none, 4, 4, 4, 4),
            CardDef("top", "T", 1, Element.none, 6, 3, 3, 3),
            CardDef("edge", "E", 1, Element.none, 2, 5, 5, 2),
        )
        var wins = 0
        repeat(10) { g ->
            val rng = kotlin.random.Random(g)
            var s = GameState(board = Board(List(9) { Cell() }),
                blueHand = hand, redHand = hand,
                turn = if (rng.nextInt(2) == 0) Owner.blue else Owner.red)
            // blue = easy (random), red = medium (2-ply search)
            while (!s.isOver) {
                val m = if (s.turn == Owner.blue) Ai.easy(s, rng) else Ai.medium(s)
                s = Engine.place(s, m.cardIndex, m.cellIndex).state
            }
            if (s.score(Owner.red) > s.score(Owner.blue)) wins++
        }
        assertTrue(wins >= 8, "medium beat easy only $wins/10 with equal decks")
    }
}
