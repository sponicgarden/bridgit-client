package cc.bridgit.app.ui.common

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.format.DateUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.AttrRes
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import cc.bridgit.app.R
import cc.bridgit.app.core.AgentRegistry
import cc.bridgit.app.databinding.RowLogEntryBinding
import cc.bridgit.app.log.LogEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** App version beside the outlined wordmark on Home. */
fun brandVersion(ctx: Context): String = runCatching {
    ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName?.substringBefore(' ') ?: ""
}.getOrDefault("")

/** Theme colour lookup. */
fun Context.themeColor(@AttrRes attr: Int): Int {
    val tv = TypedValue()
    theme.resolveAttribute(attr, tv, true)
    return tv.data
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

object Fmt {
    /** Compact age with "ago": "0m ago", "5m ago", "3h ago", "2d ago", then "2 Nov". A no-break space keeps "ago" on its line. */
    fun ago(ts: Long, now: Long = System.currentTimeMillis()): String {
        if (ts <= 0) return "never"
        val m = (now - ts).coerceAtLeast(0) / 60_000
        return when {
            m < 60 -> "${m}m\u00A0ago"
            m < 24 * 60 -> "${m / 60}h\u00A0ago"
            m < 7 * 24 * 60 -> "${m / (24 * 60)}d\u00A0ago"
            else -> SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(ts))
        }
    }
    /** Shortest age for list columns: "now", "5m", "3h", "2d", then "2 Nov". */
    fun agoShort(ts: Long, now: Long = System.currentTimeMillis()): String {
        if (ts <= 0) return "–"
        val m = (now - ts).coerceAtLeast(0) / 60_000
        return when {
            m < 1 -> "now"
            m < 60 -> "${m}m"
            m < 24 * 60 -> "${m / 60}h"
            m < 7 * 24 * 60 -> "${m / (24 * 60)}d"
            else -> SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(ts))
        }
    }
    fun time(ts: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))
    fun dateTime(ts: Long): String = SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(ts))
    fun day(ts: Long): String = when {
        DateUtils.isToday(ts) -> "Today"
        DateUtils.isToday(ts + DateUtils.DAY_IN_MILLIS) -> "Yesterday"
        else -> SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(ts))
    }
    fun bytes(n: Long?): String = when {
        n == null -> ""
        n < 1024 -> "$n B"
        n < 1024 * 1024 -> "${n / 1024} KB"
        else -> String.format(Locale.US, "%.1f MB", n / 1048576.0)
    }
    fun duration(ms: Long?): String = when {
        ms == null -> ""
        ms < 1000 -> String.format(Locale.US, "%.2f s", ms / 1000.0)
        else -> String.format(Locale.US, "%.1f s", ms / 1000.0)
    }
    fun uptime(ms: Long): String {
        val m = ms / 60_000
        return if (m < 60) "Up ${m}m" else "Up ${m / 60}h ${m % 60}m"
    }
}

/**
 * Plain sentences for log entries (UI-06, ui-spec 5.7): "Grok fetched the last
 * screenshot", "Claude couldn't use the camera". Technical detail goes in chips.
 */
object Sentences {
    fun of(e: LogEntry, agentName: String?): String {
        val who = agentName ?: e.agent
        if (e.kind == "request" && who != null) {
            val ok = (e.http ?: 0) < 400
            val ep = e.endpoint ?: ""
            val type = e.params?.optString("type", "") ?: ""
            val waits = ActivityRows.waitSeconds(e) != null
            val iid = ActivityRows.interactionId(e)
            val what = when {
                // Interactions (#0004): say what happened to which question, not the route template.
                ep.endsWith("/interactions") && e.method == "POST" -> if (ok) {
                    val old = e.agent
                    return if (old != null && e.message.startsWith("$old ")) who + e.message.removePrefix(old) else "$who sent you a question"
                } else return "$who couldn't send you a question"
                iid != null && e.method == "GET" -> if (ok) return if (waits) "$who waited for your answer to question $iid"
                    else "$who checked question $iid" else "question $iid"
                iid != null && e.method == "DELETE" -> if (ok) return "$who cancelled question $iid" else "question $iid"
                ep.endsWith("/interactions") -> if (ok) return "$who listed its questions" else "its questions"
                ep.endsWith("/events") -> if (ok) return if (waits) "$who listened for your answers" else "$who checked for your answers" else "events"
                ep.endsWith("/image/last") -> when (type) { "screenshot" -> "the last screenshot"; "photo" -> "the last photo"; else -> "the last image" }
                ep.endsWith("/camera/snapshot") -> if (ok) return "$who took a photo" else "the camera"
                ep.endsWith("/camera/arm") -> if (ok) return "$who got the camera ready for a photo" else "the camera"
                ep.endsWith("/screen/capture") -> if (ok) return "$who took a screenshot" else "a screenshot"
                ep.endsWith("/mic/record") -> if (ok) return "$who recorded a clip" else "the microphone"
                ep.contains("/gallery/") -> "a gallery photo"
                ep.endsWith("/health") -> if (ok) return "$who checked in" else "the health check"
                ep.endsWith("/guide") -> if (ok) return "$who read the guide" else "the guide"
                ep.endsWith("/capabilities") -> if (ok) return "$who checked what's on" else "the capability list"
                ep.startsWith("/v1/log") || ep.endsWith("/audit") -> if (ok) return "$who read the log" else "the log"
                ep.endsWith("/pair") -> return if (ok) "$who fetched its key with a code" else "Pairing code failed"
                else -> ActivityRows.endpoint(e) ?: ep
            }
            return if (ok) "$who fetched $what" else "$who couldn't use $what"
        }
        if (e.kind == "request" && who == null) return when (e.result) {
            "unauthorized" -> "A call with a wrong or old key was refused"
            "rate_limited" -> "Calls from ${e.sourceIp ?: "an address"} were held back after wrong keys"
            "forbidden_source" -> "A call from outside Tailscale was refused"
            "not_found" -> "A call to an unknown address was refused"
            else -> e.message
        }
        if (e.kind == "note" && who != null) return "$who wrote a note"
        return e.message
    }

    /** Second line for errors and notes (the reason, or the note text). */
    fun detail(e: LogEntry): String? = when {
        e.kind == "note" -> "\"${e.message}\""
        (e.http ?: 0) >= 400 -> reason(e.result)
        else -> null
    }

    fun reason(code: String?): String? = when (code) {
        "capability_disabled" -> "It's switched off in Feature enablement."
        "permission_denied" -> "Android permission missing. See Access."
        "notifications_required" -> "Bridgit's notifications are off."
        "service_start_blocked" -> "Open Bridgit once to enable camera and mic."
        "device_locked" -> "The phone was locked."
        "unauthorized" -> "Wrong or old key."
        "rate_limited" -> "Too many requests."
        "sensor_busy" -> "Another capture was running."
        null, "ok" -> null
        else -> code
    }
}

/**
 * Agent avatar (UI-12): first letter on a tinted circle, colour picked by
 * hashing the name into 8 fixed pairs (ui-spec 2.1). Gear-less grey circle for Bridgit.
 */
/** The app an agent runs on, for its icon: the mark, its tile colour and the mark's colour. */
enum class AgentBrand(val mark: Int, val tile: Int, val ink: Int) {
    CLAUDE(R.drawable.agent_brand_claude, 0xFFD97757.toInt(), 0xFFFFFFFF.toInt()),
    OPENAI(R.drawable.agent_brand_openai, 0xFF101010.toInt(), 0xFFFFFFFF.toInt()),
    GEMINI(R.drawable.agent_brand_gemini, 0xFFFFFFFF.toInt(), 0xFF3186FF.toInt()),
    GROK(R.drawable.agent_brand_grok, 0xFF101010.toInt(), 0xFFFFFFFF.toInt()),
    CURSOR(R.drawable.agent_brand_cursor, 0xFF101010.toInt(), 0xFFFFFFFF.toInt());

    companion object {
        private val O_MODEL = Regex("o[1-9]")

        /** First field that names a known app wins: model, then harness, then the agent's name. Whole words only. */
        fun of(model: String?, harness: String?, name: String?): AgentBrand? {
            for (field in listOf(model, harness, name)) {
                val words = field?.lowercase()?.split(Regex("[^a-z0-9.]+"))?.filter { it.isNotEmpty() } ?: continue
                for (w in words) when {
                    w == "claude" || w == "anthropic" || w.startsWith("claude-") -> return CLAUDE
                    w.startsWith("gpt") || w == "chatgpt" || w == "codex" || w == "openai" || O_MODEL.matches(w) -> return OPENAI
                    w.startsWith("gemini") -> return GEMINI
                    w == "grok" || w == "xai" || w == "x.ai" || w.startsWith("grok-") -> return GROK
                    w == "cursor" -> return CURSOR
                }
            }
            return null
        }
    }
}

/**
 * An agent's icon (design pass, 4 Nov 2026): a rounded app-icon square with the app it runs on (Claude, OpenAI,
 * Gemini, Grok, Cursor; from the model and harness it reported, then its name), else the icon it uploaded, else a
 * neutral agent glyph. Never a bare letter. setAgent(null) is Bridgit itself.
 */
class AgentAvatarView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private val tile = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect = android.graphics.RectF()
    private val clip = android.graphics.Path()
    private var mark: android.graphics.drawable.Drawable? = null
    private var markScale = 0.6f
    private var picture: android.graphics.Bitmap? = null

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun setAgent(name: String?) {
        contentDescription = name ?: "Bridgit"
        picture = null; mark = null
        val ctx = context
        if (name == null) {
            picture = bridgitIcon(ctx)
        } else {
            val agent = runCatching { (ctx.applicationContext as cc.bridgit.app.BridgeApp).agents.byName(name) }.getOrNull()
            val brand = AgentBrand.of(agent?.model, agent?.harness, name)
            if (brand != null) {
                tile.color = brand.tile
                mark = androidx.core.content.ContextCompat.getDrawable(ctx, brand.mark)?.mutate()?.apply { setTint(brand.ink) }
                markScale = 0.6f
            } else {
                picture = agent?.id?.let { uploadedIcon(ctx, it) }
                if (picture == null) {
                    tile.color = ctx.themeColor(com.google.android.material.R.attr.colorSurfaceContainerHighest)
                    mark = androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.ic_nav_agents)?.mutate()
                        ?.apply { setTint(ctx.themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)) }
                    markScale = 0.55f
                }
            }
        }
        edge.color = ctx.themeColor(com.google.android.material.R.attr.colorOutlineVariant)
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val side = minOf(width, height).toFloat()
        val left = (width - side) / 2f; val top = (height - side) / 2f
        rect.set(left, top, left + side, top + side)
        val r = side * 0.22f // the app icon's corner ratio (ShapeAppearance.Bridgit.AppIcon)
        clip.reset(); clip.addRoundRect(rect, r, r, android.graphics.Path.Direction.CW)
        val pic = picture
        if (pic != null) {
            c.save(); c.clipPath(clip); c.drawBitmap(pic, null, rect, bmpPaint); c.restore()
        } else {
            c.drawRoundRect(rect, r, r, tile)
            mark?.let { d ->
                val m = side * markScale
                val l = (left + (side - m) / 2f).toInt(); val t = (top + (side - m) / 2f).toInt()
                d.setBounds(l, t, l + m.toInt(), t + m.toInt()); d.draw(c)
            }
        }
        // A hairline keeps black and white tiles visible on both themes.
        edge.strokeWidth = resources.displayMetrics.density
        rect.inset(edge.strokeWidth / 2, edge.strokeWidth / 2)
        c.drawRoundRect(rect, r, r, edge)
    }

    companion object {
        private val cache = android.util.LruCache<String, android.graphics.Bitmap>(16)
        private var bridgit: android.graphics.Bitmap? = null

        private fun bridgitIcon(ctx: Context) = bridgit ?: android.graphics.BitmapFactory.decodeResource(ctx.resources, R.drawable.brand_logo_square)
            ?.also { bridgit = it }

        /** The icon the agent uploaded (POST /v1/me branding), decoded once. */
        private fun uploadedIcon(ctx: Context, id: String): android.graphics.Bitmap? {
            val b64 = ctx.getSharedPreferences("platform_branding", Context.MODE_PRIVATE).getString("icon:$id", null) ?: return null
            val key = "$id:${b64.hashCode()}"
            cache.get(key)?.let { return it }
            return cc.bridgit.app.AgentBranding.bitmap(b64)?.also { cache.put(key, it) }
        }
    }
}

/**
 * One activity row (UI-17), used by Activity and Agent detail: time, avatar,
 * sentence, optional reason, and mono chips; error rows get a stripe and say "Error:" to TalkBack.
 */
class LogRowAdapter(private val agents: AgentRegistry, private val onClick: (LogEntry) -> Unit) :
    ListAdapter<LogEntry, LogRowAdapter.Holder>(DIFF) {

    class Holder(val b: RowLogEntryBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(RowLogEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(h: Holder, position: Int) {
        val e = getItem(position)
        val name = e.agentId?.let { agents.get(it)?.name } ?: e.agent
        val sentence = Sentences.of(e, name)
        val detail = Sentences.detail(e)
        h.b.avatar.setAgent(name)
        h.b.time.text = Fmt.time(e.ts)
        h.b.sentence.text = sentence
        h.b.detail.isVisible = detail != null
        h.b.detail.text = detail
        val chips = listOfNotNull(
            ActivityRows.endpoint(e)?.let { "${e.method ?: ""} $it".trim() },
            e.http?.let { if (it >= 400) "$it ${e.result}" else "$it" },
            e.bytesOut?.takeIf { it > 0 }?.let { Fmt.bytes(it) },
            // A long-poll's time is the agent waiting for the owner, not a slow call (#0004).
            e.durationMs?.let { if (ActivityRows.isPoll(e)) "waited ${Fmt.duration(it)}" else Fmt.duration(it) },
        )
        bindChip(h.b.chip1, chips.getOrNull(0), false)
        bindChip(h.b.chip2, chips.getOrNull(1), e.isError)
        bindChip(h.b.chip3, chips.getOrNull(2), false)
        bindChip(h.b.chip4, chips.getOrNull(3), false)
        h.b.errorStripe.isVisible = e.isError
        h.b.root.contentDescription = (if (e.isError) "Error: " else "") + "${Fmt.time(e.ts)}, $sentence" +
            (detail?.let { ", $it" } ?: "") + (if (chips.isNotEmpty()) ", " + chips.joinToString(", ") else "")
        h.b.root.setOnClickListener { onClick(e) }
    }

    private fun bindChip(v: TextView, text: String?, error: Boolean) {
        v.isVisible = text != null
        v.text = text
        val ctx = v.context
        if (error) {
            v.setBackgroundResource(R.drawable.bg_mono_chip_error)
            v.setTextColor(ctx.themeColor(com.google.android.material.R.attr.colorOnErrorContainer))
        } else {
            v.setBackgroundResource(R.drawable.bg_mono_chip)
            v.setTextColor(ctx.themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
        }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<LogEntry>() {
            override fun areItemsTheSame(a: LogEntry, b: LogEntry) = a.id == b.id
            override fun areContentsTheSame(a: LogEntry, b: LogEntry) = a == b
        }
    }
}

/** What an agent card shows under the name: the model the agent reported (POST /v1/me), not the network path. */
fun cc.bridgit.app.core.Agent.modelText(ctx: Context): String =
    modelLabel ?: ctx.getString(cc.bridgit.app.R.string.agents_model_unknown)
