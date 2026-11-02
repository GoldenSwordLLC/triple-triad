package com.goldenswordllc.triad.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import triad.core.CardDef
import triad.core.Owner

private val DialogBg = Color(30, 22, 8).copy(alpha = 0.97f)

@Composable
fun CardInfoDialog(def: CardDef, owner: Owner, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose) {
        Box(
            Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(DialogBg)
                .border(2.dp, Color(0xFFD4AF37), RoundedCornerShape(16.dp))
                .padding(16.dp)
                .clickable { onClose() },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(def.name, color = Color(255, 240, 200), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("No. G-${def.no}   ·   Level ${def.level}   ·   ${def.type.name.lowercase()}",
                     color = Color(230, 205, 160), fontSize = 12.sp)
                if (def.element != triad.core.Element.none) {
                    Text("◆ ${def.element.name.lowercase()}",
                         color = elementColor(def.element), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                } else {
                    Text("no element", color = Color(170, 150, 110), fontSize = 12.sp)
                }
                Spacer(Modifier.height(12.dp))
                CardArt(cardNo = def.no, owner = owner,
                        modifier = Modifier.width(240.dp).aspectRatio(CardSheets.ASPECT))
                Spacer(Modifier.height(12.dp))

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    RankLine("TOP", def.top)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RankLine("LEFT", def.left, Modifier.weight(1f))
                        Box(Modifier.size(width = 4.dp, height = 30.dp))
                        RankLine("RIGHT", def.right, Modifier.weight(1f))
                    }
                    RankLine("BOTTOM", def.bottom)
                }
                Spacer(Modifier.height(6.dp))
                Text("tap to close", color = Color(170, 150, 110), fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun RankLine(label: String, value: Int, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color(170, 150, 110), fontSize = 10.sp)
        Spacer(Modifier.width(8.dp))
        Text(if (value == 10) "A" else "$value", color = Color(255, 240, 200),
             fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun LeaveMatchDialog(onLeave: () -> Unit, onStay: () -> Unit, warning: String) {
    AlertDialog(
        onDismissRequest = onStay,
        title = { Text("Leave this match?", color = Color(40, 28, 12), fontWeight = FontWeight.Bold) },
        text = { Text(warning, color = Color(60, 50, 35)) },
        confirmButton = { TextButton(onLeave) { Text("LEAVE", color = Color(183, 28, 28), fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onStay) { Text("STAY", fontWeight = FontWeight.Bold) } },
    )
}
