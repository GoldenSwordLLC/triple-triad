package triad

import kotlinx.serialization.Serializable
import triad.core.*
import triad.net.resolveColors
import triad.net.ColorPref
import java.io.File
import kotlin.test.*

@Serializable data class ColorMatrix(val cases: List<ColorCase>)
@Serializable data class ColorCase(val host: String, val joiner: String, val coin: Int,
                                   val hostColor: String, val joinerColor: String)

@Serializable data class RngFixture(val seedHex: String, val elemental: Boolean, val randomHands: Boolean,
                                    val pool: List<String>, val expect: RngExpect)
@Serializable data class RngExpect(val coin: Int, val cells: List<Int>,
                                   val blueSample: List<String>, val redSample: List<String>, val first: Int)

class SequenceTests {
    @Test fun `color resolution matrix`() {
        val fx = json.decodeFromString<ColorMatrix>(File("shared-data/fixtures/color_resolution_matrix.json").readText())
        fx.cases.forEachIndexed { i, c ->
            val (h, j) = resolveColors(ColorPref.valueOf(c.host), ColorPref.valueOf(c.joiner), c.coin)
            assertEquals(c.hostColor, h.name, "case $i: host=${c.host} joiner=${c.joiner} coin=${c.coin}")
            assertEquals(c.joinerColor, j.name, "case $i: joiner color")
        }
    }

    @Test fun `rng round1 sequence matches fixture`() {
        val fx = json.decodeFromString<RngFixture>(File("shared-data/fixtures/rng_round1_sequence.json").readText())
        val notGenerated = "GENERATE" in fx.expect.blueSample || fx.expect.coin == -1
        val r = SplitMix64(seedFromHex(fx.seedHex))
        val coin = r.nextInt(2)
        val cells = List(9) { if (fx.elemental && r.nextInt(5) == 0) r.nextInt(9) + 1 else 0 }
        val blueSample = if (fx.randomHands) r.sample5(fx.pool) else fx.pool.take(5)
        val redSample  = if (fx.randomHands) r.sample5(fx.pool) else fx.pool.take(5)
        val first = r.nextInt(2)
        if (notGenerated) {
            println("PASTE INTO rng_round1_sequence.json → coin=$coin cells=$cells blueSample=$blueSample redSample=$redSample first=$first")
            fail("fixture not generated yet — paste the printed values into shared-data/fixtures/rng_round1_sequence.json, commit, rerun")
        }
        assertEquals(fx.expect.coin, coin)
        assertEquals(fx.expect.cells, cells)
        assertEquals(fx.expect.blueSample, blueSample)
        assertEquals(fx.expect.redSample, redSample)
        assertEquals(fx.expect.first, first)
    }
}
