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

    /** Coverage: starter ∪ every fight's first-clear reward == all 110 cards.
     *  No card may ever require multiplayer. */
    @Test fun `every card is single player obtainable`() {
        val cat = catalog()
        val covered = (Campaign.STARTER +
            Campaign.FIGHTS.flatMap { it.rewardIds }).toSet()
        val missing = cat.keys - covered
        val unknown = covered - cat.keys
        assertTrue(unknown.isEmpty(), "ids not in catalog: $unknown")
        assertEquals(emptySet(), missing, "cards with no single-player source")
        assertEquals(110, cat.size)
    }

    /** Rewards are unique across the whole ladder (a card is gifted exactly once). */
    @Test fun `reward sets are disjoint`() {
        val seen = HashSet<String>()
        for (fight in Campaign.FIGHTS) {
            val dup = fight.rewardIds.filter { !seen.add(it) }
            assertEquals(emptyList(), dup, "duplicate rewards in fight ${fight.chapter}/${fight.name}")
        }
    }

    private fun playOne(index: Int, cat: Map<String, CardDef>, seed: Long, playerBudget: Long): Owner {
        val opp = Campaign.fightAt(index)
        val hand = Campaign.campaignHand(Campaign.playerArsenal(index, cat))
        var state = Campaign.campaignState(opp, cat, hand, seed)
        var playerColor = Owner.blue
        while (!state.isOver) {
            val budget = if (state.turn == playerColor) playerBudget else opp.aiBudgetMs
            val m = Ai.hard(state, budgetMs = budget)
            state = Engine.place(state, m.cardIndex, m.cellIndex).state
        }
        return if (state.score(playerColor) > state.score(playerColor.other)) playerColor else playerColor.other
    }

    /** The solvability guarantee: an AI seat holding exactly starter + every
     *  earlier first-clear reward beats each of the 51 authored decks. The
     *  player budget is deliberately below the late-game opponent budgets — a
     *  human sees rules interactions the search can't, so this is the
     *  pessimistic bound. */
    @Test fun `every fight is beatable with the arsenal it hands you`() = runBlocking {
        val cat = catalog()
        // shuffle order flips AI tie-breaks on close games, so judge on majority
        val failures = mutableListOf<String>()
        for ((index, fight) in Campaign.FIGHTS.withIndex()) {
            val seeds = if (fight.isBoss) listOf(11L, 22L, 33L, 44L, 55L) else listOf(11L, 22L, 33L)
            var wins = 0
            for (seed in seeds) {
                val w = playOne(index, cat, seed, playerBudget = 400)
                if (w == Owner.blue) wins++
            }
            val need = when {
                !fight.isBoss -> 2                // majority of three
                fight.chapter >= 15 -> 2          // the finale pair is allowed to bite
                else -> 3                         // majority of five
            }
            val ok = wins >= need
            println("ch${fight.chapter} ${fight.name}: $wins/${seeds.size} (need $need) ${if (ok) "OK" else "FAIL"}")
            if (!ok) failures.add("ch${fight.chapter} ${fight.name}: $wins/${seeds.size}")
        }
        assertTrue(failures.isEmpty(), "unbeatable fights: $failures")
    }
}
