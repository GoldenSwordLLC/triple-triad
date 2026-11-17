package com.goldenswordllc.triad.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import triad.core.Campaign
import triad.core.CardDef
import triad.core.PlayerSave
import triad.core.Owner

private val CollectionGold = Color(255, 240, 200)
private val CollectionLabel = Color(230, 205, 160)
private val CollectionDim = Color(190, 175, 145)

@Composable
fun CollectionScreen(catalog: Map<String, CardDef>, save: PlayerSave, onBack: () -> Unit) {
    val owned = save.collection.filterValues { it > 0 }.keys
    val levels = (1..10).map { lvl -> lvl to catalog.values.filter { it.level == lvl }.sortedBy { it.no } }
    val total = levels.sumOf { it.second.size }
    val have = levels.sumOf { (lvl, cards) -> cards.count { it.id in owned } }
    var detail by remember { mutableStateOf<CardDef?>(null) }
    // where each card comes from: the campaign fight that pays it, or the starter kit
    val rewardSource = remember(catalog) {
        val m = HashMap<String, Campaign.Fight>()
        Campaign.FIGHTS.forEach { f -> f.rewardIds.forEach { id -> m[id] = f } }
        m
    }

    Box(Modifier.fillMaxSize()) {
        MatBackground(Modifier.matchParentSize(), dim = 0.3f)
        Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("COLLECTION", color = CollectionGold, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                 modifier = Modifier.padding(vertical = 10.dp))
            Box(Modifier.weight(1f).fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp)).background(Color(22, 15, 6).copy(alpha = 0.88f))) {
                val vScroll = rememberScrollState()
                val canUp by remember { derivedStateOf { vScroll.value > 0 } }
                val canDown by remember { derivedStateOf { vScroll.value < vScroll.maxValue } }
                Column(Modifier.fillMaxSize().verticalScroll(vScroll).padding(horizontal = 14.dp, vertical = 10.dp),
                       horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("$have / $total cards", color = CollectionGold, fontSize = 15.sp,
                         fontWeight = FontWeight.Bold)
                    Text("dimmed cards are missing · ×N = copies you own", color = CollectionDim, fontSize = 10.sp,
                         modifier = Modifier.padding(top = 2.dp))
                    Text("source = the fight that pays the card", color = CollectionDim,
                         fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
                    levels.forEach { (lvl, cards) ->
                        val haveInTier = cards.count { it.id in owned }
                        val missing = cards.size - haveInTier
                        Row(verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp)) {
                            Text("LEVEL $lvl", color = CollectionLabel, fontSize = 12.sp,
                                 fontWeight = FontWeight.Bold)
                            Spacer(Modifier.weight(1f))
                            Text(if (missing == 0) "$haveInTier / ${cards.size}" else "$haveInTier / ${cards.size}  ·  missing $missing",
                                 color = if (missing == 0) Color(140, 200, 140) else Color(255, 150, 130),
                                 fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Box(Modifier.fillMaxWidth()) {
                            val hScroll = rememberLazyListState()
                            val canLeft by remember { derivedStateOf {
                                hScroll.firstVisibleItemIndex > 0 || hScroll.firstVisibleItemScrollOffset > 0
                            } }
                            val canRight by remember { derivedStateOf {
                                val last = hScroll.layoutInfo.visibleItemsInfo.lastOrNull()
                                last != null && (last.index < hScroll.layoutInfo.totalItemsCount - 1 ||
                                    last.offset + last.size > hScroll.layoutInfo.viewportEndOffset)
                            } }
                            LazyRow(state = hScroll, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                itemsIndexed(cards, key = { _, c -> c.id }) { _, def ->
                                    val haveIt = def.id in owned
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Box {
                                            CardThumb(def, conflict = false, owner = Owner.blue,
                                                      modifier = Modifier
                                                          .width(72.dp)
                                                          .aspectRatio(CardSheets.ASPECT)
                                                          .alpha(if (haveIt) 1f else 0.28f)
                                                          .clickable { detail = def })
                                            val copies = save.collection[def.id] ?: 0
                                            if (copies > 0) {
                                                Text("×$copies", color = CollectionGold,
                                                     fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                                     modifier = Modifier.align(Alignment.BottomEnd)
                                                         .background(Color(0, 0, 0, 160))
                                                         .padding(horizontal = 3.dp))
                                            }
                                        }

                                        Text(rewardSource[def.id]?.let { "C${it.chapter}" } ?: "STARTER",
                                             color = if (haveIt) CollectionDim else CollectionGold.copy(alpha = 0.75f),
                                             fontSize = 8.sp, fontWeight = FontWeight.Bold,
                                             modifier = Modifier.padding(top = 2.dp))
                                    }
                                }
                            }
                            ScrollChevron("‹", Alignment.CenterStart, canLeft)
                            ScrollChevron("›", Alignment.CenterEnd, canRight)
                        }
                    }
                }
                ScrollChevron("▲", Alignment.TopCenter, canUp)
                ScrollChevron("▼", Alignment.BottomCenter, canDown)
            }
            Spacer(Modifier.height(12.dp))
            GoldAction("◀  BACK TO DECKS", filled = true, modifier = Modifier.fillMaxWidth()) { onBack() }
        }
    }

    detail?.let { card ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { detail = null },
            containerColor = Color(30, 22, 8),
            title = { Text(card.name, color = CollectionGold, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("Level ${card.level}", color = CollectionLabel, fontSize = 12.sp,
                         fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(2.dp))
                    val ownedCount = save.collection[card.id] ?: 0
                    Text(if (ownedCount > 0) "In your collection  ·  you own ×$ownedCount"
                         else "MISSING from your collection",
                         color = if (ownedCount > 0) Color(140, 200, 140) else Color(255, 150, 130),
                         fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    val src = rewardSource[card.id]
                    Text(if (src != null) "Rewarded by ${src.name}, ${src.epithet} — chapter ${src.chapter}"
                         else "Part of your starter kit",
                         color = CollectionGold, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { detail = null }) {
                    Text("CLOSE", color = CollectionGold, fontWeight = FontWeight.Bold)
                }
            },
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.ScrollChevron(
    arrow: String, align: Alignment, visible: Boolean,
) {
    if (!visible) return
    Text(arrow, color = CollectionGold, fontSize = 13.sp, fontWeight = FontWeight.Bold,
         modifier = Modifier
             .align(align)
             .padding(6.dp)
             .clip(RoundedCornerShape(50))
             .background(Color(22, 15, 6).copy(alpha = 0.92f))
             .padding(horizontal = 9.dp, vertical = 1.dp))
}
