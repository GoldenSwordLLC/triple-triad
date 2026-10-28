package triad

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import triad.core.*
import triad.net.resolveColors
import triad.net.ColorPref
import java.io.File
import kotlin.test.*

val json = Json { ignoreUnknownKeys = true }

class ParityTests {
    @Test fun `all engine fixtures pass`() {
        File("shared-data/fixtures").listFiles { f: File -> f.extension == "json" }!!
            .sortedBy { it.name }.forEach { file ->
                val name = file.nameWithoutExtension
                if (name.startsWith("color_") || name.startsWith("rng_")) return@forEach
                val fx = json.decodeFromString<Fixture>(file.readText())
                val state = fx.toState()
                val cardIndex = state.hand(state.turn).indexOfFirst { it.id == fx.place.card }
                val result = Engine.place(state, cardIndex, fx.place.cell)
                assertEquals(fx.expectFlips, result.flips, "fixture ${file.name} (${fx.name})")
            }
    }
}

@Serializable data class Fixture(
    val name: String, val rules: Rules, val cards: Map<String, CardDef>,
    val board: List<FixtureCell?>, val hands: FixtureHands,
    val turn: Owner, val place: FixturePlace, val expectFlips: List<Flip>)
@Serializable data class FixtureCell(val card: String? = null, val owner: Owner? = null, val element: Element? = null)
@Serializable data class FixtureHands(val blue: List<String> = emptyList(), val red: List<String> = emptyList())
@Serializable data class FixturePlace(val card: String, val cell: Int)

private fun Fixture.toState(): GameState {
    val cards = this.cards.mapValues { (k, v) -> v.copy(id = k) }
    return GameState(
        board = Board(board.map { e -> Cell(element = e?.element ?: Element.none,
            card = e?.card?.let { PlacedCard(cards.getValue(it), e.owner ?: Owner.blue) }) }),
        blueHand = hands.blue.map { cards.getValue(it) },
        redHand = hands.red.map { cards.getValue(it) },
        turn = turn, rules = rules)
}
