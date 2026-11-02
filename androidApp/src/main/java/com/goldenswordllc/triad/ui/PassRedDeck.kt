package com.goldenswordllc.triad.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import triad.core.CardDef

private val PrGold = Color(0xFFD4AF37)
private val PrGoldText = Color(255, 240, 200)
private val PrCream = Color(245, 238, 220)
private val PrDim = Color(190, 175, 145)

/** Pass & play: hand the phone to RED, who builds their own 5-card hand from whatever the
 *  collection has left after BLUE's deck. Single-instance by construction (pool is distinct
 *  ids minus blue's picks); a RANDOM fill is offered for quick starts or thin collections. */
@Composable
fun PassRedDeckScreen(
    pool: List<CardDef>,
    onReady: (List<CardDef>) -> Unit,
    onBack: () -> Unit,
) {
    val picked = remember { mutableStateListOf<CardDef>() }
    fun toggle(c: CardDef) {
        if (picked.any { it.id == c.id }) picked.removeAll { it.id == c.id }
        else if (picked.size < 5) picked.add(c)
    }

    Box(Modifier.fillMaxSize()) {
        MatBackground(Modifier.matchParentSize(), dim = 0.3f)
        Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("RED, BUILD YOUR HAND", color = PrGoldText, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                 modifier = Modifier.padding(vertical = 8.dp))
            Text("pass the phone to the red player", color = PrDim, fontSize = 10.sp)

            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(5) { slot ->
                    val def = picked.getOrNull(slot)
                    Box(Modifier.weight(1f).aspectRatio(CardSheets.ASPECT)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(30, 22, 8))
                            .border(1.dp, if (def != null) PrGold else Color(120, 95, 50), RoundedCornerShape(8.dp))
                            .clickable { def?.let { toggle(it) } }) {
                        def?.let {
                            CardThumb(it, conflict = false, owner = triad.core.Owner.red,
                                      modifier = Modifier.fillMaxSize())
                        }
                    }
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp)).background(Color(22, 15, 6).copy(alpha = 0.88f))) {
                if (pool.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Nothing left after BLUE's deck, the match will deal a random red hand.",
                             color = PrDim, fontSize = 12.sp, textAlign = TextAlign.Center,
                             modifier = Modifier.padding(16.dp))
                    }
                } else {
                    LazyVerticalGrid(columns = GridCells.Fixed(4),
                        modifier = Modifier.fillMaxSize().padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        itemsIndexed(pool, key = { _, c -> c.id }) { _, def ->
                            val isPicked = picked.any { it.id == def.id }
                            CardThumb(def, conflict = false, selected = isPicked, owner = triad.core.Owner.red,
                                      modifier = Modifier.aspectRatio(CardSheets.ASPECT)
                                          .clickable { toggle(def) })
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GoldAction("◀  BACK", filled = false, modifier = Modifier.weight(1f)) { onBack() }
                Text("RANDOM", color = PrDim, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                     textAlign = TextAlign.Center,
                     modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                         .background(Color(30, 22, 8).copy(alpha = 0.85f))
                         .border(1.dp, PrDim.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                         .clickable {
                             picked.clear()
                             pool.shuffled().take(5).forEach { picked.add(it) }
                         }
                         .padding(vertical = 12.dp))
                GoldAction(if (picked.size == 5) "START  ▶" else "PICK ${5 - picked.size} MORE",
                    filled = true, enabled = picked.size == 5, modifier = Modifier.weight(2f)) {
                    onReady(picked.toList())
                }
            }
        }
    }
}
