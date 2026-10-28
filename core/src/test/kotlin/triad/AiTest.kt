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
}
