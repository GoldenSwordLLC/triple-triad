package triad

import triad.core.*
import kotlin.test.*

class MemoryTest {
    private fun save(vararg pairs: Pair<String, CardMemory>, streak: Int = 0) =
        PlayerSave(cardMemory = pairs.toMap(), expertStreak = streak)

    @Test fun `every finished game records the cards the player brought`() {
        val s = PlayerSave().withGameMemory(listOf("a", "b", "c", "d", "e"), won = true, expert = false)
        for (id in listOf("a", "b", "c", "d", "e")) {
            assertEquals(CardMemory(played = 1, won = 1), s.cardMemory[id])
        }
        assertEquals(0, s.expertStreak)
    }

    @Test fun `duplicates in hand count once per game`() {
        val s = PlayerSave().withGameMemory(listOf("a", "a", "a", "b", "b"), won = false, expert = false)
        assertEquals(CardMemory(played = 1, won = 0), s.cardMemory["a"])
        assertEquals(CardMemory(played = 1, won = 0), s.cardMemory["b"])
    }

    @Test fun `expert wins are remembered card by card and deck by deck`() {
        var s = PlayerSave()
        s = s.withGameMemory(listOf("a", "b"), won = true, expert = true)
        assertEquals(setOf("a", "b"), s.expertBeatenWith.toSet())
        assertEquals(listOf("a,b"), s.expertWonDecks)
        s = s.withGameMemory(listOf("b", "c"), won = true, expert = true)
        assertEquals(setOf("a", "b", "c"), s.expertBeatenWith.toSet())
        assertEquals(listOf("a,b", "b,c"), s.expertWonDecks)
        s = s.withGameMemory(listOf("d"), won = false, expert = true)
        assertFalse(s.expertBeatenWith.contains("d"), "losses add nothing to the hunt list")
        s = s.withGameMemory(listOf("e"), won = true, expert = false)
        assertFalse(s.expertBeatenWith.contains("e"), "non-expert wins never feed it")
    }

    @Test fun `expert streak counts consecutive runs in both directions`() {
        var s = PlayerSave()
        repeat(3) { s = s.withGameMemory(listOf("a"), won = true, expert = true) }
        assertEquals(3, s.expertStreak)
        s = s.withGameMemory(listOf("a"), won = false, expert = true)
        assertEquals(-1, s.expertStreak)
        repeat(2) { s = s.withGameMemory(listOf("a"), won = false, expert = true) }
        assertEquals(-3, s.expertStreak)
        s = s.withGameMemory(listOf("a"), won = true, expert = true)
        assertEquals(1, s.expertStreak)
    }

    @Test fun `threats need enough plays and a winning record`() {
        val s = save(
            "proven" to CardMemory(played = 5, won = 4),
            "coinflip" to CardMemory(played = 4, won = 2),
            "unlucky" to CardMemory(played = 6, won = 1),
            "new" to CardMemory(played = 1, won = 1),
        )
        assertEquals(setOf("proven", "coinflip"), expertThreats(s))
    }

    @Test fun `threat list is capped, best records first`() {
        val m = (1..8).associate { "c$it" to CardMemory(played = 5, won = 8 - it) }
        assertEquals(5, expertThreats(PlayerSave(cardMemory = m)).size)
        assertEquals("c1", expertThreats(PlayerSave(cardMemory = m)).first())
    }

    @Test fun `expert effort scales with the streak`() {
        assertEquals(1800L, Ai.expertBudget(5))
        assertEquals(1400L, Ai.expertBudget(2))
        assertEquals(1100L, Ai.expertBudget(1))
        assertEquals(1100L, Ai.expertBudget(0))
        assertEquals(1100L, Ai.expertBudget(-1))
        assertEquals(700L, Ai.expertBudget(-2))
        assertEquals(500L, Ai.expertBudget(-4))
    }

    @Test fun `expert prefers the move that captures a marked card`() {
        fun card(id: String, r: Int) = CardDef(id, id, 5, Element.none, r, r, r, r)
        val threat = card("threat", 6)
        val filler = card("filler", 6)
        val marked = setOf("threat")

        fun stateWith(threatInMiddle: Boolean): GameState {
            val middle = if (threatInMiddle) threat else filler
            val board = List(9) { if (it == 4) Cell(card = PlacedCard(middle, Owner.blue)) else Cell() }
            return GameState(board = Board(board), blueHand = listOf(card("b1", 2)),
                             redHand = listOf(card("hunter", 9), card("r2", 2)), turn = Owner.red)
        }

        for (s in listOf(stateWith(true), stateWith(false))) {
            val m = Ai.hard(s, budgetMs = 300, threats = marked)
            val after = Engine.place(s, m.cardIndex, m.cellIndex).state
            assertEquals(Owner.red, after.board[4].card?.owner, "red should capture the middle card")
        }

        fun held(def: CardDef) = GameState(
            board = Board(List(9) { if (it == 4) Cell(card = PlacedCard(def, Owner.red)) else Cell() }))
        val withThreat = held(threat); val withFiller = held(filler)
        assertEquals(60, Ai.evaluate(withThreat, Owner.red, marked) - Ai.evaluate(withThreat, Owner.red))
        assertEquals(0, Ai.evaluate(withFiller, Owner.red, marked) - Ai.evaluate(withFiller, Owner.red))

        val blueSide = GameState(
            board = Board(List(9) { if (it == 4) Cell(card = PlacedCard(threat, Owner.blue)) else Cell() }))
        assertEquals(0, Ai.evaluate(blueSide, Owner.red, marked) - Ai.evaluate(blueSide, Owner.red))
    }
}
