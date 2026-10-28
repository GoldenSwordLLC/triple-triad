package com.goldenswordllc.triad.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.delay
import triad.core.*

class GameViewModel(initial: GameState) : ViewModel() {
    var state by mutableStateOf(initial); private set
    var lastFlips by mutableStateOf<List<Flip>>(emptyList()); private set

    fun place(cardIndex: Int, cellIndex: Int) {
        if (state.isOver || state.board[cellIndex].card != null) return
        val r = Engine.place(state, cardIndex, cellIndex)
        state = r.state; lastFlips = r.flips
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun BoardCell(cellIndex: Int, cell: Cell, flips: List<Flip>,
              onInfo: ((CardDef, Owner) -> Unit)? = null, onCardTap: (() -> Unit)? = null) {
    val card = cell.card ?: return
    val flip = flips.firstOrNull { it.cell == cellIndex }
    val displayed = remember { mutableStateOf(card.owner) }
    val rotation = remember { Animatable(0f) }

    LaunchedEffect(card.owner) {
        if (card.owner != displayed.value) {
            if (flip != null) {
                delay(flip.wave * 350L)
                rotation.animateTo(90f, tween(140))
                displayed.value = card.owner
                rotation.animateTo(0f, tween(140))
            } else {
                displayed.value = card.owner
            }
        }
    }
    CardThumb(card.def, conflict = false, owner = displayed.value,
              modifier = Modifier
                  .fillMaxHeight()
                  .aspectRatio(CardSheets.ASPECT)
                  .combinedClickable(
                      onClick = { onCardTap?.invoke() },
                      onLongClick = { onInfo?.invoke(card.def, card.owner) })
                  .graphicsLayer {
                      rotationY = rotation.value
                  })
}
