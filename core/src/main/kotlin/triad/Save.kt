package triad.core

import kotlinx.serialization.Serializable
import kotlin.math.pow

@Serializable
data class NamedDeck(val id: String, val name: String, val ids: List<String>)

@Serializable
data class CardMemory(val played: Int = 0, val won: Int = 0)

@Serializable
data class PlayerSave(
    val collection: Map<String, Int> = emptyMap(),
    val decks: List<NamedDeck> = emptyList(),
    val activeDeck: String? = null,
    val neverUse: List<String> = emptyList(),
    val neverFace: List<String> = emptyList(),
    val wins: Int = 0, val losses: Int = 0, val draws: Int = 0,
    val tutorialSeen: Boolean = false,
    val cardMemory: Map<String, CardMemory> = emptyMap(),
    val expertStreak: Int = 0,
    val pendingPack: List<String> = emptyList(),
    val expertBeatenWith: List<String> = emptyList(),
    val expertWonDecks: List<String> = emptyList(),
)

sealed interface DeckError {
    data class UnknownCard(val id: String) : DeckError
    data class NotOwned(val id: String, val needed: Int, val owned: Int) : DeckError
    data class WrongSize(val size: Int) : DeckError
    data class Banned(val id: String) : DeckError
    data class Duplicate(val id: String) : DeckError
}

fun validateDeck(deck: List<String>, catalog: Set<String>, collection: Map<String, Int>,
                 enforceBans: Boolean, neverUse: Set<String>): List<DeckError> {
    val errors = mutableListOf<DeckError>()
    if (deck.size !in 5..10) { errors += DeckError.WrongSize(deck.size); return errors }
    for ((id, n) in deck.groupingBy { it }.eachCount()) {
        if (id !in catalog) errors += DeckError.UnknownCard(id)
        else if ((collection[id] ?: 0) < n) errors += DeckError.NotOwned(id, n, collection[id] ?: 0)
        if (n > 1) errors += DeckError.Duplicate(id)
        if (enforceBans && id in neverUse) errors += DeckError.Banned(id)
    }
    return errors
}

fun PlayerSave.activeDeckCards(): List<String> =
    decks.firstOrNull { it.id == activeDeck }?.ids ?: emptyList()

fun PlayerSave.withRecord(won: Boolean, drawn: Boolean): PlayerSave = copy(
    wins = wins + if (won && !drawn) 1 else 0,
    losses = losses + if (!won && !drawn) 1 else 0,
    draws = draws + if (drawn) 1 else 0)

fun PlayerSave.withGameMemory(handIds: List<String>, won: Boolean, expert: Boolean): PlayerSave {
    val m = cardMemory.toMutableMap()
    for (id in handIds.toSet()) {
        val s = m[id] ?: CardMemory()
        m[id] = CardMemory(played = s.played + 1, won = s.won + if (won) 1 else 0)
    }
    val streak = if (!expert) expertStreak
        else if (won) expertStreak.coerceAtLeast(0) + 1
        else expertStreak.coerceAtMost(0) - 1
    var beaten = expertBeatenWith
    var decks = expertWonDecks
    if (expert && won) {
        beaten = (beaten + handIds.toSet()).distinct()
        decks = (decks + listOf(handIds.sorted().joinToString(","))).distinct()
    }
    return copy(cardMemory = m, expertStreak = streak, expertBeatenWith = beaten, expertWonDecks = decks)
}

fun deckSignature(ids: List<String>): String = ids.sorted().joinToString(",")

fun expertThreats(save: PlayerSave, minPlays: Int = 3, minWinRate: Double = 0.5, limit: Int = 5): Set<String> =
    save.cardMemory.filterValues { it.played >= minPlays && it.won.toDouble() / it.played >= minWinRate }
        .entries.sortedWith(compareByDescending<Map.Entry<String, CardMemory>> { it.value.won }
            .thenByDescending { it.value.played })
        .take(limit).map { it.key }.toSet()

fun PlayerSave.applyTransfers(transfers: List<CardTransfer>, myColor: Owner): PlayerSave {
    val c = collection.toMutableMap()
    transfers.forEach { t ->
        if (t.to == myColor) c[t.card.id] = (c[t.card.id] ?: 0) + 1
        if (t.from == myColor) c[t.card.id] = ((c[t.card.id] ?: 1) - 1).coerceAtLeast(0)
    }
    return copy(collection = c)
}

val saveJson = kotlinx.serialization.json.Json { prettyPrint = true; ignoreUnknownKeys = true }

fun PlayerSave.addCards(cards: List<CardDef>): PlayerSave {
    val c = collection.toMutableMap()
    cards.forEach { c[it.id] = (c[it.id] ?: 0) + 1 }
    return copy(collection = c)
}

val EXPERT_EXCLUSIVES = setOf("doomtrain", "edea", "rinoa", "squall", "seifer")

fun packSources(card: CardDef, exclusives: Set<String> = EXPERT_EXCLUSIVES): List<String> = when {
    card.id in exclusives -> listOf("expert")
    card.level <= 1 -> listOf("easy")
    card.level <= 2 -> listOf("easy", "medium")
    card.level <= 3 -> listOf("easy", "medium", "hard")
    card.level <= 6 -> listOf("medium", "hard")
    card.level <= 8 -> listOf("hard")
    else -> listOf("hard", "expert")
}

fun packDropRate(card: CardDef, tier: String, catalog: Collection<CardDef>,
                 exclusives: Set<String> = EXPERT_EXCLUSIVES): Double {
    val byLevel = catalog.groupBy { it.level }
    fun drawP(range: IntRange, pool: (CardDef) -> Boolean): Double {
        if (card.level !in range) return 0.0
        val inLevel = byLevel[card.level]?.count(pool) ?: 0
        if (inLevel == 0) return 0.0

        return (1.0 / range.count()) * (1.0 / inLevel)
    }
    fun over(p: Double, draws: Int) = 1.0 - (1.0 - p).pow(draws)
    val notExclusive: (CardDef) -> Boolean = { it.id !in exclusives }
    return when (tier.lowercase()) {
        "easy" -> over(drawP(1..3) { true }, 3)
        "medium" -> 1.0 - (1.0 - drawP(4..6) { true }) * (1.0 - drawP(2..6) { true }).pow(3)
        "hard" -> 1.0 - (1.0 - drawP(7..10, notExclusive)) * (1.0 - drawP(3..10, notExclusive)).pow(2)
        "expert" -> {
            val jackpot = if (card.id in exclusives)
                1.0 / catalog.count { it.id in exclusives }.coerceAtLeast(1) else 0.0
            1.0 - (1.0 - jackpot) * (1.0 - drawP(9..10) { true })
        }
        else -> 0.0
    }
}

fun rollPack(difficulty: String, catalog: Collection<CardDef>, rng: kotlin.random.Random,
             exclusives: Set<String> = EXPERT_EXCLUSIVES): List<CardDef> {
    val byLevel = catalog.groupBy { it.level }

    fun drawFrom(range: IntRange, pool: (CardDef) -> Boolean = { true }): CardDef {
        var lvl = range.random(rng)
        var card = byLevel[lvl]?.filter(pool)?.randomOrNull(rng)
        var attempts = 0
        while (card == null && attempts++ < 20) {
            lvl = range.random(rng)
            card = byLevel[lvl]?.filter(pool)?.randomOrNull(rng)
        }
        return card ?: catalog.filter(pool).randomOrNull(rng) ?: catalog.random(rng)
    }

    return when (difficulty.lowercase()) {
        "easy" -> List(3) { drawFrom(1..3) }
        "medium" -> {
            val floor = drawFrom(4..6)
            listOf(floor) + List(3) { drawFrom(2..6) }
        }
        "expert" -> {

            val jackpot = catalog.filter { it.id in exclusives }.randomOrNull(rng) ?: drawFrom(9..10)
            listOf(jackpot, drawFrom(9..10))
        }
        else -> {
            val jackpot = drawFrom(7..10) { it.id !in exclusives }
            listOf(jackpot) + List(2) { drawFrom(3..10) { it.id !in exclusives } }
        }
    }
}
