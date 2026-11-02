package triad

import triad.core.*
import kotlin.random.Random
import kotlin.test.*

class PackTest {
    private val catalog = (1..10).flatMap { lvl ->
        (1..2).map { i ->

            val rank = if (lvl >= 9) 10 else 1 + lvl * 8 / 10
            CardDef("c${lvl}_$i", "C$lvl-$i", lvl, Element.none, rank, rank, rank, rank, no = lvl * 2 + i)
        }
    }

    private val exclusives = setOf("c9_1", "c10_1", "c10_2")

    @Test fun `pack sizes and level ranges per difficulty`() {
        for ((diff, size) in listOf("easy" to 3, "medium" to 4, "hard" to 3, "expert" to 2)) {
            repeat(20) {
                val pack = rollPack(diff, catalog, Random(it), exclusives)
                assertEquals(size, pack.size, "$diff pack size")
            }
        }
        repeat(20) {
            val easy = rollPack("easy", catalog, Random(it), exclusives)
            assertTrue(easy.all { it.level in 1..3 })
            val medium = rollPack("medium", catalog, Random(it), exclusives)
            assertTrue(medium.all { it.level in 2..6 } && medium.any { it.level >= 4 })
            val hard = rollPack("hard", catalog, Random(it), exclusives)
            assertTrue(hard.all { it.level in 3..10 } && hard.any { it.level >= 7 })
            val expert = rollPack("expert", catalog, Random(it), exclusives)
            assertTrue(expert.all { it.level >= 9 }, "expert must only drop level 9-10")
        }
    }

    @Test fun `hidden five - hard never drops them, expert guarantees one`() {
        repeat(20) {
            val hard = rollPack("hard", catalog, Random(it), exclusives)
            assertTrue(hard.none { it.id in exclusives }, "hard pack leaked a hidden card: ${hard.map { it.id }}")
            val expert = rollPack("expert", catalog, Random(it), exclusives)
            assertTrue(expert.any { it.id in exclusives }, "expert pack missing its guaranteed hidden card")
        }
    }

    @Test fun `addCards stacks copies into the collection`() {
        var save = PlayerSave()
        val card = catalog.first()
        save = save.addCards(listOf(card, card, catalog[1]))
        assertEquals(2, save.collection[card.id])
        assertEquals(1, save.collection[catalog[1].id])
    }

    @Test fun `transfers move copies both directions`() {
        val mine = catalog[0]
        val theirs = catalog[5]
        var save = PlayerSave(collection = mapOf(mine.id to 1))
        save = save.applyTransfers(
            listOf(
                CardTransfer(theirs, from = Owner.red, to = Owner.blue),
                CardTransfer(mine, from = Owner.blue, to = Owner.red),
            ),
            myColor = Owner.blue)
        assertEquals(1, save.collection[theirs.id])
        assertEquals(0, save.collection[mine.id])
    }
}
