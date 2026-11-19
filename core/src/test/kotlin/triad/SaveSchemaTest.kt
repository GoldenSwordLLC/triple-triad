package triad

import kotlinx.serialization.encodeToString
import triad.core.*
import kotlin.test.*

class SaveSchemaTest {
    /** The 1.0 wipe: a payload written before SAVE_SCHEMA carries no schema field,
     *  decodes as 0, and must load as a brand-new game — no migration. */
    @Test fun `legacy save without schema field is wiped`() {
        val legacy = """
            {"collection": {"ifrit": 2, "squall": 1},
             "decks": [{"id": "d1", "name": "old", "ids": ["ifrit", "squall", "shiva", "quezacotl", "ifrit"]}],
             "activeDeck": "d1", "wins": 41, "losses": 7,
             "campaignCleared": [0,1,2,3,4,5,6,7,8,9,10]}
        """.trimIndent()
        val save = decodeSave(legacy)
        assertEquals(SAVE_SCHEMA, save.schema)
        assertEquals(emptyMap(), save.collection, "legacy collection must not survive the wipe")
        assertEquals(emptyList(), save.decks, "legacy decks must not survive the wipe")
        assertEquals(0, save.wins)
        assertEquals(emptyList(), save.campaignCleared, "legacy campaign progress must not survive the wipe")
    }

    @Test fun `corrupt payload loads as a fresh save`() {
        val save = decodeSave("{ not json at all ]")
        assertEquals(freshSave(), save)
    }

    @Test fun `current-schema save round-trips untouched`() {
        val save = freshSave()
            .addCards(listOf(CardDef("ifrit", "Ifrit", 5, Element.fire, 6, 4, 3, 2)))
            .withRecord(won = true, drawn = false)
        val text = saveJson.encodeToString(save)
        assertEquals(save, decodeSave(text))
    }

    @Test fun `persisted saves always carry the current schema`() {
        assertEquals(SAVE_SCHEMA, freshSave().schema)
    }

    @Test fun `addCards stacks copies into the collection`() {
        val card = CardDef("testcard", "Test", 1, Element.none, 1, 2, 3, 4)
        val save = freshSave().addCards(listOf(card, card, card))
        assertEquals(3, save.collection[card.id])
    }
}
