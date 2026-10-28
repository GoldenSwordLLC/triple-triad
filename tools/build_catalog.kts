// Usage: kotlin tools/build_catalog.kts shared-data/card_table.txt shared-data/catalog/cards.json shared-data/react_cards.json
import java.io.File

val ELEMENTS = mapOf("Thunder" to "thunder", "Ice" to "ice", "Wind" to "wind", "Poison" to "poison",
    "Earth" to "earth", "Fire" to "fire", "Water" to "water", "Holy" to "holy")
val TYPES = mapOf("Monster" to "monster", "Boss" to "boss", "G.F." to "gf", "Character" to "character")
val RENAMES = mapOf(
    "Pandemonium" to "Pandemona", "Shumi Village" to "Shumi Tribe", "T-Rexsaur" to "T-Rexaur",
    "Ward Zabac" to "Ward", "Kiros Seagill" to "Kiros", "Laguna Loire" to "Laguna",
    "Selphie Tilmitt" to "Selphie", "Quistis Trepe" to "Quistis", "Irvine Kinneas" to "Irvine",
    "Zell Dincht" to "Zell", "Rinoa Heartilly" to "Rinoa", "Seifer Almasy" to "Seifer",
    "Squall Leonhart" to "Squall")

// Table rank column order is N W E S, but a card's physical edges are top/right/bottom/left
// = N/E/S/W — west is the card's LEFT edge. The adjudicated-card pins below fail if this regresses.
val ROW = Regex("""^G-\s*(\d+)\s+(.+?)\s+(Monster|Boss|G\.F\.|Character)\s+(\d+)\s*(?:(Thunder|Ice|Wind|Poison|Earth|Fire|Water|Holy)\s+)?(\d{1,2}|A)\s+(\d{1,2}|A)\s+(\d{1,2}|A)\s+(\d{1,2}|A)(?:\s|$)""")

fun rank(s: String) = if (s == "A") 10 else s.toInt()
fun slug(name: String) = name.lowercase().filter { it.isLetterOrDigit() }
fun cleanTitle(t: String) = t.replace(Regex("""-\s+"""), "-").trim()

data class Card(val no: Int, val name: String, val type: String, val level: Int, val element: String,
                val top: Int, val right: Int, val bottom: Int, val left: Int)

val cards = File(args[0]).readLines().mapNotNull { line ->
    ROW.find(line)?.let { m ->
        val g = m.groupValues
        val title = RENAMES[cleanTitle(g[2])] ?: cleanTitle(g[2])
        Card(g[1].toInt(), title, TYPES.getValue(g[3]), g[4].toInt(),
             if (g[5].isEmpty()) "none" else ELEMENTS.getValue(g[5]),
             rank(g[6]), rank(g[8]), rank(g[9]), rank(g[7]))
    }
}

// --- refuse to write a bad catalog ---
require(cards.size == 110) { "parsed ${cards.size} rows — expected 110" }
(1..10).forEach { lvl -> require(cards.count { it.level == lvl } == 11) { "level $lvl != 11 cards" } }
require(cards.map { it.no }.distinct().size == 110) { "duplicate card numbers" }
require(cards.map { slug(it.name) }.distinct().size == 110) { "slug collision after normalization" }
cards.forEach { c -> listOf(c.top, c.right, c.bottom, c.left).forEach {
    require(it in 1..10) { "${c.name}: rank $it out of range" } } }

// Orientation pins — cross-adjudicated against the React file; if these fail, the
// N/W/E/S → top/right/bottom/left mapping above has regressed.
fun pin(name: String, t: Int, r: Int, b: Int, l: Int) {
    val c = cards.first { it.name == name }
    require(listOf(c.top, c.right, c.bottom, c.left) == listOf(t, r, b, l)) {
        "orientation pin failed for $name: got ${c.top}/${c.right}/${c.bottom}/${c.left}, want $t/$r/$b/$l"
    }
}
pin("Ruby Dragon", 7, 2, 7, 4)
pin("Pandemona", 10, 1, 7, 7)
pin("Shumi Tribe", 6, 5, 8, 4)
pin("Tonberry King", 7, 6, 7, 4)

val sorted = cards.sortedBy { it.no }
File(args[1]).parentFile.mkdirs()
File(args[1]).writeText(buildString {
    append("{\n  \"cards\": [\n")
    sorted.forEachIndexed { i, c ->
        append("    { \"no\": ${c.no}, \"id\": \"${slug(c.name)}\", \"name\": \"${c.name}\", ")
        append("\"type\": \"${c.type}\", \"level\": ${c.level}, \"element\": \"${c.element}\", ")
        append("\"top\": ${c.top}, \"right\": ${c.right}, \"bottom\": ${c.bottom}, \"left\": ${c.left} }")
        append(if (i == sorted.lastIndex) "\n" else ",\n")
    }
    append("  ]\n}\n")
})
println("Wrote 110 cards → ${args[1]}  |  " + (1..10).joinToString(" ") { "L$it:${cards.count { c -> c.level == it }}" })

// --- audit against the React file ---
if (args.size > 2) {
    val react = Regex("""\{[^{}]*}""").findAll(File(args[2]).readText())
        .map { b -> Regex(""""(name|level|element|top|right|bottom|left)":\s*("[^"]*"|[\w.\-]+)""")
            .findAll(b.value).associate { it.groupValues[1] to it.groupValues[2].trim('"') } }
        .filter { it.containsKey("name") }
        .associateBy { slug(it["name"]!!) }
    val bySlug = sorted.associateBy { slug(it.name) }
    var diffs = 0
    for ((s, r) in react) {
        val c = bySlug[s] ?: run { println("NAME  react '${r["name"]}' — no match in table"); diffs++; null } ?: continue
        fun chk(f: String, got: String, want: String) { if (got.trim() != want) { println("STATS ${c.name}: $f $got → $want"); diffs++ } }
        chk("level", r["level"] ?: "?", c.level.toString())
        chk("element", (r["element"] ?: "").let { when (it.trim()) { "" -> "none"; "lightning" -> "thunder"; else -> it } }, c.element)
        chk("top",    r["top"]    ?: "?", c.top.toString())
        chk("right",  r["right"]  ?: "?", c.right.toString())
        chk("bottom", r["bottom"] ?: "?", c.bottom.toString())
        chk("left",   r["left"]   ?: "?", c.left.toString())
    }
    println("audit diffs: $diffs")
}
