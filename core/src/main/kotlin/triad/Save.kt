package triad.core

import kotlinx.serialization.Serializable

@Serializable
data class NamedDeck(val id: String, val name: String, val ids: List<String>)

/** Bump to invalidate every existing save. The 1.0 decision was a hard wipe —
 *  saves written before the bump decode with schema 0 and load as a fresh game,
 *  no migration. Missing/unreadable files behave the same via [decodeSave]. */
const val SAVE_SCHEMA = 1

@Serializable
data class PlayerSave(
    /** 0 = written before the current schema (legacy) → wiped on load. */
    val schema: Int = 0,
    val collection: Map<String, Int> = emptyMap(),
    val decks: List<NamedDeck> = emptyList(),
    val activeDeck: String? = null,
    val neverUse: List<String> = emptyList(),
    val neverFace: List<String> = emptyList(),
    val wins: Int = 0, val losses: Int = 0, val draws: Int = 0,
    val tutorialSeen: Boolean = false,
    val campaignCleared: List<Int> = emptyList(),   // global indexes of beaten fights (only ever grows)
    val circuitWins: Int = 0, val circuitLosses: Int = 0,   // W/L across ALL circuit games (run + replays)
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

val saveJson = kotlinx.serialization.json.Json { prettyPrint = true; ignoreUnknownKeys = true }

fun freshSave(): PlayerSave = PlayerSave(schema = SAVE_SCHEMA)

/** The only sanctioned way to turn persisted text into a save: legacy payloads
 *  (schema 0) and foreign-schema payloads load as a brand-new game. */
fun decodeSave(text: String): PlayerSave =
    runCatching { saveJson.decodeFromString<PlayerSave>(text) }.getOrNull()
        ?.takeIf { it.schema == SAVE_SCHEMA }
        ?: freshSave()

fun PlayerSave.addCards(cards: List<CardDef>): PlayerSave {
    val c = collection.toMutableMap()
    cards.forEach { c[it.id] = (c[it.id] ?: 0) + 1 }
    return copy(collection = c)
}

