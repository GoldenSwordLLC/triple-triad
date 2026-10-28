package com.goldenswordllc.triad.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val PanelBg = Color(22, 15, 6).copy(alpha = 0.82f)
private val TutorialGold = Color(0xFFD4AF37)
private val TutorialTitle = Color(255, 240, 200)
private val TutorialBody = Color(245, 238, 220)

@Composable
fun TutorialScreen(onDone: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        MatBackground(Modifier.matchParentSize(), dim = 0.3f)
        Column(
            Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("HOW TO PLAY", color = TutorialTitle, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                 modifier = Modifier.padding(top = 8.dp, bottom = 12.dp))

            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(PanelBg)
            ) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)
                ) {
                    section("THE GOAL",
                        "Own the most cards on the 3×3 board when it fills up. Hands don't count, " +
                        "with 9 board cards there's never a tie. Whoever owns more, wins.")
                    section("YOUR TURN",
                        "Tap one of your cards at the bottom, then tap an empty cell. The selected " +
                        "card lifts and glows gold; open cells light up so you know where you can place.")
                    section("CAPTURING",
                        "Every card has four numbers: top, left, right, bottom. When you place a card, " +
                        "its number on a touching side is compared with the neighbour's facing number, " +
                        "if yours is higher, that card flips to your color.")
                    section("ELEMENTS",
                        "On elemental boards, a card sitting on a matching element cell gets +1 on every " +
                        "side; on a mismatched cell it gets −1. Cells and elemental cards show the ◆ mark.")
                    section("WINNING",
                        "When the grid is full, whoever owns more board cards wins. 5/4 is a win, never a draw.")
                    Spacer(Modifier.height(4.dp))
                }
            }

            Spacer(Modifier.height(12.dp))
            Button(onDone, modifier = Modifier.fillMaxWidth()) { Text("GOT IT  ▶") }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun section(title: String, body: String) {
    Text(title, color = TutorialGold, fontSize = 15.sp, fontWeight = FontWeight.Bold,
         modifier = Modifier.padding(bottom = 3.dp))
    Text(body, color = TutorialBody, fontSize = 14.sp, lineHeight = 20.sp,
         modifier = Modifier.padding(bottom = 16.dp))
}
