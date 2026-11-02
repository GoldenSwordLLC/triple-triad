package com.goldenswordllc.triad.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import triad.core.CardDef
import triad.core.Owner

private val CreditsGoldText = Color(255, 240, 200)
private val CreditsCream = Color(245, 238, 220)
private val CreditsLabel = Color(230, 205, 160)
private val CreditsLink = Color(160, 200, 255)

@Composable
fun CreditsScreen(catalog: Map<String, CardDef>, onBack: () -> Unit) {
    val context = LocalContext.current

    var pair by remember { mutableStateOf(randomHighPair(catalog)) }

    Box(Modifier.fillMaxSize()) {
        MatBackground(Modifier.matchParentSize(), dim = 0.3f)
        Column(
            Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                pair.forEach { (no, owner) ->
                    val display = CardDef(id = "credit", name = "", level = 9,
                                          element = triad.core.Element.none,
                                          top = 1, right = 1, bottom = 1, left = 1, no = no)

                    CardThumb(def = display, conflict = false, owner = owner,
                              modifier = Modifier.width(96.dp).aspectRatio(CardSheets.ASPECT))
                }
            }
            Spacer(Modifier.height(10.dp))

            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(22, 15, 6).copy(alpha = 0.88f))
            ) {
                Column(
                    Modifier.fillMaxSize().padding(vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("TRIAD", color = CreditsGoldText, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    creditsSection("Development", "GoldenSword LLC")
                    creditsLink("Github") { context.openUrl("https://github.com/GoldenSwordLLC/triple-triad") }
                    creditsSection("Card Art", "Tobias Vyseri")
                    creditsLink("vyseri.com") { context.openUrl("https://vyseri.com") }
                    creditsSection("Special Thanks", "Square Enix")
                }
            }
            Spacer(Modifier.height(12.dp))

            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(Color(120, 85, 20))
                    .border(2.dp, Color(0xFFD4AF37), RoundedCornerShape(12.dp))
                    .clickable { onBack() }.padding(vertical = 12.dp),
                contentAlignment = Alignment.Center) {
                Text("◀  BACK TO SETTINGS", color = CreditsGoldText, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

internal fun randomHighPair(catalog: Map<String, CardDef>): List<Pair<Int, Owner>> {
    val high = catalog.values.filter { it.level >= 9 }.map { it.no }
    if (high.isEmpty()) return listOf(110 to Owner.red, 89 to Owner.blue)
    val red = high.random()
    var blue = high.random()
    while (blue == red) blue = high.random()
    return listOf(blue to Owner.blue, red to Owner.red)
}

@Composable
private fun creditsSection(title: String, body: String) {
    Text(title, color = CreditsLabel, fontSize = 11.sp, fontWeight = FontWeight.Bold,
         modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
    Text(body, color = CreditsCream, fontSize = 14.sp)
}

@Composable
private fun creditsLink(text: String, onClick: () -> Unit) {
    Text(text, color = CreditsLink, fontSize = 12.sp, fontWeight = FontWeight.Bold,
         modifier = Modifier.clickable(onClick = onClick))
}

private fun Context.openUrl(url: String) {
    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}
