package com.goldenswordllc.triad

import android.content.Context
import triad.core.PlayerSave
import triad.core.saveJson
import java.io.File

fun loadSave(context: Context): PlayerSave =
    File(context.filesDir, "save.json").takeIf { it.exists() }?.let {
        runCatching { saveJson.decodeFromString<PlayerSave>(it.readText()) }.getOrNull()
    } ?: PlayerSave()

fun persist(context: Context, s: PlayerSave) {
    val dir = context.filesDir
    val tmp = File(dir, "save.json.tmp")
    tmp.writeText(saveJson.encodeToString(PlayerSave.serializer(), s))
    val f = File(dir, "save.json")
    if (!tmp.renameTo(f)) { f.delete(); check(tmp.renameTo(f)) { "save failed" } }
}
