package triad

import triad.core.*
import kotlin.test.*

class PackSourcesTest {
    private val hiddenFiveNames = listOf("doomtrain", "edea", "rinoa", "squall", "seifer")
    private val catalog: Map<String, CardDef> = buildMap {
        for (lvl in 1..10) for (i in 1..11) {
            val id = when {
                lvl == 9 && i == 11 -> "doomtrain"
                lvl == 10 && i >= 8 -> listOf("edea", "rinoa", "squall", "seifer")[i - 8]
                else -> "c${(lvl - 1) * 11 + i}"
            }
            put(id, CardDef(id, id, lvl, Element.none, 5, 5, 5, 5))
        }
    }
    private val hiddenFive = hiddenFiveNames.map { catalog.getValue(it) }

    @Test fun `sources match the written pack rules`() {
        assertEquals(listOf("easy"), packSources(card(1, 1)))
        assertEquals(listOf("easy", "medium"), packSources(card(2, 1)))
        assertEquals(listOf("easy", "medium", "hard"), packSources(card(3, 2)))
        assertEquals(listOf("medium", "hard"), packSources(card(4, 1)))
        assertEquals(listOf("medium", "hard"), packSources(card(6, 3)))
        assertEquals(listOf("hard"), packSources(card(7, 1)))
        assertEquals(listOf("hard"), packSources(card(8, 2)))
        assertEquals(listOf("hard", "expert"), packSources(card(9, 4)))
        assertEquals(listOf("hard", "expert"), packSources(card(10, 5)))
        for (c in hiddenFive) assertEquals(listOf("expert"), packSources(c), "${c.id} is Expert-only")
    }

    @Test fun `rollPack agrees with packSources on a large sample`() {
        val rng = kotlin.random.Random(1)
        val tiers = listOf("easy", "medium", "hard", "expert")
        for (c in catalog.values) {
            val src = packSources(c)
            for (tier in tiers) {

                val packs = if (tier in src) 600 else 120
                val hits = (1..packs).count { rollPack(tier, catalog.values, rng).any { it.id == c.id } }
                if (tier in src) assertTrue(hits > 0, "${c.id} should drop in $tier (got $hits in $packs)")
                else assertEquals(0, hits, "${c.id} dropped in $tier but sources say $src")
            }
        }
    }

    @Test fun `drop rates match sampled reality`() {
        val rng = kotlin.random.Random(3)
        val cases = listOf(
            card(1, 1) to "easy",
            card(3, 2) to "easy",
            card(3, 2) to "medium",
            card(5, 7) to "medium",
            card(5, 7) to "hard",
            card(8, 2) to "hard",
            card(9, 4) to "hard",
            card(9, 4) to "expert",
            hiddenFive[0] to "expert",
            card(2, 3) to "hard",
            card(1, 1) to "hard",
        )
        val n = 20_000
        for ((c, tier) in cases) {
            val expected = packDropRate(c, tier, catalog.values)
            val sampled = (1..n).count { rollPack(tier, catalog.values, rng).any { it.id == c.id } } / n.toDouble()
            assertTrue(abs(sampled - expected) < 0.015,
                "${c.id} @$tier: formula=$expected sampled=$sampled (n=$n)")
        }
        assertEquals(0.0, packDropRate(card(1, 1), "hard", catalog.values))
        assertEquals(0.0, packDropRate(hiddenFive[0], "medium", catalog.values))
    }

    private fun card(lvl: Int, i: Int) = catalog["c${(lvl - 1) * 11 + i}"]!!
    private fun abs(v: Double) = kotlin.math.abs(v)
}
