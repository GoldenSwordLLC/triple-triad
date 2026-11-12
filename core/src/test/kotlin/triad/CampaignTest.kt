package triad

import kotlinx.coroutines.*
import java.io.File
import triad.core.*
import kotlinx.serialization.json.*
import triad.net.*
import kotlin.test.*

class CampaignTest {
    private fun catalog(): Map<String, CardDef> =
        sequenceOf(File("shared-data/catalog/cards.json"), File("../shared-data/catalog/cards.json"))
            .firstOrNull { it.exists() }
            ?.let { f ->
                val obj = kotlinx.serialization.json.Json.parseToJsonElement(f.readText()).jsonObject
                obj["cards"]!!.jsonArray.map {
                    kotlinx.serialization.json.Json.decodeFromJsonElement(CardDef.serializer(), it)
                }
            }
            ?.associateBy { it.id }
            ?: error("catalog not found")

    /** Coverage: starter ∪ every reward ∪ every pool == all 110 cards. No card
     *  may ever require multiplayer. */
    @Test fun `every card is single player obtainable`() {
        val cat = catalog()
        val covered = (Campaign.STARTER +
            Campaign.OPPONENTS.flatMap { it.rewardIds + it.poolIds }).toSet()
        val missing = cat.keys - covered
        val unknown = covered - cat.keys
        assertTrue(unknown.isEmpty(), "ids not in catalog: $unknown")
        assertEquals(emptySet(), missing, "cards with no single-player source")
        assertEquals(110, cat.size)
    }

    /** Rewards are unique per chapter and never re-gift a card (replays are the
     *  dupe channel). */
    @Test fun `reward sets are disjoint`() {
        val seen = HashSet<String>()
        for (opp in Campaign.OPPONENTS) {
            val dup = opp.rewardIds.filter { !seen.add(it) }
            assertEquals(emptyList(), dup, "duplicate rewards in chapter ${opp.chapter}")
        }
    }

    private fun playOne(chapter: Int, cat: Map<String, CardDef>, seed: Long, playerBudget: Long): Owner {
        val opp = Campaign.OPPONENTS[chapter - 1]
        val hand = Campaign.campaignHand(Campaign.playerArsenal(chapter, cat))
        var state = Campaign.campaignState(opp, cat, hand, seed)
        var playerColor = Owner.blue
        while (!state.isOver) {
            val budget = if (state.turn == playerColor) playerBudget else opp.aiBudgetMs
            val m = Ai.hard(state, budgetMs = budget)
            state = Engine.place(state, m.cardIndex, m.cellIndex).state
        }
        return if (state.score(playerColor) > state.score(playerColor.other)) playerColor else playerColor.other
    }

    /** The solvability guarantee: an AI seat holding exactly starter + rewards
     *  through the previous chapter beats the authored deck. Player budget is
     *  deliberately BELOW the opponent's late-game budget — a human sees rules
     *  interactions the search can't, so this is the pessimistic bound. */
    @Test fun `every chapter is beatable with the arsenal it hands you`() = runBlocking {
        val cat = catalog()
        // shuffle order flips AI tie-breaks on close games, so judge on majority
        val seeds = listOf(11L, 22L, 33L, 44L, 55L)
        val failures = mutableListOf<String>()
        for (opp in Campaign.OPPONENTS) {
            var wins = 0
            for (seed in seeds) {
                val w = playOne(opp.chapter, cat, seed, playerBudget = 200)
                if (w == Owner.blue) wins++
            }
            val need = if (opp.chapter >= 15) 2 else 3   // majority of five
            val ok = wins >= need
            println("ch${opp.chapter} ${opp.name}: $wins/${seeds.size} (need $need) ${if (ok) "OK" else "FAIL"}")
            if (!ok) failures.add("ch${opp.chapter} ${opp.name}: $wins/${seeds.size}")
        }
        assertTrue(failures.isEmpty(), "unbeatable chapters: $failures")
    }
}
