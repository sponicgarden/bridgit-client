package cc.bridgit.app.ui.interactions

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.applets.AppletTableStore
import cc.bridgit.app.applets.AppletTables
import cc.bridgit.app.interact.CardPieces
import cc.bridgit.app.interact.TablePieces
import cc.bridgit.app.ui.common.MaxHeightScrollView
import cc.bridgit.app.ui.common.dp
import cc.bridgit.app.ui.common.themeColor
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import com.google.android.material.R as M

/**
 * The `table` piece of a pieces card (docs/applet-cards.md, docs/applet-wave2.md C, D and A): rows under a heading
 * per `group_by` value with subtotals and a grand total, `marks` badges, a list that scrolls inside the card, and a
 * row that expands in place (its photo with its box outlined, the owner's Edit, Save and Delete). Each piece's
 * expanded row, edits and scroll position live here, so a redraw (new rows, the minute moving on) keeps them.
 */
internal class TablePieceView(private val host: PiecesCardView, private val app: BridgeApp, private val agentId: String,
                              /** Redraws [p]'s piece now, even while it is being edited. */
                              private val redraw: (p: JSONObject) -> Unit) {
    private class State {
        var sortColumn: String? = null
        var descending = false
        var expanded: String? = null
        var editing = false
        var saving = false
        var error: String? = null
        var note: String? = null
        var scrollY = 0
        var table: AppletTables.Table? = null
        /** Column → the text in its field while editing. */
        val edits = LinkedHashMap<String, String>()
    }

    private val states = java.util.IdentityHashMap<JSONObject, State>()
    private fun state(p: JSONObject) = states.getOrPut(p) { State() }
    private val context: Context get() = host.context

    /** What [p] shows besides its data: the expanded row and edit state (part of the piece's redraw key). */
    fun key(p: JSONObject): String = states[p]?.let { "|sort=${it.sortColumn}:${it.descending}|x=${it.expanded}|e=${it.editing}|s=${it.saving}|err=${it.error}|n=${it.note}" } ?: ""

    /** While the owner edits a row, data redraws wait, so the fields don't vanish mid-edit. */
    fun editing(p: JSONObject) = states[p]?.editing == true

    private fun rowKey(row: JSONObject, i: Int) = row.optString("id").ifEmpty { "#$i" }

    fun build(p: JSONObject, v: CardPieces.Values, tableId: String?): View {
        val st = state(p)
        // The table's own column names and types for the opened row (not only once Edit is tapped).
        if (st.table == null && tableId != null) st.table = runCatching { host.tables() }.getOrNull()?.firstOrNull { it.id == tableId }
        val all = CardPieces.items(CardPieces.resolve(p.opt("rows"), v))
        val rows = cc.bridgit.app.interact.TableSort.rows(all, st.sortColumn, st.descending).take(p.optInt("limit", 20))
        val cols = p.optJSONArray("columns") ?: JSONArray()
        val colList = (0 until cols.length()).map { cols.getJSONObject(it) }
        val box = host.col()
        if (rows.isEmpty()) { box.addView(host.emptyLine(p, v, "Nothing yet.")); return box }
        val groupBy = if (st.sortColumn == null) p.optString("group_by").ifEmpty { null } else null
        val totals = p.optJSONArray("totals")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
        val marks = p.optJSONObject("marks")
        val photo = p.optJSONObject("photo")
        val ownerEdit = p.optBoolean("owner_edit") && tableId != null
        val expandable = photo != null || ownerEdit
        fun opts(c: JSONObject) = JSONObject().apply { c.opt("words")?.let { put("words", it) }; c.opt("currency")?.let { put("currency", it) } }
        fun cell(c: JSONObject, row: JSONObject): String =
            CardPieces.format(row.opt(c.optString("key"))?.takeIf { it != JSONObject.NULL }, c.optString("format").ifEmpty { null }, opts(c)) ?: "—"
        // A total in its column's format; the first column carries the line's name.
        fun totalLine(name: String, sums: Map<String, Double?>, strong: Boolean): View {
            val cells = colList.mapIndexed { j, c ->
                val k = c.optString("key")
                val s = if (k in totals) sums[k]?.let { CardPieces.format(it, c.optString("format").ifEmpty { "number" }, opts(c)) } ?: "—" else ""
                if (j == 0) (if (s.isEmpty()) name else "$name $s") else s
            }
            return host.rowView(cells, header = !strong).apply {
                contentDescription = name + ": " + colList.indices.filter { colList[it].optString("key") in totals }.joinToString(", ") { t -> host.t(colList[t].opt("label"), v) + " " + cells[t] }
            }
        }
        box.addView(host.rowView(colList.map { c -> host.t(c.opt("label"), v) +
            if (st.sortColumn == c.optString("key")) (if (st.descending) " ↓" else " ↑") else "" }, header = true).apply {
            colList.forEachIndexed { i, c -> getChildAt(i).apply {
                isFocusable = true
                minimumHeight = context.dp(48)
                contentDescription = host.t(c.opt("label"), v) + if (st.sortColumn == c.optString("key"))
                    (if (st.descending) ", descending" else ", ascending") else ", sort"
                setOnClickListener {
                    if (st.editing) return@setOnClickListener
                    st.descending = st.sortColumn == c.optString("key") && !st.descending
                    st.sortColumn = c.optString("key")
                    redraw(p)
                }
            } }
        })
        val list = host.col()
        val grouped = TablePieces.group(rows, groupBy, totals)
        var index = 0
        for (g in grouped.groups) {
            if (groupBy != null) list.addView(host.text((g.name ?: "Other") + " · " + g.rows.size, M.style.TextAppearance_Material3_TitleSmall, M.attr.colorPrimary, bold = true).apply {
                setPadding(0, context.dp(10), 0, context.dp(2)); androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
            })
            for (row in g.rows) {
                val key = rowKey(row, index++)
                val cells = colList.map { cell(it, row) }
                val line = host.col()
                line.addView(host.rowView(cells, header = false).apply {
                    contentDescription = colList.indices.joinToString(", ") { host.t(colList[it].opt("label"), v) + " " + cells[it] }
                    setOnClickListener {
                        if (!expandable) { host.showRow(row); return@setOnClickListener }
                        if (st.editing) return@setOnClickListener
                        st.expanded = if (st.expanded == key) null else key
                        st.error = null; st.note = null
                        redraw(p)
                    }
                    if (p.optBoolean("owner_delete") && tableId != null && row.has("id")) setOnLongClickListener { host.confirmDelete(tableId, row.getString("id")); true }
                })
                marks?.let { m -> badges(m, row)?.let { line.addView(it) } }
                if (expandable && st.expanded == key) line.addView(detail(p, st, row, all, colList, photo, if (ownerEdit) tableId else null))
                list.addView(line)
            }
            if (groupBy != null && totals.isNotEmpty()) list.addView(totalLine("Subtotal", g.totals, strong = false))
        }
        // A grouped list with totals reads top to bottom with the card: an inner scroll hid rows and subtotals
        // below the fold on the A11 (a swipe moved the card, not the list), so only ungrouped lists scroll inside.
        if (rows.size > SCROLL_AT && groupBy == null) {
            val scroll = MaxHeightScrollView(context).apply {
                maxHeightPx = (host.resources.displayMetrics.heightPixels * 0.6).toInt()
                isNestedScrollingEnabled = true
                addView(list)
                setOnScrollChangeListener { _: androidx.core.widget.NestedScrollView, _: Int, y: Int, _: Int, _: Int -> st.scrollY = y }
            }
            box.addView(scroll, LinearLayout.LayoutParams(-1, -2))
            val y = st.scrollY
            if (y > 0) scroll.post { scroll.scrollTo(0, y) }
        } else box.addView(list)
        if (totals.isNotEmpty()) box.addView(totalLine("Total", grouped.totals, strong = true))
        return box
    }

    private fun badges(marks: JSONObject, row: JSONObject): View? {
        val words = marks.keys().asSequence().mapNotNull { k -> TablePieces.mark(row.opt(k), marks.opt(k), k)?.let { it to (row.opt(k).toString().lowercase() in setOf("true", "yes", "1")) } }.toList()
        if (words.isEmpty()) return null
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, context.dp(6))
            for ((w, on) in words) addView(host.text(w, M.style.TextAppearance_Material3_LabelSmall, if (on) M.attr.colorOnSecondaryContainer else M.attr.colorOnSurfaceVariant).apply {
                setPadding(context.dp(8), context.dp(2), context.dp(8), context.dp(2))
                background = GradientDrawable().apply { cornerRadius = context.dp(99).toFloat(); setColor(context.themeColor(if (on) M.attr.colorSecondaryContainer else M.attr.colorSurfaceContainerHighest)) }
            }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = context.dp(6) })
        }
    }

    /** The expanded row: its photo with boxes, every value (or the editors), and the owner's buttons. */
    private fun detail(p: JSONObject, st: State, row: JSONObject, all: List<JSONObject>, cols: List<JSONObject>, photo: JSONObject?, tableId: String?): View {
        val d = host.col().apply {
            setPadding(context.dp(10), context.dp(8), context.dp(10), context.dp(10))
            background = GradientDrawable().apply { cornerRadius = context.dp(10).toFloat(); setColor(context.themeColor(M.attr.colorSurfaceContainerHighest)) }
        }
        photo?.let { ph ->
            val col = ph.optString("column")
            val ref = (row.opt(col) as? String)?.takeIf { AppletTables.PHOTO_REF.matches(it) }
            if (ref == null) d.addView(host.text("No photo.", cc.bridgit.app.R.style.TextAppearance_Bridgit_BodySmall, M.attr.colorOnSurfaceVariant))
            else {
                val boxCol = ph.optString("box").ifEmpty { null }
                val mine = boxCol?.let { TablePieces.box(row.opt(it)) }
                val others = if (boxCol == null) emptyList() else all.filter { it !== row && it.optString(col) == ref && it.optString("id") != row.optString("id") }.mapNotNull { TablePieces.box(it.opt(boxCol)) }
                val img = BoxedPhotoView(context, mine, others).apply { contentDescription = "Photo of this row" + (if (mine != null) ", its item outlined" else ""); setOnClickListener { host.fullScreen(ref, null) } }
                d.addView(img, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = context.dp(8) })
                val width = host.resources.displayMetrics.widthPixels - context.dp(72)
                host.scope?.launch {
                    val bmp = withContext(Dispatchers.IO) { AppletTableStore.of(app).photoFile(agentId, ref)?.let { f -> runCatching { host.decode(f.path, width) }.getOrNull() } }
                    if (bmp != null) img.setImageBitmap(bmp) else { d.addView(host.text("This photo is no longer on the phone.", cc.bridgit.app.R.style.TextAppearance_Bridgit_BodySmall, M.attr.colorOnSurfaceVariant), d.indexOfChild(img)); d.removeView(img) }
                }
            }
        }
        val table = st.table
        if (st.editing && table != null) editors(d, st, row, table, photo?.optString("box")?.ifEmpty { null })
        else {
            val shown = cols.associateBy { it.optString("key") }
            val boxKey = photo?.optString("box")?.ifEmpty { null }
            for (k in row.keys().asSequence().filter { it != "\$key" && it != "id" && it != "by" && it != boxKey }) {
                val x = row.opt(k)
                if ((x as? String)?.let { AppletTables.PHOTO_REF.matches(it) } == true) continue
                val c = shown[k]
                // The box is drawn on the photo above, so its numbers aren't listed; booleans read Yes / No.
                val type = st.table?.columns?.get(k)?.type
                val value = CardPieces.format(x, c?.optString("format")?.ifEmpty { null } ?: if (k == "at") "time" else if (type == "boolean") "yes_no" else null,
                    JSONObject().apply { c?.opt("words")?.let { put("words", it) }; c?.opt("currency")?.let { put("currency", it) } }) ?: "—"
                d.addView(host.text("${label(st, k)}: $value", cc.bridgit.app.R.style.TextAppearance_Bridgit_BodyMedium, M.attr.colorOnSurface).apply { setPadding(0, context.dp(2), 0, context.dp(2)) })
            }
        }
        st.error?.let { d.addView(host.text(it, cc.bridgit.app.R.style.TextAppearance_Bridgit_BodyMedium, M.attr.colorError).apply { setPadding(0, context.dp(6), 0, 0) }) }
        st.note?.let { d.addView(host.text(it, cc.bridgit.app.R.style.TextAppearance_Bridgit_BodyMedium, M.attr.colorOnSurfaceVariant).apply { setPadding(0, context.dp(6), 0, 0) }) }
        val rowId = row.optString("id").ifEmpty { null }
        if (tableId != null && rowId != null) {
            val buttons = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, context.dp(8), 0, 0) }
            fun button(label: String, primary: Boolean, danger: Boolean = false, onTap: () -> Unit) = buttons.addView(
                MaterialButton(context, null, if (primary) M.attr.materialButtonStyle else M.attr.materialButtonOutlinedStyle).apply {
                    text = label; isEnabled = !st.saving
                    if (danger) setTextColor(context.themeColor(M.attr.colorError))
                    setOnClickListener { onTap() }
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = context.dp(8) })
            if (st.editing) {
                button("Save", true) { save(p, st, tableId, rowId) }
                button("Cancel", false) { st.editing = false; st.edits.clear(); st.error = null; redraw(p) }
            } else {
                button("Edit", true) { startEdit(p, st, tableId) }
                button("Delete", false, danger = true) { host.confirmDelete(tableId, rowId) { st.expanded = null } }
            }
            d.addView(buttons, LinearLayout.LayoutParams(-1, -2))
        }
        return d
    }

    private fun label(st: State, k: String) = st.table?.columns?.get(k)?.label ?: if (k == "at") "Saved" else k.replace('_', ' ').replaceFirstChar { it.uppercase() }

    private fun startEdit(p: JSONObject, st: State, tableId: String) {
        host.scope?.launch {
            val t = withContext(Dispatchers.IO) { host.tables() }.firstOrNull { it.id == tableId }
            if (t == null) { st.error = "This table is gone from the applet."; redraw(p); return@launch }
            st.table = t; st.editing = true; st.edits.clear(); st.error = null; st.note = null
            redraw(p)
        }
    }

    /** One field per column the owner may change: a picker, a switch, a number box or a text box. Photos stay. */
    private fun editors(d: LinearLayout, st: State, row: JSONObject, t: AppletTables.Table, boxKey: String? = null) {
        for (c in t.columns.values) {
            if (c.type == "photo" || c.id == boxKey) continue
            val now = st.edits[c.id] ?: row.opt(c.id)?.takeIf { it != JSONObject.NULL }?.let { CardPieces.show(it) } ?: ""
            d.addView(host.text(c.label, M.style.TextAppearance_Material3_LabelMedium, M.attr.colorOnSurfaceVariant).apply { setPadding(0, context.dp(6), 0, 0) })
            when (c.type) {
                "boolean" -> d.addView(MaterialSwitch(context).apply {
                    isChecked = now.equals("true", ignoreCase = true); contentDescription = c.label
                    setOnCheckedChangeListener { _, on -> st.edits[c.id] = on.toString() }
                })
                "choice" -> {
                    val options = listOf("—") + c.options
                    d.addView(Spinner(context).apply {
                        adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, options)
                        contentDescription = c.label
                        setSelection(c.options.indexOfFirst { it.equals(now.trim(), ignoreCase = true) } + 1)
                        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                                val v = if (pos == 0) "" else options[pos]
                                if (v != now || c.id in st.edits) st.edits[c.id] = v
                            }
                            override fun onNothingSelected(parent: AdapterView<*>?) {}
                        }
                    }, LinearLayout.LayoutParams(-1, -2))
                }
                else -> d.addView(EditText(context).apply {
                    setText(now); contentDescription = c.label
                    inputType = when (c.type) {
                        "number" -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
                        "integer" -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
                        else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or (if (c.type == "text") InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0)
                    }
                    if (c.type == "text") filters = arrayOf(android.text.InputFilter.LengthFilter(AppletTables.MAX_TEXT))
                    addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, n: Int) {}
                        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, n: Int) {}
                        override fun afterTextChanged(s: Editable?) { st.edits[c.id] = s?.toString().orEmpty() }
                    })
                }, LinearLayout.LayoutParams(-1, -2))
            }
        }
    }

    private fun save(p: JSONObject, st: State, tableId: String, rowId: String) {
        val t = st.table ?: return
        val set = JSONObject()
        for ((k, s) in st.edits) if (k in t.columns) set.put(k, if (s.isBlank()) JSONObject.NULL else s.trim())
        if (set.length() == 0) { st.editing = false; st.note = "Nothing changed."; redraw(p); return }
        st.saving = true; st.error = null; redraw(p)
        host.scope?.launch {
            val err = withContext(Dispatchers.IO) {
                try { AppletTableStore.of(app).update(agentId, t, rowId, set, OWNER_BY); null }
                catch (e: AppletTables.Refused) { if (e.code == "row_not_found") "This row was deleted meanwhile." else e.message ?: "That couldn't be saved." }
                catch (e: Exception) { "That couldn't be saved: ${e.message ?: e.javaClass.simpleName}" }
            }
            st.saving = false
            if (err == null) { st.editing = false; st.edits.clear(); st.note = "Saved." } else st.error = err
            redraw(p)
        }
    }

    private companion object {
        /** More rows than this scroll inside the card. */
        const val SCROLL_AT = 12
        val OWNER_BY: JSONObject get() = JSONObject().put("type", "owner").put("owner", true)
    }
}

/** A photo drawn whole (fit to width) with this row's box outlined bright and the boxes of other rows on it faint. */
@SuppressLint("ViewConstructor")
internal class BoxedPhotoView(context: Context, private val mine: TablePieces.Box?, private val others: List<TablePieces.Box>) : ImageView(context) {
    private val d = resources.displayMetrics.density
    private val faint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d; color = Color.argb(150, 255, 255, 255) }
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5f * d; color = Color.argb(140, 0, 0, 0) }
    private val bright = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f * d; color = Color.rgb(255, 214, 0) }

    init { adjustViewBounds = true; scaleType = ScaleType.FIT_CENTER; minimumHeight = (120 * d).toInt() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val dr = drawable ?: return
        if (dr.intrinsicWidth <= 0 || dr.intrinsicHeight <= 0) return
        val area = RectF(0f, 0f, dr.intrinsicWidth.toFloat(), dr.intrinsicHeight.toFloat())
        imageMatrix.mapRect(area)
        area.offset(paddingLeft.toFloat(), paddingTop.toFloat())
        fun rect(b: TablePieces.Box) = RectF(area.left + area.width() * b.x0.toFloat(), area.top + area.height() * b.y0.toFloat(),
            area.left + area.width() * b.x1.toFloat(), area.top + area.height() * b.y1.toFloat())
        others.forEach { canvas.drawRect(rect(it), faint) }
        mine?.let { val r = rect(it); canvas.drawRect(r, shade); canvas.drawRect(r, bright) }
    }
}
