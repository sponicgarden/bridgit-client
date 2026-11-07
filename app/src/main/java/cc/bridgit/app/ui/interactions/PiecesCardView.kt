package cc.bridgit.app.ui.interactions

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatDialog
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import cc.bridgit.app.applets.AppletTableStore
import cc.bridgit.app.interact.CardActionRunner
import cc.bridgit.app.interact.CardPieces
import cc.bridgit.app.interact.Interaction
import cc.bridgit.app.interact.TablePieces
import cc.bridgit.app.ui.common.dp
import cc.bridgit.app.ui.common.themeColor
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import com.google.android.material.R as M

/**
 * Draws a pieces card (docs/applet-cards.md) with Bridgit's own views: no WebView and no agent script. Sources are
 * read when the card is attached and kept fresh only while it is on screen ([PieceSources.liveEveryMs]); each
 * piece is rebuilt only when what it shows changed, so a draft being typed is never reset.
 */
@SuppressLint("ViewConstructor")
class PiecesCardView(context: Context, private val app: BridgeApp, private val card: Interaction) : LinearLayout(context) {
    private val src = PieceSources(app, card)
    private val json = src.json
    private val pieces = json.optJSONArray("pieces") ?: JSONArray()
    private val loaded = java.util.concurrent.ConcurrentHashMap<String, Any>()
    private val nulls = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
    private val errors = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val drafts = HashMap<String, String>()
    private val dirtyDrafts = HashSet<String>()
    internal var scope: CoroutineScope? = null
    private var busy = false
    /** Whether the applet is paused, read on IO with the sources (never on the main thread in draw). */
    @Volatile private var pausedNow = false
    private var resultLine: String? = null
    /** The buttons piece whose button ran last: its result line shows under it only. */
    private var resultPiece: JSONObject? = null
    private val slots = ArrayList<FrameLayout>()
    private val keys = arrayOfNulls<String>(pieces.length() + 1)
    private val stateSlot = FrameLayout(context)
    private val tablePieces = TablePieceView(this, app, card.agentId) { p -> (0 until pieces.length()).firstOrNull { pieces.optJSONObject(it) === p }?.let { keys[it] = null }; draw() }
    private val tableListener: (String) -> Unit = { a -> if (a == card.agentId) scope?.launch { readKinds(setOf("table", "photos")) } }
    /** The newest batch of each batch button (by id or label), for "Reading 3 of 12…" on the button. */
    private val batchState = java.util.concurrent.ConcurrentHashMap<String, JSONObject>()
    private val batchListener: (String) -> Unit = { a -> if (a == card.agentId) scope?.launch { readBatches(); readKinds(setOf("batch")) } }

    init {
        orientation = VERTICAL
        setPadding(0, context.dp(4), 0, context.dp(4))
        for (i in 0 until pieces.length()) {
            val f = FrameLayout(context)
            slots += f
            // The state strip goes under the header (or first, when the card has no header).
            addView(f, LayoutParams(-1, -2).apply { topMargin = context.dp(if (i == 0) 0 else 10) })
            if (i == 0 && pieces.optJSONObject(0)?.optString("type") == "header") addView(stateSlot, LayoutParams(-1, -2).apply { topMargin = context.dp(8) })
        }
        if (stateSlot.parent == null) addView(stateSlot, 0, LayoutParams(-1, -2))
        draw()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main); scope = s
        AppletTableStore.of(app).addListener(tableListener)
        app.platform.batches.addListener(batchListener)
        s.launch { readBatches(); readAll() }
        // Live sources: watch counts and runs every 10 s, calls only when they ask (every_s), while on screen.
        for (name in src.sources.keys()) src.liveEveryMs(name)?.let { every ->
            s.launch { while (isActive) { delay(every); readOne(name); pausedNow = withContext(Dispatchers.IO) { src.paused() }; draw() } }
        }
        s.launch { while (isActive) { delay(15_000); draw() } } // stale/ago words move on
    }

    override fun onDetachedFromWindow() {
        AppletTableStore.of(app).removeListener(tableListener)
        app.platform.batches.removeListener(batchListener)
        scope?.cancel(); scope = null
        super.onDetachedFromWindow()
    }

    private suspend fun readAll() { pausedNow = withContext(Dispatchers.IO) { src.paused() }; for (n in src.sources.keys().asSequence().toList()) readOne(n); draw() }

    private fun batchKey(b: JSONObject) = b.optString("id").ifEmpty { b.optString("label") }

    private suspend fun readBatches() {
        val keys = (0 until pieces.length()).mapNotNull { pieces.optJSONObject(it) }.filter { it.optString("type") == "buttons" }
            .flatMap { p -> p.optJSONArray("items")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } } ?: emptyList() }
            .filter { it.optJSONObject("do")?.has("for_each") == true }.map(::batchKey)
        if (keys.isEmpty()) return
        val read = withContext(Dispatchers.IO) { keys.associateWith { app.platform.batches.summary(card.agentId, it) } }
        batchState.putAll(read)
    }

    private suspend fun readKinds(kinds: Set<String>) {
        for (n in src.sources.keys().asSequence().toList()) if (kinds.any { src.sources.optJSONObject(n)?.has(it) == true }) readOne(n)
        draw()
    }

    private suspend fun readOne(name: String) {
        if (src.sources.optJSONObject(name)?.has("reply") == true) { src.replies[name]?.let { loaded[name] = it }; return }
        try {
            val v = withContext(Dispatchers.IO) { src.read(name) }
            if (v == null) { loaded.remove(name); nulls += name } else { loaded[name] = v; nulls -= name }
            errors.remove(name)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { errors[name] = e.message ?: "This couldn't be read." }
    }

    private fun values(): CardPieces.Values = CardPieces.Values(HashMap<String, Any?>(loaded).apply { nulls.forEach { put(it, null) } }, HashMap(drafts))

    /** Rebuilds the pieces whose content changed. */
    private fun draw() {
        val v = values()
        val paused = pausedNow
        val readMap: Map<String, Any?> = HashMap<String, Any?>(loaded).apply { nulls.forEach { put(it, null) } }
        val state = CardPieces.state(json, readMap, errors, paused)
        val stateKey = state.id + "|" + state.words
        if (keys[pieces.length()] != stateKey) {
            keys[pieces.length()] = stateKey
            stateSlot.removeAllViews()
            if (state.id != "ready") stateSlot.addView(strip(state))
        }
        for (i in 0 until pieces.length()) {
            val p = pieces.optJSONObject(i) ?: continue
            val shown = p.optJSONObject("only_if")?.let { CardPieces.holds(it, v) } ?: true
            val key = if (!shown) "hidden" else pieceKey(p, v, paused)
            if (keys[i] == key) continue
            if (p.optString("type") == "draft" && p.optString("id") in dirtyDrafts && keys[i] != null && shown) continue
            if (p.optString("type") == "table" && tablePieces.editing(p) && keys[i] != null && shown) continue
            keys[i] = key
            slots[i].removeAllViews()
            slots[i].visibility = if (shown) View.VISIBLE else View.GONE
            if (shown) runCatching { piece(p, v, paused) }.getOrNull()?.let { slots[i].addView(it) }
        }
    }

    /** What a piece shows, as text: it is redrawn only when this changes. */
    private fun pieceKey(p: JSONObject, v: CardPieces.Values, paused: Boolean): String {
        val refs = StringBuilder(p.toString())
        fun walk(x: Any?) { when (x) {
            is JSONObject -> if (x.length() == 1 && (x.has("\$source") || x.has("\$draft"))) refs.append('|').append(CardPieces.resolve(x, v)?.toString()) else x.keys().forEach { walk(x.opt(it)) }
            is JSONArray -> for (j in 0 until x.length()) walk(x.opt(j))
        } }
        walk(p)
        if (p.optString("type") in setOf("metrics", "timeline", "table")) refs.append('|').append(System.currentTimeMillis() / 60_000)
        if (p.optString("type") == "table") refs.append(tablePieces.key(p))
        if (p.optString("type") == "buttons") refs.append('|').append(busy).append(paused).append(resultLine).append(batchState.toString())
        return refs.toString()
    }

    // ---- pieces ---------------------------------------------------------------------------------------------

    internal fun text(s: CharSequence, style: Int, color: Int, bold: Boolean = false) = TextView(context).apply {
        text = s
        setTextAppearance(style)
        setTextColor(context.themeColor(color))
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    internal fun t(v: Any?, values: CardPieces.Values) = CardPieces.textOf(v, values)

    internal fun col(): LinearLayout = LinearLayout(context).apply { orientation = VERTICAL }

    private fun panel(view: View, colorAttr: Int = M.attr.colorSurfaceContainerHigh, radius: Int = 12): View = view.apply {
        background = GradientDrawable().apply { cornerRadius = context.dp(radius).toFloat(); setColor(context.themeColor(colorAttr)) }
        setPadding(context.dp(12), context.dp(10), context.dp(12), context.dp(10))
    }

    internal fun emptyLine(p: JSONObject, v: CardPieces.Values, default: String) =
        text(t(p.opt("empty"), v).ifBlank { default }, cc.bridgit.app.R.style.TextAppearance_Bridgit_BodyMedium, M.attr.colorOnSurfaceVariant)

    private fun piece(p: JSONObject, v: CardPieces.Values, paused: Boolean): View? = when (p.optString("type")) {
        "header" -> col().apply {
            t(p.opt("eyebrow"), v).takeIf { it.isNotBlank() }?.let { addView(text(it.uppercase(), M.style.TextAppearance_Material3_LabelMedium, M.attr.colorPrimary, true)) }
            addView(text(t(p.opt("title"), v).ifBlank { card.title }, M.style.TextAppearance_Material3_HeadlineSmall, M.attr.colorOnSurface).apply { isFocusable = true; androidx.core.view.ViewCompat.setAccessibilityHeading(this, true) })
            t(p.opt("text"), v).takeIf { it.isNotBlank() }?.let { addView(text(it, cc.bridgit.app.R.style.TextAppearance_Bridgit_BodyMedium, M.attr.colorOnSurfaceVariant).apply { setPadding(0, context.dp(4), 0, 0) }) }
        }
        "status" -> pill(t(p.opt("text"), v), p.optString("tone", "info"))
        "text" -> text(t(p.opt("text"), v), M.style.TextAppearance_Material3_BodyLarge, M.attr.colorOnSurface)
        "note" -> text(t(p.opt("text"), v), cc.bridgit.app.R.style.TextAppearance_Bridgit_BodySmall, M.attr.colorOnSurfaceVariant)
        "metrics" -> metrics(p, v)
        "table" -> table(p, v)
        "timeline" -> timeline(p, v)
        "people" -> list(p, v, p.optString("name"), p.optString("status").ifEmpty { null }, p.optString("time").ifEmpty { null }, badge = true, empty = "Nobody yet.")
        "devices" -> devices(p, v)
        "gallery" -> gallery(p, v)
        "video" -> video(p, v)
        "draft" -> draft(p, v)
        "buttons" -> buttons(p, v, paused)
        else -> null
    }

    private fun pill(words: String, tone: String) = LinearLayout(context).apply {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        val (bg, fg) = when (tone) {
            "ok" -> R.attr.colorSuccessContainer to M.attr.colorOnSurface
            "warn" -> M.attr.colorTertiaryContainer to M.attr.colorOnTertiaryContainer
            "bad" -> M.attr.colorErrorContainer to M.attr.colorOnErrorContainer
            else -> M.attr.colorSecondaryContainer to M.attr.colorOnSecondaryContainer
        }
        addView(panel(text(words, cc.bridgit.app.R.style.TextAppearance_Bridgit_Label, fg), bg, 99).apply { setPadding(context.dp(12), context.dp(6), context.dp(12), context.dp(6)) })
    }

    private fun strip(s: CardPieces.State): View = when (s.id) {
        "loading" -> LinearLayout(context).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(ProgressBar(context).apply { isIndeterminate = true }, LayoutParams(context.dp(18), context.dp(18)))
            addView(text(s.words ?: "", cc.bridgit.app.R.style.TextAppearance_Bridgit_BodyMedium, M.attr.colorOnSurfaceVariant).apply { setPadding(context.dp(8), 0, 0, 0) })
        }
        else -> pill(s.words ?: s.id, when (s.id) { "error" -> "bad"; "stale", "paused" -> "warn"; else -> "info" })
    }

    private fun metrics(p: JSONObject, v: CardPieces.Values): View {
        val items = p.optJSONArray("items") ?: JSONArray()
        val grid = GridLayout(context).apply { columnCount = 2; useDefaultMargins = false }
        for (i in 0 until items.length()) {
            val m = items.getJSONObject(i)
            val opts = JSONObject().apply { m.opt("currency")?.let { put("currency", CardPieces.resolve(it, v)) }; m.opt("words")?.let { put("words", it) } }
            val value = CardPieces.format(CardPieces.resolve(m.opt("value"), v), m.optString("format").ifEmpty { null }, opts)
                ?.let { s -> m.optString("unit").takeIf { it.isNotEmpty() }?.let { "$s $it" } ?: s } ?: t(m.opt("empty"), v).ifBlank { "—" }
            val label = t(m.opt("label"), v)
            val tile = col().apply {
                addView(text(value, M.style.TextAppearance_Material3_TitleLarge, M.attr.colorOnSurface, true))
                addView(text(label, M.style.TextAppearance_Material3_LabelMedium, M.attr.colorOnSurfaceVariant))
                contentDescription = "$label: $value"; isFocusable = true
            }
            grid.addView(panel(tile, M.attr.colorPrimaryContainer.takeIf { false } ?: M.attr.colorSurfaceContainerHigh), GridLayout.LayoutParams(GridLayout.spec(i / 2), GridLayout.spec(i % 2, 1f)).apply {
                width = 0; setMargins(0, if (i >= 2) context.dp(8) else 0, if (i % 2 == 0) context.dp(8) else 0, 0)
            })
        }
        return grid
    }

    private fun limitOf(p: JSONObject, default: Int) = p.optInt("limit", default)

    internal fun tables() = src.tables()

    private fun table(p: JSONObject, v: CardPieces.Values): View {
        return tablePieces.build(p, v, (p.optJSONObject("rows")?.optString("\$source")?.substringBefore('.'))?.let { src.sources.optJSONObject(it)?.optString("table") }?.takeIf { it.isNotEmpty() })
    }

    internal fun rowView(cells: List<String>, header: Boolean) = LinearLayout(context).apply {
        orientation = HORIZONTAL
        setPadding(0, context.dp(8), 0, context.dp(8))
        cells.forEachIndexed { i, c ->
            addView(text(c, if (header) M.style.TextAppearance_Material3_LabelMedium else cc.bridgit.app.R.style.TextAppearance_Bridgit_BodyMedium,
                if (header) M.attr.colorOnSurfaceVariant else M.attr.colorOnSurface, bold = !header && i == 0).apply {
                gravity = if (i == 0) Gravity.START else Gravity.END
            }, LayoutParams(0, -2, if (i == 0) 1.4f else 1f))
        }
        if (!header) { isClickable = true; isFocusable = true; setBackgroundResource(context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).let { a -> a.getResourceId(0, 0).also { a.recycle() } }) }
    }

    /** One row in full: each value on its own line, and a kept photo as the photo itself, full width. */
    internal fun showRow(row: JSONObject) {
        val box = col().apply { setPadding(context.dp(24), context.dp(8), context.dp(24), 0) }
        val width = resources.displayMetrics.widthPixels - context.dp(96)
        for (k in row.keys().asSequence().filter { it != "\$key" }) {
            val x = row.opt(k)
            val name = k.replace('_', ' ')
            val ref = (x as? String)?.takeIf { cc.bridgit.app.applets.AppletTables.PHOTO_REF.matches(it) }
            if (ref == null) {
                box.addView(text("$name: " + (CardPieces.format(x, if (k == "at") "time" else null) ?: "—"), cc.bridgit.app.R.style.TextAppearance_Bridgit_BodyMedium, M.attr.colorOnSurface)
                    .apply { setPadding(0, context.dp(2), 0, context.dp(2)) })
                continue
            }
            box.addView(text(name, M.style.TextAppearance_Material3_LabelMedium, M.attr.colorOnSurfaceVariant).apply { setPadding(0, context.dp(6), 0, context.dp(4)) })
            val img = ImageView(context).apply {
                adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER; contentDescription = "Photo: $name"
                setOnClickListener { fullScreen(ref, null) }
            }
            box.addView(img, LayoutParams(-1, -2))
            scope?.launch {
                withContext(Dispatchers.IO) { AppletTableStore.of(app).photoFile(card.agentId, ref)?.let { f -> runCatching { decode(f.path, width) }.getOrNull() } }
                    ?.let { img.setImageBitmap(it) } ?: run { box.addView(text("This photo is no longer on the phone.", cc.bridgit.app.R.style.TextAppearance_Bridgit_BodySmall, M.attr.colorOnSurfaceVariant), box.indexOfChild(img)); box.removeView(img) }
            }
        }
        MaterialAlertDialogBuilder(context).setTitle(card.title).setView(android.widget.ScrollView(context).apply { addView(box) })
            .setPositiveButton(android.R.string.ok, null).show()
    }

    internal fun confirmDelete(table: String, rowId: String, deleted: () -> Unit = {}) {
        MaterialAlertDialogBuilder(context).setTitle("Delete this row?").setMessage("It's deleted from the phone. This can't be undone.")
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Delete") { _, _ -> scope?.launch { withContext(Dispatchers.IO) { runCatching { AppletTableStore.of(app).delete(card.agentId, table, rowId) } }; deleted() } }.show()
    }

    private fun timeline(p: JSONObject, v: CardPieces.Values): View {
        val items = CardPieces.items(CardPieces.resolve(p.opt("items"), v))
        val time = p.optString("time"); val label = p.optString("label"); val detail = p.optString("detail").ifEmpty { null }
        val sorted = items.sortedByDescending { CardPieces.epochMs(it.opt(time)) ?: 0L }.take(limitOf(p, 10))
        val box = col()
        if (sorted.isEmpty()) { box.addView(emptyLine(p, v, "Nothing yet.")); return box }
        val words = JSONObject().apply { p.optJSONObject("label_words")?.let { put("words", it) } }
        for (it in sorted) {
            val l = CardPieces.format(it.opt(label), p.optString("label_format").ifEmpty { if (p.has("label_words")) "words" else null }, words) ?: "—"
            val w = CardPieces.format(it.opt(time), p.optString("time_format").ifEmpty { "ago" }) ?: ""
            val d = detail?.let { k -> CardPieces.format(it.opt(k), null) }
            box.addView(LinearLayout(context).apply {
                orientation = HORIZONTAL; setPadding(0, context.dp(6), 0, context.dp(6))
                addView(View(context).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(context.themeColor(M.attr.colorPrimary)) } },
                    LayoutParams(context.dp(8), context.dp(8)).apply { topMargin = context.dp(7); marginEnd = context.dp(10) })
                addView(col().apply {
                    addView(text(l + (d?.let { " · $it" } ?: ""), cc.bridgit.app.R.style.TextAppearance_Bridgit_BodyMedium, M.attr.colorOnSurface))
                    addView(text(w, cc.bridgit.app.R.style.TextAppearance_Bridgit_BodySmall, M.attr.colorOnSurfaceVariant))
                }, LayoutParams(0, -2, 1f))
                contentDescription = "$l, $w" + (d?.let { ", $it" } ?: ""); isFocusable = true
            })
        }
        return box
    }

    private fun list(p: JSONObject, v: CardPieces.Values, nameKey: String, statusKey: String?, timeKey: String?, badge: Boolean, empty: String): View {
        val items = CardPieces.items(CardPieces.resolve(p.opt("items"), v))
        val box = col()
        if (items.isEmpty()) { box.addView(emptyLine(p, v, empty)); return box }
        for (it in items.take(20)) {
            val name = CardPieces.show(it.opt(nameKey)).ifBlank { "—" }
            val status = listOfNotNull(statusKey?.let { k -> CardPieces.show(it.opt(k)).ifBlank { null } }, timeKey?.let { k -> CardPieces.format(it.opt(k), "ago") }).joinToString(" · ")
            box.addView(LinearLayout(context).apply {
                orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, context.dp(6), 0, context.dp(6))
                if (badge) addView(text(name.take(1).uppercase(), cc.bridgit.app.R.style.TextAppearance_Bridgit_Label, M.attr.colorOnPrimaryContainer).apply {
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(context.themeColor(M.attr.colorPrimaryContainer)) }
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LayoutParams(context.dp(32), context.dp(32)).apply { marginEnd = context.dp(10) })
                addView(col().apply {
                    addView(text(name, M.style.TextAppearance_Material3_BodyLarge, M.attr.colorOnSurface))
                    if (status.isNotBlank()) addView(text(status, cc.bridgit.app.R.style.TextAppearance_Bridgit_BodySmall, M.attr.colorOnSurfaceVariant))
                }, LayoutParams(0, -2, 1f))
                contentDescription = name + (if (status.isNotBlank()) ", $status" else ""); isFocusable = true
            })
        }
        return box
    }

    private fun devices(p: JSONObject, v: CardPieces.Values): View {
        val items = CardPieces.items(CardPieces.resolve(p.opt("items"), v))
        val box = col()
        if (items.isEmpty()) { box.addView(emptyLine(p, v, "No devices yet.")); return box }
        for (it in items.take(20)) {
            val name = CardPieces.show(it.opt(p.optString("name"))).ifBlank { "—" }
            val kind = p.optString("kind").ifEmpty { null }?.let { k -> CardPieces.show(it.opt(k)) }.orEmpty()
            val avail = p.optString("availability").ifEmpty { null }?.let { k -> CardPieces.show(it.opt(k)) }.orEmpty()
            val words = when (avail) { "online" -> "Verified online"; "offline" -> "Offline"; "not_checked" -> "Not checked"; "" -> "Availability unknown"; else -> avail.replace('_', ' ').replaceFirstChar { c -> c.uppercase() } }
            val checked = p.optString("checked_at").ifEmpty { null }?.let { k -> CardPieces.format(it.opt(k), "ago") }
            box.addView(LinearLayout(context).apply {
                orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, context.dp(6), 0, context.dp(6))
                addView(View(context).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL
                    setColor(context.themeColor(when (avail) { "online" -> R.attr.colorSuccess; "offline" -> M.attr.colorError; else -> M.attr.colorOutline })) } },
                    LayoutParams(context.dp(10), context.dp(10)).apply { marginEnd = context.dp(12) })
                addView(col().apply {
                    addView(text(name, M.style.TextAppearance_Material3_BodyLarge, M.attr.colorOnSurface))
                    addView(text(listOf(kind, words, checked.orEmpty()).filter { s -> s.isNotBlank() }.joinToString(" · "), cc.bridgit.app.R.style.TextAppearance_Bridgit_BodySmall, M.attr.colorOnSurfaceVariant))
                }, LayoutParams(0, -2, 1f))
                contentDescription = "$name, $words"; isFocusable = true
            })
        }
        return box
    }

    private fun gallery(p: JSONObject, v: CardPieces.Values): View {
        val items = CardPieces.items(CardPieces.resolve(p.opt("photos"), v)).take(limitOf(p, 12))
        if (items.isEmpty()) return emptyLine(p, v, "No photos yet.")
        val grid = GridLayout(context).apply { columnCount = 3 }
        val size = (resources.displayMetrics.widthPixels - context.dp(72)) / 3
        items.forEachIndexed { i, it ->
            val ref = p.optString("photo").ifEmpty { null }?.let { c -> it.optString(c) } ?: it.optString("ref").ifEmpty { it.optString("value") }
            val caption = TablePieces.caption(p, it)
            val img = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = "Photo" + (caption?.let { c -> ", $c" } ?: "")
                background = GradientDrawable().apply { cornerRadius = context.dp(8).toFloat(); setColor(context.themeColor(M.attr.colorSurfaceContainerHigh)) }
                clipToOutline = true
                setOnClickListener { fullScreen(ref, caption) }
            }
            if (caption == null) grid.addView(img, GridLayout.LayoutParams().apply { width = size; height = size; setMargins(0, 0, context.dp(6), context.dp(6)) })
            else grid.addView(col().apply {
                // The caption (status, "3 items") under each thumbnail, not only for screen readers.
                addView(img, LayoutParams(size, size))
                addView(text(caption, M.style.TextAppearance_Material3_LabelSmall, M.attr.colorOnSurfaceVariant).apply {
                    maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(context.dp(2), context.dp(2), 0, 0)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LayoutParams(size, -2))
            }, GridLayout.LayoutParams().apply { width = size; setMargins(0, 0, context.dp(6), context.dp(8)) })
            scope?.launch {
                val bmp = withContext(Dispatchers.IO) { AppletTableStore.of(app).photoFile(card.agentId, ref)?.let { f -> decode(f.path, size) } }
                bmp?.let { b -> img.setImageBitmap(b) }
            }
        }
        return grid
    }

    internal fun decode(path: String, target: Int): android.graphics.Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        var sample = 1
        while (o.outWidth / (sample * 2) >= target && o.outHeight / (sample * 2) >= target) sample *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    internal fun fullScreen(ref: String, caption: String?) {
        val d = AppCompatDialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val img = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER; contentDescription = "Photo" + (caption?.let { ", $it" } ?: ""); setOnClickListener { d.dismiss() } }
        d.setContentView(img, ViewGroup.LayoutParams(-1, -1))
        scope?.launch { withContext(Dispatchers.IO) { AppletTableStore.of(app).photoFile(card.agentId, ref)?.let { BitmapFactory.decodeFile(it.path) } }?.let { img.setImageBitmap(it) } }
        d.show()
    }

    /**
     * A video by gallery id (a number, or an object with gallery_id / id): plays only when tapped, muted at first.
     * Only when the card's agent holds the gallery video permission (gallery_video, which reads MediaStore videos)
     * and that feature is on: a card can't play a phone video its agent couldn't read itself.
     */
    private fun video(p: JSONObject, v: CardPieces.Values): View {
        val ref = CardPieces.resolve(p.opt("video"), v)
        val id = when (ref) { is Number -> ref.toLong(); is JSONObject -> ref.optLong("gallery_id", ref.optLong("id", -1)); is String -> ref.toLongOrNull() ?: -1; else -> -1 }
        if (id <= 0 || !mayPlayGalleryVideo()) return emptyLine(p, v, "No video yet.")
        val caption = t(p.opt("caption"), v)
        return MaterialButton(context, null, M.attr.materialButtonOutlinedStyle).apply {
            text = if (caption.isNotBlank()) "Play: $caption" else "Play video"
            setOnClickListener {
                val uri = android.content.ContentUris.withAppendedId(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                val d = AppCompatDialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
                val vv = android.widget.VideoView(context)
                d.setContentView(FrameLayout(context).apply { addView(vv, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER)) }, ViewGroup.LayoutParams(-1, -1))
                vv.setMediaController(android.widget.MediaController(context).also { it.setAnchorView(vv) })
                vv.setOnPreparedListener { mp -> runCatching { mp.setVolume(0f, 0f) }; vv.start() }
                vv.setVideoURI(uri)
                d.show()
            }
        }
    }

    private fun mayPlayGalleryVideo(): Boolean =
        app.agents.get(card.agentId)?.allows(GALLERY_VIDEO) == true && app.registry.byId(GALLERY_VIDEO)?.enabled == true

    private fun draft(p: JSONObject, v: CardPieces.Values): View {
        val id = p.optString("id")
        val prefill = CardPieces.show(CardPieces.resolve(p.opt("value"), v))
        if (id !in dirtyDrafts) drafts[id] = prefill.take(p.optInt("max", 2000))
        return col().apply {
            t(p.opt("label"), v).takeIf { it.isNotBlank() }?.let { addView(text(it, cc.bridgit.app.R.style.TextAppearance_Bridgit_Label, M.attr.colorOnSurfaceVariant)) }
            addView(EditText(context).apply {
                setText(drafts[id]); minLines = 3; gravity = Gravity.TOP or Gravity.START
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                filters = arrayOf(android.text.InputFilter.LengthFilter(p.optInt("max", 2000)))
                contentDescription = t(p.opt("label"), v).ifBlank { "Draft" }
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(s: Editable?) { drafts[id] = s?.toString().orEmpty(); dirtyDrafts += id }
                })
            }, LayoutParams(-1, -2))
        }
    }

    private fun buttons(p: JSONObject, v: CardPieces.Values, paused: Boolean): View {
        val items = p.optJSONArray("items") ?: JSONArray()
        val box = col()
        val row = LinearLayout(context).apply { orientation = HORIZONTAL }
        for (i in 0 until items.length()) {
            val b = items.getJSONObject(i)
            if (b.optJSONObject("only_if")?.let { CardPieces.holds(it, v) } == false) continue
            val pauseButton = b.optJSONObject("do")?.has("pause_applet") == true
            val style = b.optString("style", "plain")
            // A batch that is going: the button says how far it is, with a Cancel link; another tap does nothing.
            val batch = b.optJSONObject("do")?.takeIf { it.has("for_each") }?.let { batchState[batchKey(b)] }?.takeIf { it.optString("status") == "running" }
            if (batch != null) {
                val going = "${b.optJSONObject("do")?.optString("progress")?.ifBlank { null } ?: "Running"} ${batch.opt("current").takeIf { it != JSONObject.NULL } ?: batch.optInt("total")} of ${batch.optInt("total")}…"
                box.addView(LinearLayout(context).apply {
                    orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    addView(MaterialButton(context, null, M.attr.materialButtonOutlinedStyle).apply { text = going; isEnabled = false }, LayoutParams(0, -2, 1f))
                    addView(MaterialButton(context, null, M.attr.borderlessButtonStyle).apply {
                        text = if (batch.optBoolean("cancelling")) "Stopping…" else "Cancel"; isEnabled = !batch.optBoolean("cancelling")
                        setOnClickListener { scope?.launch { withContext(Dispatchers.IO) { app.platform.batches.cancel(card.agentId) } } }
                    }, LayoutParams(-2, -2))
                }, LayoutParams(-1, -2))
                continue
            }
            val mb = MaterialButton(context, null, when (style) { "primary" -> M.attr.materialButtonStyle; else -> M.attr.materialButtonOutlinedStyle }).apply {
                text = if (pauseButton && paused) "Resume" else b.optString("label")
                isEnabled = !busy
                if (style == "danger") setTextColor(context.themeColor(M.attr.colorError))
                setOnClickListener { resultPiece = p; press(b) }
            }
            if (style == "primary") box.addView(mb, LayoutParams(-1, -2))
            else row.addView(mb, LayoutParams(0, -2, 1f).apply { marginEnd = context.dp(8) })
        }
        if (row.childCount > 0) box.addView(row, LayoutParams(-1, -2))
        if (busy) box.addView(ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true }, LayoutParams(-1, -2))
        resultLine?.takeIf { resultPiece === p }?.let { box.addView(text(it, cc.bridgit.app.R.style.TextAppearance_Bridgit_BodyMedium, M.attr.colorOnSurfaceVariant).apply { setPadding(0, context.dp(6), 0, 0) }) }
        return box
    }

    /** A tap: the values are read now, not at the last draw, so what the owner just typed in a draft is what's sent. */
    private fun press(b: JSONObject) {
        if (busy) return
        val q = b.optString("confirm").takeIf { it.isNotBlank() }
        if (q != null) MaterialAlertDialogBuilder(context).setMessage(q).setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(b.optString("label")) { _, _ -> run(b, values()) }.show()
        else run(b, values())
    }

    private fun run(b: JSONObject, v: CardPieces.Values) {
        val s = scope ?: return
        val d = b.optJSONObject("do") ?: return
        // The camera button opens the camera screen; its photos save there, and the table redraws as rows arrive.
        d.optJSONObject("camera")?.let { c ->
            val row = JSONObject(); c.optJSONObject("row")?.let { r -> r.keys().forEach { k -> row.put(k, CardPieces.resolve(r.opt(k), v) ?: JSONObject.NULL) } }
            cc.bridgit.app.ui.camera.AppletCameraActivity.start(context, card.agentId, card.id, c, row)
            return
        }
        busy = true; resultLine = null; draw()
        s.launch {
            val words = try {
                when {
                    d.has("refresh") -> { readAll(); null }
                    d.has("pause_applet") -> {
                        val inst = withContext(Dispatchers.IO) { src.instance() } ?: throw IllegalStateException("This card isn't from an installed applet.")
                        val now = inst.optString("status") == "paused"
                        withContext(Dispatchers.IO) { app.applets.setPaused(inst.getString("id"), !now) }
                        pausedNow = !now
                        if (now) "Resumed." else null // the state strip already says Paused
                    }
                    d.has("run_workflow") -> {
                        val wf = withContext(Dispatchers.IO) { src.workflowId() } ?: throw IllegalStateException("This applet has no workflow.")
                        // A run may wait for you (ask_owner): it carries on in the background.
                        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) { runCatching { app.platform.workflows.runOnce(card.agentId, wf) } }
                        "Started."
                    }
                    d.has("flow") -> flowPress(b, d, v)
                    d.has("record") -> {
                        val table = withContext(Dispatchers.IO) { src.tables() }.firstOrNull { it.id == d.optString("record") } ?: throw IllegalStateException("The table ${d.optString("record")} is gone.")
                        val row = JSONObject(); d.optJSONObject("row")?.let { r -> r.keys().forEach { k -> row.put(k, CardPieces.resolve(r.opt(k), v) ?: JSONObject.NULL) } }
                        withContext(Dispatchers.IO) { AppletTableStore.of(app).add(card.agentId, table, row, JSONObject().put("type", "card").put("interaction_id", card.id)) }
                        d.optJSONObject("row")?.keys()?.forEach { k -> d.getJSONObject("row").optJSONObject(k)?.optString("\$draft")?.takeIf { it.isNotEmpty() }?.let { dirtyDrafts -= it } }
                        "Saved on the phone."
                    }
                    d.has("step") -> {
                        // A share step (O1): built here, then Android's share sheet; nothing leaves until the owner picks where.
                        val st = d.getJSONObject("step")
                        val table = withContext(Dispatchers.IO) { src.tables() }.firstOrNull { it.id == st.optString("table") } ?: throw IllegalStateException("The table ${st.optString("table")} is gone.")
                        val built = withContext(Dispatchers.IO) { cc.bridgit.app.applets.AppletExport.export(app, card.agentId, st, table) }
                        cc.bridgit.app.applets.AppletExport.share(context, built)
                        null
                    }
                    else -> {
                        val call = JSONObject().put("method", d.optString("method", if (d.has("body")) "POST" else "GET")).put("path", d.optString("path"))
                        d.optJSONObject("query")?.let { call.put("query", it) }
                        d.optJSONObject("body")?.let { call.put("body", resolveAll(it, v)) }
                        val r = kotlinx.coroutines.suspendCancellableCoroutine<JSONObject> { c -> CardActionRunner.run(app, card.id, call.toString()) { res -> c.resumeWith(Result.success(res)) } }
                        if (!r.optBoolean("ok")) throw IllegalStateException((r.opt("body") as? JSONObject)?.optString("message")?.ifEmpty { null } ?: r.optString("error"))
                        b.optString("into").takeIf { it.isNotEmpty() }?.let { n -> r.opt("body")?.takeIf { it != JSONObject.NULL }?.let { body -> src.replies[n] = body; loaded[n] = body; nulls -= n } }
                        "Done."
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { e.message?.ifBlank { null } ?: "That didn't work." }
            busy = false; resultLine = words
            keys.fill(null, 0, pieces.length()) // drafts already saved redraw from their source again
            draw()
        }
    }

    /** do.flow: starts the applet's flow once, or (for_each) a batch of runs, one per element (docs/applet-dev-experience.md R2). */
    private suspend fun flowPress(b: JSONObject, d: JSONObject, v: CardPieces.Values): String? {
        val flow = d.opt("flow").let { (it as? JSONObject)?.optString("\$flow") ?: it.toString() }
        if (!d.has("for_each")) {
            val with = d.optJSONObject("with")?.let { resolveAll(it, v) as JSONObject }
            val r = withContext(Dispatchers.IO) { app.platform.workflows.start(card.agentId, flow, JSONObject().put("caller", "button"), with) }
            return when (r.optString("status")) { "started" -> "Started."; "dropped" -> "It's already running."; else -> r.optString("message").ifBlank { "That didn't start." } }
        }
        val r = withContext(Dispatchers.IO) {
            if (app.platform.batches.running(card.agentId) != null) return@withContext null // a second tap while a batch runs does nothing
            val path = d.getJSONObject("for_each").getString("\$source").split('.')
            val table = src.sources.optJSONObject(path[0])?.optString("table")?.takeIf { it.isNotEmpty() && path.drop(1).let { r -> r.isEmpty() || r == listOf("items") } }
            // A table goes through all its rows, oldest first (not only the rows the source shows).
            val list = if (table != null) JSONArray(AppletTableStore.of(app).rows(card.agentId, table, 1000, null, newestFirst = false))
                else CardPieces.resolve(d.opt("for_each"), v) as? JSONArray ?: JSONArray()
            app.platform.batches.start(card.agentId, batchKey(b), b.optString("label"), flow, CardPieces.batchElements(d, list, v))
        } ?: return null
        readBatches()
        return when (r.optString("status")) { "started" -> null; "empty" -> "Nothing to do."; else -> r.optString("message").ifBlank { "That didn't start." } }
    }

    private fun resolveAll(o: Any?, v: CardPieces.Values): Any? = when (o) {
        is JSONObject -> if (o.length() == 1 && (o.has("\$source") || o.has("\$draft"))) (CardPieces.resolve(o, v) ?: JSONObject.NULL)
            else JSONObject().also { out -> o.keys().forEach { out.put(it, resolveAll(o.opt(it), v)) } }
        is JSONArray -> JSONArray().also { out -> for (i in 0 until o.length()) out.put(resolveAll(o.opt(i), v)) }
        else -> o
    }

    private companion object {
        const val GALLERY_VIDEO = "gallery_video"
        val WAVE2_TABLE = listOf("group_by", "totals", "marks", "photo", "owner_edit")
    }
}
