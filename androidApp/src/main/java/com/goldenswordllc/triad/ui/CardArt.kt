package com.goldenswordllc.triad.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import triad.core.Owner
import kotlin.math.roundToInt

object CardSheets {
    const val CARD_W = 375
    const val CARD_H = 525
    const val ASPECT = CARD_W.toFloat() / CARD_H

    private const val CACHE_LIMIT = 28

    private val cache = LinkedHashMap<String, Bitmap>(32, 0.75f, true)

    fun peek(cardNo: Int, owner: Owner): Bitmap? = synchronized(cache) { cache[key(cardNo, owner)] }

    suspend fun load(context: Context, cardNo: Int, owner: Owner): Bitmap {
        peek(cardNo, owner)?.let { return it }
        return withContext(Dispatchers.IO) {
            val color = if (owner == Owner.blue) "blue" else "red"
            val name = "art/cards/$color/" + "%03d".format(cardNo.coerceIn(1, 110)) + ".jpg"
            val bmp = context.assets.open(name).use { BitmapFactory.decodeStream(it) }
                ?: error("missing card asset $name")
            synchronized(cache) {
                cache[key(cardNo, owner)] = bmp
                while (cache.size > CACHE_LIMIT) {
                    val eldest = cache.keys.firstOrNull() ?: break
                    cache.remove(eldest)
                }
            }
            bmp
        }
    }

    private fun key(cardNo: Int, owner: Owner) =
        cardNo.coerceIn(1, 110).toString() + ":" + (if (owner == Owner.blue) "b" else "r")
}

@Composable
fun CardArt(cardNo: Int, owner: Owner, modifier: Modifier = Modifier) {
    val context = LocalContext.current

    val bmp by produceState<Bitmap?>(initialValue = CardSheets.peek(cardNo, owner), cardNo, owner) {
        value = CardSheets.load(context, cardNo, owner)
    }
    Canvas(modifier) {
        bmp?.let { drawCardSprite(it, cardNo) }
    }
}

private fun DrawScope.drawCardSprite(sheet: Bitmap, cardNo: Int) {
    drawImage(
        sheet.asImageBitmap(),
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(sheet.width, sheet.height),
        dstOffset = IntOffset.Zero,
        dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
    )
}

object MatCache {
    @Volatile private var mat: Bitmap? = null
    fun peek(): Bitmap? = mat
    suspend fun load(context: Context): Bitmap {
        mat?.let { return it }
        return withContext(Dispatchers.IO) {
            if (mat == null) {
                val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                mat = context.assets.open("art/playmat.jpg").use {
                    BitmapFactory.decodeStream(it, null, opts)
                }
            }
            mat!!
        }
    }
}

@Composable
fun MatBackground(modifier: Modifier = Modifier, dim: Float = 0.12f, bottomScrim: Boolean = false) {
    val context = LocalContext.current
    val mat by produceState<Bitmap?>(initialValue = MatCache.peek()) {
        if (value == null) value = MatCache.load(context)
    }
    Canvas(modifier) {
        mat?.let {
            val scale = maxOf(size.width / it.width, size.height / it.height)
            val srcW = (size.width / scale).toInt().coerceAtMost(it.width)
            val srcH = (size.height / scale).toInt().coerceAtMost(it.height)
            drawImage(
                it.asImageBitmap(),
                srcOffset = IntOffset((it.width - srcW) / 2, (it.height - srcH) / 2),
                srcSize = IntSize(srcW, srcH),
                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
            )
            drawRect(Color(0f, 0f, 0f, dim))
        }
    }
    if (bottomScrim) {
        Box(
            Modifier
                .then(modifier)
                .background(
                    Brush.verticalGradient(
                        0.35f to Color.Transparent,
                        1f to Color(0f, 0f, 0f, 0.78f),
                    )
                )
        )
    }
}
