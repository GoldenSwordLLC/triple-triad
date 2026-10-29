package com.goldenswordllc.triad.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import triad.core.PlayerSave

private val PanelBg = Color(22, 15, 6).copy(alpha = 0.85f)
private val Gold = Color(0xFFD4AF37)
private val GoldText = Color(255, 240, 200)
private val Cream = Color(245, 238, 220)
private val DimText = Color(200, 178, 130)

@Composable
fun SettingsScreen(record: PlayerSave, lastCrash: String?, onWipeRecord: () -> Unit,
                   onSaveErrorToFile: () -> Unit, onCredits: () -> Unit,
                   onBackToMain: () -> Unit) {
    var confirmWipe by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val version = remember {
        runCatching {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
            "Version ${pi.versionName}  (build $code)"
        }.getOrDefault("Version ?")
    }

    Box(Modifier.fillMaxSize()) {
        MatBackground(Modifier.matchParentSize(), dim = 0.3f)
        Column(
            Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("SETTINGS", color = GoldText, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                 modifier = Modifier.padding(top = 8.dp, bottom = 12.dp))

            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(PanelBg)
            ) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)) {

                    sectionHeader("RECORD")
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        Stat("WINS", record.wins, Color(120, 190, 255))
                        Stat("LOSSES", record.losses, Color(255, 120, 100))
                    }
                    Text(
                        if (confirmWipe) "TAP AGAIN TO WIPE, THIS CANNOT BE UNDONE"
                        else "WIPE WIN / LOSS RECORD",
                        color = if (confirmWipe) Color(255, 120, 100) else DimText,
                        fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(30, 22, 8))
                            .clickable {
                                if (confirmWipe) { onWipeRecord(); confirmWipe = false }
                                else confirmWipe = true
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                    if (confirmWipe) {
                        Text("tapping anywhere else cancels", color = Color(170, 150, 110), fontSize = 10.sp)
                    }
                    Spacer(Modifier.height(22.dp))
                    Text("Credits  ▶", color = Color(160, 200, 255), fontSize = 13.sp,
                         fontWeight = FontWeight.Bold,
                         modifier = Modifier.clickable { onCredits() })

                    sectionHeader("ABOUT")
                    Text("Triad, a Triple Triad fan game", color = Cream, fontSize = 14.sp)
                    Text(version, color = DimText, fontSize = 12.sp)
                    Text("fan project, Square Enix owns Triple Triad",
                         color = Color(170, 150, 110), fontSize = 10.sp)

                    sectionHeader("DIAGNOSTICS")
                    Text(
                        if (lastCrash != null) "SAVE LAST ERROR TO FILE…"
                        else "No crashes recorded",
                        color = if (lastCrash != null) Color(160, 200, 255) else Color(170, 150, 110),
                        fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable(enabled = lastCrash != null) { onSaveErrorToFile() }
                            .padding(vertical = 6.dp),
                    )
                    if (lastCrash != null) {
                        Text("Opens a picker to choose where the diagnostics file is written.",
                             color = Color(170, 150, 110), fontSize = 10.sp)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(Color(120, 85, 20))
                    .border(2.dp, Gold, RoundedCornerShape(12.dp))
                    .clickable { onBackToMain() }.padding(vertical = 12.dp),
                contentAlignment = Alignment.Center) {
                Text("◀  BACK TO MAIN MENU", color = GoldText, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun sectionHeader(title: String) {
    Text(title, color = Gold, fontSize = 13.sp, fontWeight = FontWeight.Bold,
         modifier = Modifier.padding(top = 18.dp, bottom = 6.dp))
}

@Composable
private fun Stat(label: String, value: Int, color: Color) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text("$value", color = color, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(6.dp))
        Text(label, color = DimText, fontSize = 11.sp, fontWeight = FontWeight.Bold,
             modifier = Modifier.padding(bottom = 4.dp))
    }
}
