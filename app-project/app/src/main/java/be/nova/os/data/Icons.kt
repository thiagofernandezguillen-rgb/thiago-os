package be.nova.os.data

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Echte app-iconen als afgeronde tegels (zoals .tile in het prototype), klein gerenderd en in een LruCache.
 * - Adaptieve iconen: achtergrond + voorgrond schermvullend.
 * - Gewone iconen die al een volle vierkante vorm hebben: schermvullend.
 * - Overige (logo op transparant): logo op een verloop in de eigen kleur van het icoon.
 */
object Icons {
    private val lru = object : LruCache<String, ImageBitmap>(10 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val worker = Dispatchers.Default.limitedParallelism(2)

    /** Android 8+: pixels alleen nog op de GPU bewaren (geen dubbele kopie in het RAM). */
    fun toGpu(b: Bitmap): Bitmap {
        if (Build.VERSION.SDK_INT >= 26) {
            val h = try { b.copy(Bitmap.Config.HARDWARE, false) } catch (_: Throwable) { null }
            if (h != null) { b.recycle(); return h }
        }
        b.prepareToDraw()
        return b
    }

    fun key(app: AppEntry, px: Int) = "${app.iconKey}@$px"
    fun peek(app: AppEntry, px: Int): ImageBitmap? = lru.get(key(app, px))

    suspend fun load(ctx: Context, app: AppEntry, px: Int, big: Boolean): ImageBitmap = withContext(worker) {
        val k = key(app, px)
        lru.get(k) ?: run {
            val bmp = try { render(ctx, app, px, big) } catch (_: Throwable) { fallback(px, big) }
            toGpu(bmp).asImageBitmap().also { lru.put(k, it) }
        }
    }

    private fun loadDrawable(ctx: Context, app: AppEntry, density: Int): Drawable? {
        val pm = ctx.packageManager
        val cn = ComponentName(app.pkg, app.cls)
        try {
            val ai = pm.getActivityInfo(cn, 0)
            val res = pm.getResourcesForApplication(ai.applicationInfo)
            val id = if (ai.icon != 0) ai.icon else ai.applicationInfo.icon
            if (id != 0) {
                @Suppress("DEPRECATION")
                res.getDrawableForDensity(id, density, null)?.let { return it }
            }
        } catch (_: Exception) { }
        return try { pm.getActivityIcon(cn) } catch (_: PackageManager.NameNotFoundException) {
            try { pm.getApplicationIcon(app.pkg) } catch (_: Exception) { null }
        }
    }

    private fun radiusFor(n: Int, big: Boolean) = n * if (big) 92f / 400f else 44f / 186f

    private fun render(ctx: Context, app: AppEntry, n: Int, big: Boolean): Bitmap {
        val d = loadDrawable(ctx, app, if (big) 640 else 320) ?: return fallback(n, big)
        val out = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val box = RectF(0f, 0f, n.toFloat(), n.toFloat())
        val layer = c.saveLayer(box, null)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        if (Build.VERSION.SDK_INT >= 26 && d is AdaptiveIconDrawable) {
            val l = (n * 1.5f).roundToInt(); val off = (n - l) / 2
            d.background?.let { it.setBounds(off, off, off + l, off + l); it.draw(c) }
            d.foreground?.let { it.setBounds(off, off, off + l, off + l); it.draw(c) }
        } else {
            val src = toBitmap(d, 192)
            if (isFullBleed(src)) {
                c.drawBitmap(src, null, box, p)
            } else {
                val (top, bottom) = backgroundFor(src)
                // linear-gradient(160deg, licht, donker)
                val a = Math.toRadians(160.0)
                val dx = sin(a).toFloat(); val dy = -cos(a).toFloat()
                val len = n * (abs(sin(a)) + abs(cos(a))).toFloat()
                val cx = n / 2f
                p.shader = LinearGradient(cx - dx * len / 2, cx - dy * len / 2, cx + dx * len / 2, cx + dy * len / 2,
                    top, bottom, Shader.TileMode.CLAMP)
                c.drawRect(box, p); p.shader = null
                val content = contentBounds(src)
                val target = n * 0.62f
                val sc = target / max(content.width(), content.height()).coerceAtLeast(1)
                val w = content.width() * sc; val h = content.height() * sc
                c.drawBitmap(src, content, RectF((n - w) / 2, (n - h) / 2, (n + w) / 2, (n + h) / 2), p)
            }
        }
        // ::after glans
        val gloss = if (big) 0.28f else 0.30f
        val stop = if (big) 0.45f else 0.5f
        p.shader = LinearGradient(0f, 0f, 0f, n * stop, Color.argb((gloss * 255).roundToInt(), 255, 255, 255), 0x00FFFFFF, Shader.TileMode.CLAMP)
        c.drawRect(box, p); p.shader = null
        if (big) { // inset 0 2px 0 rgba(255,255,255,.35)
            val r = radiusFor(n, true)
            p.color = Color.argb(89, 255, 255, 255); p.style = Paint.Style.STROKE; p.strokeWidth = n * 2f / 400f * 1.5f
            c.save(); c.clipRect(0f, 0f, n.toFloat(), r)
            c.drawRoundRect(RectF(box).apply { inset(p.strokeWidth / 2, p.strokeWidth / 2) }, r, r, p)
            c.restore(); p.style = Paint.Style.FILL
        }
        mask(c, box, radiusFor(n, big))
        c.restoreToCount(layer)
        return out
    }

    private fun mask(c: Canvas, box: RectF, r: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        val l = c.saveLayer(box, p)
        p.xfermode = null; p.color = Color.WHITE
        c.drawRoundRect(box, r, r, p)
        c.restoreToCount(l)
    }

    private fun fallback(n: Int, big: Boolean): Bitmap {
        val out = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, n.toFloat(), n.toFloat(), 0xFF5B5FE8.toInt(), 0xFF2B2F9E.toInt(), Shader.TileMode.CLAMP)
        val r = radiusFor(n, big)
        c.drawRoundRect(RectF(0f, 0f, n.toFloat(), n.toFloat()), r, r, p)
        return out
    }

    private fun toBitmap(d: Drawable, size: Int): Bitmap {
        if (d is BitmapDrawable && d.bitmap != null && d.bitmap.width >= size / 2) return d.bitmap
        val w = if (d.intrinsicWidth > 0) d.intrinsicWidth.coerceAtMost(size) else size
        val h = if (d.intrinsicHeight > 0) d.intrinsicHeight.coerceAtMost(size) else size
        val sc = size.toFloat() / max(w, h)
        val bw = (w * sc).roundToInt(); val bh = (h * sc).roundToInt()
        val b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, bw, bh); d.draw(Canvas(b))
        return b
    }

    private fun alphaAt(b: Bitmap, fx: Float, fy: Float) =
        Color.alpha(b.getPixel((fx * (b.width - 1)).roundToInt(), (fy * (b.height - 1)).roundToInt()))

    /** Volle vierkante tegel? (dan schermvullend tekenen) */
    private fun isFullBleed(b: Bitmap): Boolean {
        val pts = floatArrayOf(0.5f, 0.03f, 0.03f, 0.5f, 0.97f, 0.5f, 0.5f, 0.97f, 0.1f, 0.1f, 0.9f, 0.1f, 0.1f, 0.9f, 0.9f, 0.9f)
        var i = 0
        while (i < pts.size) { if (alphaAt(b, pts[i], pts[i + 1]) < 245) return false; i += 2 }
        return true
    }

    private fun contentBounds(b: Bitmap): Rect {
        var l = b.width; var t = b.height; var r = 0; var bo = 0
        val step = max(1, min(b.width, b.height) / 96)
        var y = 0
        while (y < b.height) {
            var x = 0
            while (x < b.width) {
                if (Color.alpha(b.getPixel(x, y)) > 24) { if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > bo) bo = y }
                x += step
            }
            y += step
        }
        return if (r <= l || bo <= t) Rect(0, 0, b.width, b.height) else Rect(l, t, min(b.width, r + step), min(b.height, bo + step))
    }

    /** Verloop in de hoofdkleur van het logo; wit logo → donkere tegel, donker logo → lichte tegel. */
    private fun backgroundFor(b: Bitmap): Pair<Int, Int> {
        var rs = 0.0; var gs = 0.0; var bs = 0.0; var ws = 0.0; var sat = 0.0
        val step = max(1, min(b.width, b.height) / 48)
        val hsv = FloatArray(3)
        var y = 0
        while (y < b.height) {
            var x = 0
            while (x < b.width) {
                val c = b.getPixel(x, y); val a = Color.alpha(c) / 255.0
                if (a > 0.5) {
                    Color.colorToHSV(c, hsv)
                    val wgt = a * (0.15 + hsv[1]) // verzadigde pixels tellen zwaarder
                    rs += Color.red(c) * wgt; gs += Color.green(c) * wgt; bs += Color.blue(c) * wgt; ws += wgt; sat += hsv[1] * a
                }
                x += step
            }
            y += step
        }
        if (ws <= 0.0) return 0xFF3B3F47.toInt() to 0xFF15171B.toInt()
        val r = (rs / ws).toInt(); val g = (gs / ws).toInt(); val bl = (bs / ws).toInt()
        Color.RGBToHSV(r, g, bl, hsv)
        val lum = (0.2126 * r + 0.7152 * g + 0.0722 * bl) / 255.0
        return when {
            hsv[1] < 0.18f && lum > 0.7 -> 0xFF3B3F47.toInt() to 0xFF15171B.toInt()   // wit/grijs logo
            hsv[1] < 0.18f -> 0xFFFFFFFF.toInt() to 0xFFE3E7F1.toInt()                 // zwart/donker logo
            else -> {
                val top = FloatArray(3).also { it[0] = hsv[0]; it[1] = (hsv[1] * 0.85f).coerceIn(0.35f, 0.9f); it[2] = (hsv[2] * 1.05f + 0.12f).coerceIn(0.55f, 1f) }
                val bot = FloatArray(3).also { it[0] = hsv[0]; it[1] = (hsv[1] * 1.0f).coerceIn(0.45f, 1f); it[2] = (hsv[2] * 0.75f).coerceIn(0.3f, 0.85f) }
                Color.HSVToColor(top) to Color.HSVToColor(bot)
            }
        }
    }
}
