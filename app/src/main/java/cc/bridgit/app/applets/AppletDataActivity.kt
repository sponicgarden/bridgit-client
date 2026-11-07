package cc.bridgit.app.applets

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.interact.CardPieces
import cc.bridgit.app.ui.common.OwnerScreen
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Applets › Installed applets › (applet) › Data (docs/applet-cards.md, phase 2): the tables an applet keeps on the
 * phone. Each table with its row count, space and how long rows are kept; its rows newest first; one row in full;
 * Delete for a row and Delete all rows for a table; Export makes a PDF or CSV of a table and opens the share sheet
 * (docs/applet-dev-experience.md O1). Nothing leaves the phone without the owner's pick in that sheet.
 */
class AppletDataActivity : AppCompatActivity() {
    private val app get() = application as BridgeApp
    private lateinit var screen: OwnerScreen
    private var table: String? = null
    private var row: String? = null
    /** How many pages of [PAGE] rows the open table shows ("Show older" adds one). */
    private var pages = 1
    private val listener: (String) -> Unit = { a -> if (a == instance()?.optString("agent_id")) runOnUiThread { render() } }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        screen = OwnerScreen(this, "Applet data")
        table = state?.getString("table"); row = state?.getString("row"); pages = state?.getInt("pages", 1) ?: 1
        render()
    }

    override fun onStart() { super.onStart(); AppletTableStore.of(app).addListener(listener); render() }
    override fun onStop() { AppletTableStore.of(app).removeListener(listener); super.onStop() }
    override fun onSaveInstanceState(out: Bundle) { out.putString("table", table); out.putString("row", row); out.putInt("pages", pages); super.onSaveInstanceState(out) }

    @Deprecated("Back goes up one level: row → table → tables.")
    override fun onBackPressed() {
        when { row != null -> { row = null; render() }; table != null -> { table = null; pages = 1; render() }; else -> @Suppress("DEPRECATION") super.onBackPressed() }
    }

    private fun instance(): JSONObject? = intent.getStringExtra(EXTRA_INSTANCE)?.let { app.applets.get(it) }
    private fun tables(r: JSONObject) = runCatching { AppletTables.parse(r.getJSONObject("definition").optJSONObject("processing") ?: JSONObject()) }.getOrDefault(emptyList())
    private val store get() = AppletTableStore.of(app)

    private fun render() {
        val r = instance() ?: run { screen.clear(); screen.note("This applet was removed."); return }
        val agent = r.optString("agent_id")
        val all = tables(r)
        screen.clear()
        screen.toolbar.title = "${r.optString("name")} · Data"
        val t = all.firstOrNull { it.id == table }
        if (t == null) { table = null; list(agent, all); return }
        val one = row?.let { store.get(agent, t.id, it) }
        if (one == null) { row = null; rows(agent, t); return }
        detail(agent, t, one)
    }

    private fun size(bytes: Long) = when { bytes < 1024 -> "$bytes bytes"; bytes < 1024 * 1024 -> "${bytes / 1024} KB"; else -> "%.1f MB".format(bytes / 1048576.0) }

    private fun list(agent: String, all: List<AppletTables.Table>) {
        screen.note("What this applet saved on this phone. It never leaves the phone unless you let the control agent that installed it read a table, or you export a table and share it. Removing the applet deletes all of it.")
        if (all.isEmpty()) { screen.note("This applet keeps no tables."); return }
        for (t in all) screen.card {
            val n = store.count(agent, t.id)
            title(t.label)
            text("$n row${if (n == 1) "" else "s"} · ${size(store.bytes(agent, t.id))} · rows kept ${t.keepDays} days, at most ${t.maxRows}")
            if (t.shared) text("The control agent that installed this applet can read these rows.")
            if (n > 0) secondary("Delete all rows", danger = true) { confirmAll(agent, t, n) }
            if (n > 0) secondary("Export") { export(agent, t) }
            primary("Open") { table = t.id; pages = 1; render() }
        }
    }

    private fun rows(agent: String, t: AppletTables.Table) {
        // Newest first, a page at a time: each page starts after the last row of the one before.
        val list = ArrayList<JSONObject>(); var full = false
        for (i in 0 until pages) {
            val page = store.rows(agent, t.id, PAGE, list.lastOrNull()?.getString("id"), true)
            list += page; full = page.size >= PAGE
            if (!full) break
        }
        screen.header("${t.label} · newest first")
        if (list.isEmpty()) { screen.note("No rows yet."); return }
        for (x in list) screen.card {
            val first = t.columns.keys.firstNotNullOfOrNull { k -> x.opt(k)?.takeIf { it != JSONObject.NULL }?.let { v -> CardPieces.show(v).takeIf { it.isNotBlank() } } } ?: "(empty row)"
            title(first.take(120))
            text(CardPieces.format(x.opt("at"), "time") ?: "")
            secondary("Delete", danger = true) { confirmRow(agent, t, x.getString("id")) }
            primary("Open") { row = x.getString("id"); render() }
        }
        if (full) screen.card {
            text("Showing the newest ${list.size} rows.")
            secondary("Show older") { pages++; render() }
        }
    }

    private fun detail(agent: String, t: AppletTables.Table, x: JSONObject) {
        screen.header(t.label)
        screen.card {
            for ((id, c) in t.columns) {
                val v = x.opt(id)?.takeIf { it != JSONObject.NULL }
                title(c.label ?: id.replace('_', ' ').replaceFirstChar { it.uppercase() })
                // A kept photo shows as itself, full width.
                if (v != null && c.type == "photo" && photo(agent, v.toString())) continue
                text(when {
                    v == null -> "—"
                    c.type == "photo" -> "This photo is no longer on the phone."
                    c.type == "time" -> CardPieces.format(v, "time") ?: CardPieces.show(v)
                    c.type == "boolean" -> CardPieces.format(v, "yes_no") ?: CardPieces.show(v)
                    else -> CardPieces.show(v)
                }, quiet = false, gapDp = 0)
            }
            text("Saved ${CardPieces.format(x.opt("at"), "time") ?: ""}" + when (x.optJSONObject("by")?.optString("type")) {
                "flow" -> " by the applet's workflow"; "card" -> " from its card"; "agent" -> " by the control agent that installed it"; else -> "" }, gapDp = 12)
            secondary("Delete", danger = true) { confirmRow(agent, t, x.getString("id")) }
        }
    }

    /** A kept photo, full width, in the card being built. Returns false when it isn't on the phone any more. */
    private fun OwnerScreen.Card.photo(agent: String, ref: String): Boolean {
        val f = store.photoFile(agent, ref) ?: return false
        val img = android.widget.ImageView(this@AppletDataActivity).apply {
            adjustViewBounds = true; scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            contentDescription = "Kept photo"
        }
        view(img)
        val width = resources.displayMetrics.widthPixels
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { runCatching { decode(f.path, width) }.getOrNull() }?.let { img.setImageBitmap(it) }
        }
        return true
    }

    private fun decode(path: String, target: Int): android.graphics.Bitmap? {
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(path, o)
        var sample = 1
        while (o.outWidth / (sample * 2) >= target) sample *= 2
        return android.graphics.BitmapFactory.decodeFile(path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun confirmRow(agent: String, t: AppletTables.Table, id: String) {
        MaterialAlertDialogBuilder(this).setTitle("Delete this row?").setMessage("It's deleted from ${t.label} on this phone, with any photo only it used. This can't be undone.")
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Delete") { _, _ -> lifecycleScope.launch { withContext(Dispatchers.IO) { store.delete(agent, t.id, id) }; row = null; render() } }.show()
    }

    /** Export (O1): every column, no grouping or totals, as a PDF or a CSV file, then the share sheet. */
    private fun export(agent: String, t: AppletTables.Table) {
        MaterialAlertDialogBuilder(this).setTitle("Export ${t.label}")
            .setItems(arrayOf("PDF report", "CSV file (for a spreadsheet)")) { _, which ->
                val step = JSONObject().put("type", "share").put("table", t.id).put("as", if (which == 0) "pdf" else "csv")
                lifecycleScope.launch {
                    try {
                        val built = withContext(Dispatchers.IO) { AppletExport.export(app, agent, step, t) }
                        AppletExport.share(this@AppletDataActivity, built)
                    } catch (e: Exception) {
                        MaterialAlertDialogBuilder(this@AppletDataActivity).setTitle("Couldn't export").setMessage(e.message ?: "That didn't work.")
                            .setPositiveButton(android.R.string.ok, null).show()
                    }
                }
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun confirmAll(agent: String, t: AppletTables.Table, n: Int) {
        MaterialAlertDialogBuilder(this).setTitle("Delete all $n rows of ${t.label}?").setMessage("They're deleted from this phone, with the photos they used. This can't be undone.")
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Delete all") { _, _ -> lifecycleScope.launch { withContext(Dispatchers.IO) { store.deleteAll(agent, t.id) }; render() } }.show()
    }

    companion object { const val EXTRA_INSTANCE = "instance"; const val PAGE = 200 }
}
