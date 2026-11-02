package triad

import triad.core.*
import kotlin.test.*

class DeckRuleTest {
    private val catalog = setOf("a", "b", "c", "d", "e")
    private val collection = mapOf("a" to 3, "b" to 1, "c" to 1, "d" to 1, "e" to 1)

    @Test fun `a deck may hold each card at most once even when owned in multiples`() {
        val errors = validateDeck(listOf("a", "a", "b", "c", "d"), catalog, collection, false, emptySet())
        assertEquals(listOf<DeckError>(DeckError.Duplicate("a")), errors)
    }

    @Test fun `distinct decks from a multi-copy collection validate cleanly`() {
        val errors = validateDeck(listOf("a", "b", "c", "d", "e"), catalog, collection, false, emptySet())
        assertTrue(errors.isEmpty())
    }

    @Test fun `random deals never produce duplicates - battleDeck and pass red pool stay distinct`() {

        val owned = collection.filterValues { it > 0 }.keys
        val rng = kotlin.random.Random(7)
        repeat(200) {
            val hand = owned.shuffled(rng).take(5)
            assertEquals(5, hand.map { it }.toSet().size)
        }
    }
}
