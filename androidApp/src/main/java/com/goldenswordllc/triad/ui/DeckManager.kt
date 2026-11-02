package com.goldenswordllc.triad.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed as rowItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
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
import triad.core.NamedDeck
import triad.core.PlayerSave
import triad.core.Owner

private val PanelBg = Color(22, 15, 6).copy(alpha = 0.85f)
private val Gold = Color(0xFFD4AF37)
private val GoldText = Color(255, 240, 200)
private val Cream = Color(245, 238, 220)
private val DimText = Color(200, 178, 130)

@Composable
fun DeckManagerScreen(
    catalog: Map<String, CardDef>,
    save: PlayerSave,
    onUpdate: (PlayerSave) -> Unit,
    onDone: () -> Unit,
    onViewCollection: () -> Unit = {},
) {
    var editing by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<NamedDeck?>(null) }
    deleting?.let { deck ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete \"${deck.name}\"?", color = Color(40, 28, 12), fontWeight = FontWeight.Bold) },
            text = { Text("The deck is removed, your cards stay in the collection.",
                          color = Color(60, 50, 35)) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    onUpdate(save.copy(
                        decks = save.decks.filterNot { it.id == deck.id },
                        activeDeck = if (save.activeDeck == deck.id) null else save.activeDeck))
                    deleting = null
                }) { Text("DELETE", color = Color(183, 28, 28), fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { deleting = null }) { Text("KEEP") }
            },
        )
    }

    if (editing != null) {
        DeckEditorScreen(catalog, save, deckId = editing?.takeIf { it.isNotEmpty() },
            onUpdate = onUpdate, onBack = { editing = null })
        return
    }

    val distinct = save.collection.count { it.value > 0 }
    val total = save.collection.values.sum()
    Box(Modifier.fillMaxSize()) {
        MatBackground(Modifier.matchParentSize(), dim = 0.28f)
        Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("DECKS", color = GoldText, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                 modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
            Text("COLLECTION: $distinct cards ($total total)", color = DimText, fontSize = 12.sp)

            Box(Modifier.weight(1f).fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp)).background(PanelBg)) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
                       verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (save.decks.isEmpty()) {
                        Text("No decks yet, build one from your collection.",
                             color = DimText, fontSize = 13.sp)
                    }
                    save.decks.forEach { deck ->
                        val active = deck.id == save.activeDeck
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .background(if (active) Color(120, 85, 20) else Color(30, 22, 8))
                                .border(2.dp, if (active) Gold else Color(120, 95, 50), RoundedCornerShape(12.dp))
                                .clickable { editing = deck.id }
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(deck.name, color = GoldText, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                Text(if (active) "ACTIVE DECK" else "tap to edit",
                                     color = if (active) Gold else DimText, fontSize = 10.sp)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                deck.ids.forEach { id ->
                                    catalog[id]?.let {
                                        CardThumb(it, conflict = false, owner = Owner.blue,
                                                  modifier = Modifier.width(34.dp).aspectRatio(CardSheets.ASPECT).padding(1.dp))
                                    }
                                }
                                Spacer(Modifier.width(6.dp))
                                Text("✕", color = Color(255, 120, 100), fontWeight = FontWeight.Bold,
                                     modifier = Modifier
                                         .clip(RoundedCornerShape(8.dp))
                                         .background(Color(0, 0, 0, 120))
                                         .clickable { deleting = deck }
                                         .padding(horizontal = 8.dp, vertical = 4.dp))
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            GoldButton("VIEW COLLECTION", sub = "see which cards you're missing") { onViewCollection() }
            GoldButton("NEW DECK") { editing = "" }
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(Color(120, 85, 20)).border(2.dp, Gold, RoundedCornerShape(12.dp))
                    .clickable { onDone() }.padding(vertical = 12.dp),
                contentAlignment = Alignment.Center) {
                Text("DONE  ▶", color = GoldText, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun DeckEditorScreen(
    catalog: Map<String, CardDef>,
    save: PlayerSave,
    deckId: String? = null,
    onUpdate: (PlayerSave) -> Unit,
    onBack: () -> Unit,
) {
    val existing = deckId?.let { id -> save.decks.firstOrNull { it.id == id } }
    var name by remember { mutableStateOf(existing?.name ?: "Deck ${save.decks.size + 1}") }

    val picked = remember(existing) {
        mutableStateListOf<String>().apply { existing?.ids?.let { addAll(it) } }
    }

    fun used(id: String) = if (picked.any { it == id }) 1 else 0
    val collection = remember(save) { save.collection.filterValues { it > 0 } }

    Box(Modifier.fillMaxSize()) {
        MatBackground(Modifier.matchParentSize(), dim = 0.28f)
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("BUILD A DECK", color = GoldText, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text("${picked.size}/5", color = if (picked.size == 5) Gold else DimText,
                     fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            OutlinedTextField(name, { name = it }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                              singleLine = true,
                              colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                                  focusedBorderColor = Color(0xFFD4AF37),
                                  unfocusedBorderColor = Color(150, 130, 90),
                                  focusedTextColor = Color(245, 238, 220),
                                  unfocusedTextColor = Color(245, 238, 220),
                                  cursorColor = Color(0xFFD4AF37),
                                  focusedLabelColor = Color(230, 205, 160),
                                  unfocusedLabelColor = Color(190, 175, 145),
                              ))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(5) { slot ->
                    val id = picked.getOrNull(slot)
                    val def = id?.let { catalog[it] }
                    Box(Modifier.weight(1f).aspectRatio(CardSheets.ASPECT)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(30, 22, 8))
                            .border(1.dp, if (def != null) Gold else Color(120, 95, 50), RoundedCornerShape(8.dp))
                            .clickable { if (slot < picked.size) { picked.removeAt(slot) } }) {
                        def?.let {
                            CardThumb(it, conflict = false, owner = Owner.blue, modifier = Modifier.fillMaxSize())
                        }
                    }
                }
            }

            Text("COLLECTION, tap a card to add it", color = DimText, fontSize = 11.sp,
                 modifier = Modifier.padding(vertical = 6.dp))

            Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(PanelBg)) {
                val sorted = remember(save) {
                    collection.keys.mapNotNull { catalog[it] }.sortedWith(
                        compareBy({ it.level }, { it.no }))
                }

                val available = sorted.filter { used(it.id) == 0 }
                if (available.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Every card you own is already in the deck.",
                             color = DimText, fontSize = 13.sp)
                    }
                }
                LazyVerticalGrid(columns = GridCells.Fixed(4),
                                 modifier = Modifier.fillMaxSize().padding(8.dp),
                                 horizontalArrangement = Arrangement.spacedBy(6.dp),
                                 verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    itemsIndexed(available, key = { _, c -> c.id }) { _, def ->
                        val remaining = 1 - used(def.id)
                        Box(Modifier.aspectRatio(CardSheets.ASPECT).clickable(enabled = remaining > 0 && picked.size < 5) {
                            if (picked.size < 5) picked.add(def.id)
                        }) {
                            CardThumb(def, conflict = false, owner = Owner.blue,
                                      modifier = Modifier.fillMaxSize())
                            Text("×$remaining", color = GoldText, fontSize = 10.sp,
                                 fontWeight = FontWeight.Bold,
                                 modifier = Modifier.align(Alignment.BottomEnd)
                                     .background(Color(0, 0, 0, 160)).padding(horizontal = 3.dp))
                        }
                    }
                }
            }

            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val canSave = picked.size == 5 && name.isNotBlank()
                Box(Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                        .background(if (canSave) Color(120, 85, 20) else Color(40, 30, 14))
                        .border(2.dp, Gold, RoundedCornerShape(12.dp))
                        .clickable(enabled = canSave) {
                            val id = existing?.id ?: java.util.UUID.randomUUID().toString()
                            val deck = NamedDeck(id, name.trim(), picked.toList())
                            val decks = save.decks.filterNot { it.id == id } + deck
                            onUpdate(save.copy(decks = decks, activeDeck = save.activeDeck ?: id))
                            onBack()
                        }.padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center) {
                    Text("SAVE", color = GoldText, fontWeight = FontWeight.Bold)
                }
                if (existing != null) {
                    Box(Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                            .background(Color(120, 85, 20)).border(2.dp, Gold, RoundedCornerShape(12.dp))
                            .clickable { onUpdate(save.copy(activeDeck = existing.id)); onBack() }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center) {
                        Text("SET ACTIVE", color = GoldText, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
                Box(Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                        .background(Color(30, 22, 8)).border(2.dp, Gold, RoundedCornerShape(12.dp))
                        .clickable { onBack() }.padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center) {
                    Text("CANCEL", color = GoldText, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
fun NoDeckDialog(onBuild: () -> Unit, onPlayAnyway: () -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("No active deck", color = Color(40, 28, 12), fontWeight = FontWeight.Bold) },
        text = { Text("Your deck is the hand you play with, in single player and against friends. " +
                      "Build one from your collection first?", color = Color(60, 50, 35)) },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onBuild) {
                Text("BUILD A DECK", color = Color(120, 85, 20), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onPlayAnyway) {
                Text("USE RANDOM CARDS", fontWeight = FontWeight.Bold)
            }
        },
    )
}

@Composable
fun CardBack(revealed: Boolean, content: @Composable () -> Unit) {
    if (revealed) { content(); return }
    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp))
            .background(Color(25, 32, 62))
            .border(2.dp, Gold, RoundedCornerShape(8.dp))) {
        Text("◆", color = Gold, fontSize = 22.sp,
             modifier = Modifier.align(Alignment.Center))
    }
}

@Composable
fun PackScreen(cards: List<CardDef>, onClaim: () -> Unit) {

    var revealed by remember(cards) { mutableStateOf(cards.map { false }) }
    Box(Modifier.fillMaxSize()
            .background(Color(0, 0, 0, 170))

            .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                       indication = null) {},
        contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxWidth().padding(24.dp)
                .clip(RoundedCornerShape(16.dp)).background(Color(30, 22, 8))
                .border(2.dp, Gold, RoundedCornerShape(16.dp)).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text("VICTORY PACK", color = GoldText, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(if (revealed.all { it }) "Added to your collection!" else "Tap each card to reveal it",
                 color = DimText, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                cards.forEachIndexed { i, def ->
                    Box(Modifier.weight(1f).aspectRatio(CardSheets.ASPECT)
                            .clickable { if (!revealed[i]) revealed = revealed.toMutableList().also { it[i] = true } }) {
                        CardBack(revealed[i]) {
                            CardThumb(def, conflict = false, owner = Owner.blue, modifier = Modifier.fillMaxSize())
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            val all = revealed.all { it }
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(if (all) Color(46, 125, 50) else Color(40, 30, 14))
                    .border(2.dp, Gold, RoundedCornerShape(12.dp))
                    .clickable(enabled = all) { onClaim() }.padding(vertical = 12.dp),
                contentAlignment = Alignment.Center) {
                Text(if (all) "ADD TO COLLECTION  ▶" else "REVEAL ALL FIRST",
                     color = GoldText, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }
    }
}
