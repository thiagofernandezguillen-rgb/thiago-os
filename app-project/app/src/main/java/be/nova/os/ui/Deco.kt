package be.nova.os.ui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Region
import android.graphics.Shader
import android.os.Build
import android.util.LruCache
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/** CSS box-shadow: x, y, blur, spread, kleur (ARGB). */
@Immutable
class Sh(val dx: Float, val dy: Float, val blur: Float, val spread: Float, val color: Int)

private var decoIds = 0

/**
 * Een "CSS-doos": achtergrond (verticaal verloop), rand, inset- en buitenschaduwen,
 * optioneel met backdrop (vervaagde + verzadigde achtergrond, zoals backdrop-filter).
 * Wordt per grootte EEN keer naar een bitmap gerenderd en daarna enkel nog getekend.
 */
@Immutable
class Deco(
    val radius: Float,                 // ontwerp-px; < 0 = pil (999px)
    val fillTop: Int = 0,
    val fillBottom: Int = fillTop,
    val border: Float = 0f,
    val borderColor: Int = 0,
    val outer: List<Sh> = emptyList(),
    val inset: List<Sh> = emptyList(),
    val backdrop: Boolean = false,
    val gloss: Float = 0f,             // ::after glans: wit met deze alpha bovenaan
    val glossStop: Float = 0.5f,
) {
    val id = ++decoIds
}

private fun argb(a: Float, rgb: Int) = ((a * 255).roundToInt() shl 24) or (rgb and 0xFFFFFF)

object Decos {
    private val glassFillTop = argb(0.62f, 0xFFFFFF)
    private val glassFillBottom = argb(0.28f, 0xFFFFFF)
    private val shadowTint = 0x28325A

    /** .glass */
    val glass = Deco(
        radius = -1f, fillTop = glassFillTop, fillBottom = glassFillBottom,
        border = 1.5f, borderColor = argb(0.8f, 0xFFFFFF),
        inset = listOf(Sh(0f, 1.5f, 0f, 0f, argb(0.95f, 0xFFFFFF)), Sh(0f, -1f, 0f, 0f, argb(0.35f, 0xFFFFFF)),
            Sh(0f, 0f, 22f, 0f, argb(0.25f, 0xFFFFFF))),
        outer = listOf(Sh(0f, 10f, 40f, 0f, argb(0.14f, shadowTint)), Sh(0f, 2f, 6f, 0f, argb(0.08f, shadowTint))),
        backdrop = true,
    )

    /** .status.glass en .btn.light.glass (overschreven regel in het prototype) */
    val glassLite = Deco(
        radius = -1f, fillTop = glassFillTop, fillBottom = glassFillBottom,
        border = 1.5f, borderColor = argb(0.8f, 0xFFFFFF),
        inset = listOf(Sh(0f, 1.5f, 0f, 0f, argb(0.95f, 0xFFFFFF)), Sh(0f, 0f, 22f, 0f, argb(0.25f, 0xFFFFFF))),
        outer = listOf(Sh(0f, 10f, 40f, 0f, argb(0.14f, shadowTint))),
        backdrop = true,
    )

    fun glassR(r: Float) = Deco(
        radius = r, fillTop = glass.fillTop, fillBottom = glass.fillBottom, border = glass.border,
        borderColor = glass.borderColor, inset = glass.inset, outer = glass.outer, backdrop = true,
    )

    val setList = glassR(38f)
    val setDetail = glassR(38f)
    val cc = glassR(44f)
    val menu = glassR(38f)

    /** .nav button.on */
    val navOn = Deco(
        radius = -1f, fillTop = argb(0.9f, 0xFFFFFF),
        inset = listOf(Sh(0f, 1f, 0f, 0f, 0xFFFFFFFF.toInt())),
        outer = listOf(Sh(0f, 4f, 14f, 0f, argb(0.12f, shadowTint))),
    )

    /** .btn.dark */
    val btnDark = Deco(radius = -1f, fillTop = 0xFF141826.toInt(), outer = listOf(Sh(0f, 8f, 24f, 0f, argb(0.25f, 0x141826))))

    /** .tile (alleen schaduw; het icoon zelf is een aparte bitmap) */
    val tileShadow = Deco(radius = 44f, outer = listOf(Sh(0f, 14f, 30f, 0f, argb(0.18f, 0x141E3C))))

    /** .app.focus .tile */
    val tileFocus = Deco(
        radius = 44f,
        outer = listOf(Sh(0f, 0f, 0f, 5f, 0xFFFFFFFF.toInt()), Sh(0f, 0f, 0f, 9f, 0xFF4F5BD5.toInt()),
            Sh(0f, 30f, 50f, 0f, argb(0.3f, 0x141E3C))),
    )

    /** .bigicon */
    val bigIcon = Deco(radius = 92f, outer = listOf(Sh(0f, 40f, 80f, 0f, argb(0.28f, 0x141E3C))))

    /** .app .fav */
    val fav = Deco(radius = -1f, fillTop = 0xFFFFFFFF.toInt(), outer = listOf(Sh(0f, 4f, 10f, 0f, argb(0.15f, 0x000000))))

    /** .si.on */
    val siOn = Deco(radius = 24f, fillTop = argb(0.85f, 0xFFFFFF), outer = listOf(Sh(0f, 4f, 14f, 0f, argb(0.1f, shadowTint))))

    /** .ct en .slider */
    val ct = Deco(radius = 28f, fillTop = argb(0.55f, 0xFFFFFF), border = 1.5f, borderColor = argb(0.8f, 0xFFFFFF))

    /** .pill */
    val pill = Deco(radius = -1f, fillTop = argb(0.07f, 0x141826))
    val pillDark = Deco(radius = -1f, fillTop = 0xFF141826.toInt())

    /** .sw */
    val swKnob = Deco(radius = -1f, fillTop = 0xFFFFFFFF.toInt(), outer = listOf(Sh(0f, 2f, 6f, 0f, argb(0.2f, 0x000000))))
}

class Baked(val bmp: Bitmap, val margin: Int)

object DecoBaker {
    private val cache = object : LruCache<String, Baked>(14 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Baked) = value.bmp.byteCount
    }

    fun get(d: Deco, w: Int, h: Int, s: Float, ox: Int, oy: Int): Baked {
        val bd = if (d.backdrop) Wall.backdrop else null
        val key = if (bd != null) "${d.id}|$w|$h|$ox|$oy|${Wall.version}" else "${d.id}|$w|$h"
        cache.get(key)?.let { return it }
        val b = bake(d, w, h, s, ox, oy, bd)
        cache.put(key, b)
        return b
    }

    private fun blurRadius(sigma: Float) = ((sigma - 0.5f) / 0.57735f).coerceAtLeast(0.1f)

    private fun bake(d: Deco, w: Int, h: Int, s: Float, ox: Int, oy: Int, bd: Bitmap?): Baked {
        val rad = if (d.radius < 0) h / 2f else d.radius * s
        var m = 0f
        for (o in d.outer) m = max(m, o.blur * s + o.spread * s + max(abs(o.dx), abs(o.dy)) * s)
        val mi = ceil(m).toInt() + 2
        val bmp = Bitmap.createBitmap(w + 2 * mi, h + 2 * mi, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.translate(mi.toFloat(), mi.toFloat())
        val box = RectF(0f, 0f, w.toFloat(), h.toFloat())
        val boxPath = Path().apply { addRoundRect(box, rad, rad, Path.Direction.CW) }
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        // 1. buitenschaduwen (niet onder de doos zelf, zoals in CSS)
        if (d.outer.isNotEmpty()) {
            c.save()
            if (Build.VERSION.SDK_INT >= 26) c.clipOutPath(boxPath)
            else @Suppress("DEPRECATION") c.clipPath(boxPath, Region.Op.DIFFERENCE)
            for (o in d.outer.asReversed()) {
                p.reset(); p.isAntiAlias = true; p.color = o.color
                val sigma = o.blur * s / 2f
                p.maskFilter = if (sigma > 0.5f) BlurMaskFilter(blurRadius(sigma), BlurMaskFilter.Blur.NORMAL) else null
                val sp = o.spread * s
                val r = RectF(box); r.offset(o.dx * s, o.dy * s); r.inset(-sp, -sp)
                val rr = max(0f, rad + sp)
                c.drawRoundRect(r, rr, rr, p)
            }
            c.restore()
        }

        // 2. binnenkant op een eigen laag, daarna afgeknipt tot de afgeronde doos
        val hasInside = bd != null || (d.fillTop ushr 24) != 0 || (d.fillBottom ushr 24) != 0 ||
            d.inset.isNotEmpty() || d.border > 0f || d.gloss > 0f
        if (hasInside) {
            val layer = c.saveLayer(box, null)
            p.reset(); p.isAntiAlias = true
            if (bd != null) {
                val k = bd.width.toFloat() / Wall.deviceW
                val sh = BitmapShader(bd, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                sh.setLocalMatrix(Matrix().apply { setScale(1f / k, 1f / k); postTranslate(-ox.toFloat(), -oy.toFloat()) })
                p.shader = sh; p.isFilterBitmap = true
                c.drawRect(box, p)
                p.shader = null
            }
            if ((d.fillTop ushr 24) != 0 || (d.fillBottom ushr 24) != 0) {
                if (d.fillTop == d.fillBottom) { p.shader = null; p.color = d.fillTop }
                else p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), d.fillTop, d.fillBottom, Shader.TileMode.CLAMP)
                c.drawRect(box, p)
                p.shader = null
            }
            if (d.gloss > 0f) {
                p.shader = LinearGradient(0f, 0f, 0f, h * d.glossStop, argb(d.gloss, 0xFFFFFF), 0x00FFFFFF, Shader.TileMode.CLAMP)
                c.drawRect(box, p); p.shader = null
            }
            // inset-schaduwen binnen de padding-box
            val b = d.border * s
            val pb = RectF(box).apply { inset(b, b) }
            val prad = max(0f, rad - b)
            for (o in d.inset.asReversed()) {
                p.reset(); p.isAntiAlias = true; p.color = o.color
                val sigma = o.blur * s / 2f
                p.maskFilter = if (sigma > 0.5f) BlurMaskFilter(blurRadius(sigma), BlurMaskFilter.Blur.NORMAL) else null
                val hole = RectF(pb); hole.offset(o.dx * s, o.dy * s); hole.inset(o.spread * s, o.spread * s)
                val path = Path()
                path.addRect(-w.toFloat(), -h.toFloat(), 2f * w, 2f * h, Path.Direction.CW)
                path.addRoundRect(hole, prad, prad, Path.Direction.CCW)
                path.fillType = Path.FillType.EVEN_ODD
                c.save(); c.clipRect(pb)
                c.drawPath(path, p)
                c.restore()
            }
            p.maskFilter = null
            if (b > 0f) {
                p.reset(); p.isAntiAlias = true; p.style = Paint.Style.STROKE; p.strokeWidth = b; p.color = d.borderColor
                val br = RectF(box).apply { inset(b / 2f, b / 2f) }
                c.drawRoundRect(br, max(0f, rad - b / 2f), max(0f, rad - b / 2f), p)
            }
            // masker: alleen binnen de afgeronde doos houden (anti-aliased)
            p.reset(); p.isAntiAlias = true; p.color = 0xFFFFFFFF.toInt()
            p.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            val mask = c.saveLayer(box, p)
            p.xfermode = null
            c.drawRoundRect(box, rad, rad, p)
            c.restoreToCount(mask)
            c.restoreToCount(layer)
        }
        return Baked(be.nova.os.data.Icons.toGpu(bmp), mi)
    }
}

/* -------------------------- Compose-koppeling -------------------------- */

private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

fun DrawScope.drawBaked(b: Baked, alpha: Float = 1f) {
    if (alpha <= 0.004f) return
    drawIntoCanvas {
        bitmapPaint.alpha = (alpha * 255).roundToInt().coerceIn(0, 255)
        it.nativeCanvas.drawBitmap(b.bmp, -b.margin.toFloat(), -b.margin.toFloat(), bitmapPaint)
    }
}

/** Tekent een Deco zonder backdrop (schaduwen, knoppen, ...). */
fun DrawScope.drawDeco(d: Deco, alpha: Float = 1f, w: Int = size.width.roundToInt(), h: Int = size.height.roundToInt()) {
    if (w <= 0 || h <= 0 || alpha <= 0.004f) return
    drawBaked(DecoBaker.get(d, w, h, density, 0, 0), alpha)
}

/**
 * Modifier: tekent de Deco achter de inhoud.
 * [origin] = positie op het scherm in px (voor de backdrop); null = automatisch via plaatsing,
 * gecorrigeerd met [dy] (bv. de scrollpositie van Home).
 */
fun Modifier.deco(
    d: Deco,
    alpha: (() -> Float)? = null,
    origin: ((IntSize) -> IntOffset)? = null,
    dy: (() -> Float)? = null,
): Modifier = this then DecoElement(d, alpha, origin, dy)

private class DecoElement(
    val d: Deco, val alpha: (() -> Float)?, val origin: ((IntSize) -> IntOffset)?, val dy: (() -> Float)?,
) : ModifierNodeElement<DecoNode>() {
    override fun create() = DecoNode(d, alpha, origin, dy)
    override fun update(node: DecoNode) {
        node.d = d; node.alpha = alpha; node.origin = origin; node.dy = dy
        node.invalidateDraw()
    }
    override fun InspectorInfo.inspectableProperties() { name = "deco" }
    override fun equals(other: Any?) = other is DecoElement && other.d === d && other.alpha === alpha &&
        other.origin === origin && other.dy === dy
    override fun hashCode() = d.id
}

private class DecoNode(
    var d: Deco, var alpha: (() -> Float)?, var origin: ((IntSize) -> IntOffset)?, var dy: (() -> Float)?,
) : Modifier.Node(), DrawModifierNode, LayoutAwareModifierNode {
    private var placed = IntOffset.Zero

    override fun onPlaced(coordinates: LayoutCoordinates) {
        if (origin != null || !d.backdrop) return
        val pos = coordinates.positionInRoot()
        val fix = dy?.invoke() ?: 0f
        val np = IntOffset(pos.x.roundToInt(), (pos.y - fix).roundToInt())
        if (np != placed) { placed = np; invalidateDraw() }
    }

    override fun ContentDrawScope.draw() {
        val w = size.width.roundToInt(); val h = size.height.roundToInt()
        if (w > 0 && h > 0) {
            @Suppress("UNUSED_VARIABLE") val v = if (d.backdrop) Wall.state.value else null // herteken als de achtergrond klaar is
            val o = if (d.backdrop) origin?.invoke(IntSize(w, h)) ?: placed else IntOffset.Zero
            drawBaked(DecoBaker.get(d, w, h, density, o.x, o.y), alpha?.invoke() ?: 1f)
        }
        drawContent()
    }
}

/** CSS outline (focusrand) met outline-offset; volgt de afronding zoals Chrome. */
fun DrawScope.ring(offsetDp: Float, radiusDp: Float, color: Color = Nc.accent, widthDp: Float = 3f, alpha: Float = 1f) {
    if (alpha <= 0.004f) return
    val sw = widthDp * density
    val o = offsetDp * density + sw / 2f
    val base = if (radiusDp < 0) size.height / 2f else radiusDp * density
    val r = max(0f, base + o)
    drawRoundRect(
        color = color, alpha = alpha,
        topLeft = Offset(-o, -o), size = Size(size.width + 2 * o, size.height + 2 * o),
        cornerRadius = CornerRadius(r, r), style = Stroke(sw),
    )
}
