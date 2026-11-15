package triad

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import triad.core.*
import java.io.File
import kotlin.test.*

class DiagTest {
    private fun catalog(): Map<String, CardDef> {
        val f = sequenceOf(File("shared-data/catalog/cards.json"), File("../shared-data/catalog/cards.json")).first { it.exists() }
        val obj = Json.parseToJsonElement(f.readText()).jsonObject
        return obj["cards"]!!.jsonArray.map { Json.decodeFromJsonElement(CardDef.serializer(), it) }.associateBy { it.id }
    }

    @Test fun `ch5 carlo move by move`() {
        val cat = catalog()
        val idx = Campaign.FIGHTS.indexOfFirst { it.name == "Carlo" }
        val fight = Campaign.FIGHTS[idx]
        val hand = Campaign.campaignHand(Campaign.playerArsenal(idx, cat))
        println("player hand: " + hand.map { "${it.id}(${it.top}${it.right}${it.bottom}${it.left})" })
        println("opp deck: " + fight.deckIds.map { id ->
            val c = cat.getValue(id); "${id}(${c.top}${c.right}${c.bottom}${c.left})"
        })
        println("rules: same=${fight.same} plus=${fight.plus} wall=${fight.sameWall} elem=${fight.elemental}")
        var s = Campaign.campaignState(fight, cat, hand, seed = 11L)
        println("elements on board: " + s.board.cells.mapIndexed { i, c -> if (c.element != Element.none) "$i=${c.element}" else null }.filterNotNull())
        var moves = 0
        while (!s.isOver && moves < 12) {
            val budget = if (s.turn == Owner.blue) 400 else fight.aiBudgetMs
            val m = Ai.hard(s, budgetMs = budget)
            println("move $moves: ${s.turn} plays ${s.hand(s.turn)[m.cardIndex].id} at ${m.cellIndex}")
            s = Engine.place(s, m.cardIndex, m.cellIndex).state
            println("  score blue=${s.score(Owner.blue)} red=${s.score(Owner.red)}")
            moves++
        }
        println("final: blue=${s.score(Owner.blue)} red=${s.score(Owner.red)} over=${s.isOver}")
    }
}
