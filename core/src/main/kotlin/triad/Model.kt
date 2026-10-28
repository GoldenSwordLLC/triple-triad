package triad.core

import kotlinx.serialization.Serializable

@Serializable enum class Owner { blue, red; val other: Owner get() = if (this == blue) red else blue }
@Serializable enum class Side { top, right, bottom, left;
    val opposite: Side get() = when (this) { top -> bottom; bottom -> top; left -> right; right -> left } }

@Serializable enum class Element { none, fire, ice, thunder, poison, holy, wind, earth, water, dark }
@Serializable enum class CardType { monster, boss, gf, character }
@Serializable enum class TradeRule { none, one, direct, diff, all }

@Serializable
data class CardDef(
    val id: String = "",
    val name: String,
    val level: Int,
    val element: Element,
    val top: Int, val right: Int, val bottom: Int, val left: Int,
    val no: Int = 0,
    val type: CardType = CardType.monster
) {
    fun rank(side: Side): Int = when (side) { Side.top -> top; Side.right -> right; Side.bottom -> bottom; Side.left -> left }
}

@Serializable
data class PlacedCard(val def: CardDef, val owner: Owner, val originalOwner: Owner = owner)

@Serializable
data class Cell(val card: PlacedCard? = null, val element: Element = Element.none)

@Serializable
data class Board(val cells: List<Cell> = List(9) { Cell() }) {
    operator fun get(i: Int) = cells[i]
    val isFull get() = cells.all { it.card != null }
    fun withCard(i: Int, card: PlacedCard) = Board(cells.mapIndexed { idx, c -> if (idx == i) c.copy(card = card) else c })
    fun withOwner(i: Int, owner: Owner) = Board(cells.mapIndexed { idx, c ->
        if (idx == i) c.copy(card = c.card?.copy(owner = owner)) else c })
}

@Serializable
data class Rules(val same: Boolean = false, val plus: Boolean = false,
                 val sameWall: Boolean = false, val elemental: Boolean = false)

@Serializable
data class MatchSettings(
    val rules: Rules = Rules(),
    val open: Boolean = true,
    val randomHands: Boolean = false,
    val suddenDeath: Boolean = false,
    val trade: TradeRule = TradeRule.none
)

@Serializable
data class GameState(
    val board: Board = Board(),
    val blueHand: List<CardDef> = emptyList(),
    val redHand: List<CardDef> = emptyList(),
    val turn: Owner = Owner.blue,
    val rules: Rules = Rules()
) {
    fun hand(owner: Owner) = if (owner == Owner.blue) blueHand else redHand
    val isOver get() = board.isFull

    fun score(owner: Owner) = board.cells.count { it.card?.owner == owner }
}

@Serializable enum class FlipCause { basic, same, plus, combo }
@Serializable data class Flip(val cell: Int, val cause: FlipCause, val wave: Int)
data class PlacementResult(val state: GameState, val flips: List<Flip>)

data class CardTransfer(val card: CardDef, val from: Owner, val to: Owner)
data class PendingPick(val count: Int, val candidates: List<CardDef>)

fun TradeRule.maxLoss(brought: Int = 5): Int = when (this) {
    TradeRule.none -> 0
    TradeRule.one -> 1
    TradeRule.direct -> brought
    TradeRule.diff -> brought
    TradeRule.all -> brought
}
