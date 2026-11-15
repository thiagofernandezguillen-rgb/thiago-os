package be.nova.os.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.PathParser
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

@Immutable
class WallImages(val image: ImageBitmap, val backdrop: Bitmap, val version: Int)

/**
 * De achtergrond van het prototype (.wall): kleurverloop, 5 vervaagde kleurvlekken (multiply),
 * glans en lijnen. Wordt EEN keer op schermresolutie naar een bitmap gerenderd.
 * Daarnaast een kleine, vervaagde + verzadigde kopie: dat is wat backdrop-filter:blur(28px) saturate(185%)
 * achter de glazen panelen zou tonen.
 */
object Wall {
    val state = mutableStateOf<WallImages?>(null)
    @Volatile var backdrop: Bitmap? = null; private set
    @Volatile var version = 0; private set
    var deviceW = 1920; private set
    var deviceH = 1080; private set
    const val BACKDROP_DIV = 4

    fun renderAsync(w: Int, h: Int) {
        deviceW = w; deviceH = h
        Thread({
            val bmp = render(w, h)
            val bd = makeBackdrop(bmp, w / 1920f)
            backdrop = bd
            version = 1
            val img = WallImages(be.nova.os.data.Icons.toGpu(bmp).asImageBitmap(), bd, 1)
            android.os.Handler(android.os.Looper.getMainLooper()).post { state.value = img }
        }, "nova-wall").apply { priority = Thread.NORM_PRIORITY + 1 }.start()
    }

    private fun phi(x: Double): Double {
        // Standaardnormale verdelingsfunctie (Abramowitz-Stegun 7.1.26 voor erf)
        val z = x / sqrt(2.0)
        val t = 1.0 / (1.0 + 0.3275911 * abs(z))
        val y = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * exp(-z * z)
        val erf = if (z >= 0) y else -y
        return 0.5 * (1.0 + erf)
    }

    private fun lerpWhite(c: Int, a: Double): Int {
        val r = (255 + ((c shr 16 and 255) - 255) * a).roundToInt()
        val g = (255 + ((c shr 8 and 255) - 255) * a).roundToInt()
        val b = (255 + ((c and 255) - 255) * a).roundToInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    fun render(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(w / 1920f, h / 1080f)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)

        // linear-gradient(125deg, #E9E6FF 0%, #DDEBFF 30%, #E4F7D2 62%, #C9F06A 100%)
        val a = Math.toRadians(125.0)
        val dx = sin(a); val dy = -cos(a)
        val len = abs(1920 * sin(a)) + abs(1080 * cos(a))
        p.shader = LinearGradient(
            (960 - dx * len / 2).toFloat(), (540 - dy * len / 2).toFloat(),
            (960 + dx * len / 2).toFloat(), (540 + dy * len / 2).toFloat(),
            intArrayOf(0xFFE9E6FF.toInt(), 0xFFDDEBFF.toInt(), 0xFFE4F7D2.toInt(), 0xFFC9F06A.toInt()),
            floatArrayOf(0f, 0.30f, 0.62f, 1f), Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, 0f, 1920f, 1080f, p)

        // .blob — cirkel met filter:blur(90px) (σ = 90) en mix-blend-mode:multiply
        fun blob(cx: Float, cy: Float, r: Float, color: Int, opacity: Double) {
            val sigma = 90.0
            val outer = (r + 3.2 * sigma).toFloat()
            val n = 40
            val cols = IntArray(n + 1); val pos = FloatArray(n + 1)
            for (i in 0..n) {
                val t = i / n.toFloat()
                val d = t * outer
                val v = phi((r - d) / sigma) * opacity
                cols[i] = lerpWhite(color, v)
                pos[i] = t
            }
            p.shader = RadialGradient(cx, cy, outer, cols, pos, Shader.TileMode.CLAMP)
            p.xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
            c.drawRect(cx - outer, cy - outer, cx + outer, cy + outer, p)
            p.xfermode = null
        }
        blob(250f, 190f, 450f, 0xB9A8FF, 0.85)    // b1
        blob(1680f, 280f, 500f, 0xB6F04A, 0.85)   // b2
        blob(930f, 1090f, 410f, 0x7FE3FF, 0.85)   // b3
        blob(1360f, 960f, 320f, 0xFFC48A, 0.60)   // b4
        blob(460f, 700f, 280f, 0xFF9CDB, 0.45)    // b5

        // .sheen: radial-gradient(1200px 600px at 30% 0%, rgba(255,255,255,.55), transparent 70%)
        val sheen = RadialGradient(0f, 0f, 600f, intArrayOf(0x8CFFFFFF.toInt(), 0x00FFFFFF), floatArrayOf(0f, 0.7f), Shader.TileMode.CLAMP)
        sheen.setLocalMatrix(Matrix().apply { setScale(2f, 1f); postTranslate(576f, 0f) })
        p.shader = sheen
        c.drawRect(0f, 0f, 1920f, 1080f, p)

        // lijnen (svg)
        val lines = listOf(
            "M-100,820 C400,560 900,980 1400,560 S2000,260 2100,300" to false,
            "M-100,900 C500,700 1000,1050 1500,700 S2000,420 2100,460" to true,
            "M600,1180 C760,700 1100,260 1500,120 S1980,60 2100,90" to false,
            "M820,1180 C980,760 1300,380 1700,260 S2000,230 2100,250" to true,
            "M1300,1180 C1420,880 1700,640 2100,560" to true,
            "M-100,300 C300,420 600,160 1000,260" to true,
        )
        val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        val bounds = RectF()
        for ((d, thin) in lines) {
            val path = PathParser.createPathFromPathData(d)
            if (thin) {
                sp.shader = null; sp.color = 0xFFFFFFFF.toInt(); sp.alpha = (0.6f * 255).roundToInt(); sp.strokeWidth = 2f
            } else {
                @Suppress("DEPRECATION") path.computeBounds(bounds, true)
                sp.color = 0xFFFFFFFF.toInt()
                sp.shader = LinearGradient(bounds.left, 0f, bounds.right, 0f,
                    intArrayOf(0x33FFFFFF, 0xFFFFFFFF.toInt(), 0x4DFFFFFF), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
                sp.alpha = (0.9f * 255).roundToInt(); sp.strokeWidth = 4f
            }
            c.drawPath(path, sp)
        }
        return bmp
    }

    /** blur(28px) + saturate(185%) op kwart-resolutie. */
    private fun makeBackdrop(src: Bitmap, s: Float): Bitmap {
        val w = src.width / BACKDROP_DIV; val h = src.height / BACKDROP_DIV
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        p.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.85f) })
        c.drawBitmap(src, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), p)
        val sigma = 28f * s / BACKDROP_DIV
        val r = ((sqrt(4f * sigma * sigma + 1f) - 1f) / 2f).roundToInt().coerceAtLeast(1)
        val px = IntArray(w * h)
        out.getPixels(px, 0, w, 0, 0, w, h)
        val tmp = IntArray(w * h)
        repeat(3) {
            boxH(px, tmp, w, h, r)
            boxV(tmp, px, w, h, r)
        }
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    private fun boxH(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        for (y in 0 until h) {
            val row = y * w
            var sr = 0; var sg = 0; var sb = 0
            for (k in -r..r) {
                val v = src[row + k.coerceIn(0, w - 1)]
                sr += v shr 16 and 255; sg += v shr 8 and 255; sb += v and 255
            }
            for (x in 0 until w) {
                dst[row + x] = (0xFF shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val o = src[row + (x - r).coerceIn(0, w - 1)]
                val n = src[row + (x + r + 1).coerceIn(0, w - 1)]
                sr += (n shr 16 and 255) - (o shr 16 and 255)
                sg += (n shr 8 and 255) - (o shr 8 and 255)
                sb += (n and 255) - (o and 255)
            }
        }
    }

    private fun boxV(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        for (x in 0 until w) {
            var sr = 0; var sg = 0; var sb = 0
            for (k in -r..r) {
                val v = src[k.coerceIn(0, h - 1) * w + x]
                sr += v shr 16 and 255; sg += v shr 8 and 255; sb += v and 255
            }
            for (y in 0 until h) {
                dst[y * w + x] = (0xFF shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val o = src[(y - r).coerceIn(0, h - 1) * w + x]
                val n = src[(y + r + 1).coerceIn(0, h - 1) * w + x]
                sr += (n shr 16 and 255) - (o shr 16 and 255)
                sg += (n shr 8 and 255) - (o shr 8 and 255)
                sb += (n and 255) - (o and 255)
            }
        }
    }
}
