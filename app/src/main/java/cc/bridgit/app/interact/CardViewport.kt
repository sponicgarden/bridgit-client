package cc.bridgit.app.interact

import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** How a card is presented (A0074): inside Bridgit's bordered box, or edge to edge with every bar hidden. */
enum class Presentation(val id: String) {
    STANDARD("standard"), FULL_SCREEN("full_screen");
    companion object {
        fun of(id: String?) = values().firstOrNull { it.id == id }
        val IDS = values().map { it.id }
    }
}

/** A size in pixels. */
data class Px(val w: Int, val h: Int) {
    val ratio get() = if (h == 0) 0.0 else w.toDouble() / h
    fun json(): JSONObject = JSONObject().put("width_px", w).put("height_px", h)
}

/**
 * The display, as read from Android ([cc.bridgit.app.interact.DisplayInfo]): the full physical display in its current
 * rotation, the cutout along its long axis (summed, both ends), and the bars standard cards share the screen with.
 */
data class DisplayGeometry(
    val widthPx: Int, val heightPx: Int, val densityDpi: Int, val density: Float, val rotation: Int,
    /** Display cutout safe insets along the long axis (top+bottom in portrait, left+right in landscape). */
    val cutoutLongPx: Int = 0,
    val statusBarPx: Int = 0, val navBarPx: Int = 0, val refreshHz: Int? = null,
) {
    val portrait get() = heightPx >= widthPx
    val shortPx get() = min(widthPx, heightPx)
    val longPx get() = max(widthPx, heightPx)
    /** The full display in natural (portrait) orientation. */
    val natural get() = Px(shortPx, longPx)
    fun dp(px: Int) = if (density <= 0f) px else (px / density).roundToInt()
}

/**
 * A0074–A0078: the canvas a card's page gets, the aspect-ratio checks that flag a mismatch back to the agent,
 * and the display summary for GET /v1/device/display. Pure; unit-tested.
 */
object CardViewport {
    /** The aspect of content and canvas may differ this much before the agent is told. */
    const val MISMATCH = 0.10
    /** Standard card chrome outside the page, in dp: toolbar, card bar (status + 48 dp Dismiss + padding), box margins and border. */
    const val STANDARD_TOOLBAR_DP = 56
    const val STANDARD_BAR_DP = 64
    const val STANDARD_MARGIN_DP = 8
    const val STANDARD_BORDER_DP = 1

    /**
     * What a true full-screen card gets in each orientation: the whole display with bars hidden, less the cutout
     * (pages are kept off the camera cutout; the cutout band is black).
     */
    fun fullScreen(g: DisplayGeometry): Pair<Px, Px> {
        val long = (g.longPx - g.cutoutLongPx).coerceAtLeast(1)
        return Px(g.shortPx, long) to Px(long, g.shortPx)
    }

    /** The full-screen canvas in the current rotation. */
    fun fullScreenNow(g: DisplayGeometry): Px = fullScreen(g).let { if (g.portrait) it.first else it.second }

    /**
     * The standard card frame (the bordered box under the toolbar, above Bridgit's strip), estimated from the window
     * when the card screen hasn't been measured in this orientation yet.
     */
    fun standardEstimate(g: DisplayGeometry): Px {
        fun dp(n: Int) = (n * g.density).roundToInt()
        val w = g.widthPx - 2 * dp(STANDARD_MARGIN_DP) - 2 * dp(STANDARD_BORDER_DP)
        val h = g.heightPx - g.statusBarPx - g.navBarPx - dp(STANDARD_TOOLBAR_DP) - dp(STANDARD_BAR_DP) - dp(STANDARD_MARGIN_DP) - 2 * dp(STANDARD_BORDER_DP)
        return Px(w.coerceAtLeast(1), h.coerceAtLeast(1))
    }

    /** `{width_px, height_px, width_dp, height_dp, density}` for the post response's display block. */
    fun viewportJson(px: Px, density: Float): JSONObject = px.json()
        .put("width_dp", if (density > 0f) (px.w / density).roundToInt() else px.w)
        .put("height_dp", if (density > 0f) (px.h / density).roundToInt() else px.h)
        .put("density", density.toDouble())

    /**
     * A small "long:short" ratio, e.g. 20:9 for 1344x2992 and 16:9 for 1280x720: the smallest short side up to 32 whose
     * ratio is within 0.5 %; else the exact reduced ratio.
     */
    fun aspectLabel(w: Int, h: Int): String {
        if (w <= 0 || h <= 0) return "0:0"
        val long = max(w, h); val short = min(w, h)
        val r = long.toDouble() / short
        for (b in 1..32) {
            val a = (r * b).roundToInt()
            if (abs(a.toDouble() / b - r) / r < 0.005) return "$a:$b"
        }
        val g = gcd(long, short)
        return "${long / g}:${short / g}"
    }

    private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

    private val ASPECT = Regex("^\\s*(\\d{1,5}(?:\\.\\d{1,3})?)\\s*[:x×/]\\s*(\\d{1,5}(?:\\.\\d{1,3})?)\\s*$", RegexOption.IGNORE_CASE)

    /** `content_aspect`: "16:9", "1280x720" or "4/3" as width:height; null when it isn't one. */
    fun parseAspect(text: String?): Double? {
        val m = ASPECT.matchEntire(text ?: return null) ?: return null
        val w = m.groupValues[1].toDouble(); val h = m.groupValues[2].toDouble()
        if (w <= 0.0 || h <= 0.0) return null
        return (w / h).takeIf { it in 0.05..20.0 }
    }

    private val META = Regex("<meta\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val NAME_VIEWPORT = Regex("name\\s*=\\s*[\"']?viewport\\b", RegexOption.IGNORE_CASE)
    private val CONTENT = Regex("content\\s*=\\s*(\"([^\"]*)\"|'([^']*)')", RegexOption.IGNORE_CASE)

    /** The `content` of the page's own `<meta name="viewport">`, or null without one. */
    fun viewportMeta(html: String): String? = META.findAll(html).map { it.value }.lastOrNull { NAME_VIEWPORT.containsMatchIn(it) }
        ?.let { tag -> CONTENT.find(tag)?.let { it.groupValues[2].ifEmpty { it.groupValues[3] } } }

    /** True when the page lays itself out to the screen (`width=device-width` in its viewport meta). */
    fun responsive(html: String): Boolean = viewportMeta(html)?.lowercase()?.replace(" ", "")?.contains("width=device-width") == true

    /** A fixed page size from the page's viewport meta (`width=1280, height=720`), best effort. */
    fun declaredSize(html: String): Px? {
        val c = viewportMeta(html)?.lowercase() ?: return null
        fun num(k: String) = Regex("(?:^|[,;\\s])$k\\s*=\\s*(\\d{2,5})").find(c)?.groupValues?.get(1)?.toIntOrNull()
        val w = num("width") ?: return null
        val h = num("height") ?: return null
        return Px(w, h).takeIf { it.w > 0 && it.h > 0 }
    }

    /** True when [content] (width/height) and [viewport] differ by more than [MISMATCH]. */
    fun mismatched(content: Double, viewport: Px): Boolean {
        val v = viewport.ratio
        if (content <= 0.0 || v <= 0.0) return false
        return abs(content - v) / v > MISMATCH
    }

    /** `display.aspect_mismatch` for the post response, or null when the aspects agree (or the content declares none). */
    fun mismatchJson(content: Double?, contentLabel: String?, viewport: Px, presentation: Presentation): JSONObject? {
        if (content == null || !mismatched(content, viewport)) return null
        val vLabel = "${viewport.w}x${viewport.h} (${orientedLabel(viewport.w, viewport.h)})"
        val advice = when {
            presentation == Presentation.FULL_SCREEN -> "Shown whole, scaled to fit with black bars. To fill the screen, compose for ${viewport.w}x${viewport.h} px or lay the page out with width=device-width."
            else -> "Standard cards fit the page to the card's width. To fill it, lay the page out with width=device-width, or post with presentation \"full_screen\"."
        }
        return JSONObject().put("content", contentLabel ?: "%.3f".format(java.util.Locale.ROOT, content))
            .put("viewport", vLabel).put("advice", advice)
    }

    /** "16:9" for landscape, "9:16" for portrait, so the label says which way round. */
    fun orientedLabel(w: Int, h: Int): String = aspectLabel(w, h).let { l -> if (h > w) l.split(":").let { "${it[1]}:${it[0]}" } else l }

    /** GET /v1/device/display. [standard] is the measured card frame in the current orientation, if known. */
    fun displayJson(g: DisplayGeometry, standard: Px?): JSONObject {
        val (p, l) = fullScreen(g)
        return JSONObject().put("width_px", g.widthPx).put("height_px", g.heightPx).put("density_dpi", g.densityDpi)
            .put("density", g.density.toDouble()).put("aspect_ratio", aspectLabel(g.widthPx, g.heightPx))
            .put("rotation", g.rotation).put("orientation", if (g.portrait) "portrait" else "landscape")
            .put("full_screen_card", JSONObject().put("portrait", p.json()).put("landscape", l.json()))
            .put("standard_card", (standard ?: standardEstimate(g)).json().put("measured", standard != null))
            .apply { g.refreshHz?.let { put("refresh_hz", it) } }
    }

    /** The short `display` block in GET /v1/health. */
    fun healthJson(g: DisplayGeometry): JSONObject {
        val (p, l) = fullScreen(g)
        return JSONObject().put("width_px", g.widthPx).put("height_px", g.heightPx).put("aspect_ratio", aspectLabel(g.widthPx, g.heightPx))
            .put("orientation", if (g.portrait) "portrait" else "landscape")
            .put("full_screen_card", JSONObject().put("portrait", p.json()).put("landscape", l.json()))
            .put("details", "/v1/device/display")
    }
}
