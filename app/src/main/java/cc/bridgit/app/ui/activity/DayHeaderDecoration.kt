package cc.bridgit.app.ui.activity

import android.graphics.Canvas
import android.graphics.Rect
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import cc.bridgit.app.R
import cc.bridgit.app.ui.common.Fmt
import java.time.Instant
import java.time.ZoneId

/**
 * Sticky day headers for the Activity list (ui-spec 3 and 5.7): "Today", "Yesterday",
 * "Mon 26 Oct" in primary LabelMedium. Rows stay plain LogRowAdapter rows; the decoration
 * reserves space above the first row of each day and keeps the current day pinned at the top.
 *
 * [tsAt] returns the timestamp of the log row at an adapter position, or null for
 * positions that aren't log rows (the loading row).
 */
class DayHeaderDecoration(parent: RecyclerView, private val tsAt: (Int) -> Long?) : RecyclerView.ItemDecoration() {
    private val header = LayoutInflater.from(parent.context)
        .inflate(R.layout.activity_day_header, parent, false) as TextView
    private var measuredWidth = -1
    private var height = 0

    fun dayKey(ts: Long): Long = Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()

    /** True when the row at [pos] is the first (newest) row of its day. */
    fun isFirstOfDay(pos: Int): Boolean {
        val t = tsAt(pos) ?: return false
        if (pos == 0) return true
        val prev = tsAt(pos - 1) ?: return true
        return dayKey(t) != dayKey(prev)
    }

    private fun prepare(parent: RecyclerView, text: String): Int {
        header.text = text
        val w = parent.width
        header.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        header.layout(0, 0, header.measuredWidth, header.measuredHeight)
        return header.measuredHeight
    }

    private fun headerHeight(parent: RecyclerView): Int {
        if (measuredWidth != parent.width || height == 0) {
            measuredWidth = parent.width
            height = prepare(parent, "Today")
        }
        return height
    }

    override fun getItemOffsets(out: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        val pos = parent.getChildAdapterPosition(view)
        if (pos != RecyclerView.NO_POSITION && isFirstOfDay(pos)) out.top = headerHeight(parent)
    }

    override fun onDrawOver(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        if (parent.childCount == 0) return
        val h = headerHeight(parent)
        // Inline headers above the first row of each day.
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            val pos = parent.getChildAdapterPosition(child)
            if (pos == RecyclerView.NO_POSITION || !isFirstOfDay(pos)) continue
            val ts = tsAt(pos) ?: continue
            drawAt(c, parent, Fmt.day(ts), child.top + child.translationY - h)
        }
        // The pinned header for the day at the top, pushed up by the next day's header.
        val first = parent.getChildAt(0)
        val firstPos = parent.getChildAdapterPosition(first)
        if (firstPos == RecyclerView.NO_POSITION) return
        val ts = tsAt(firstPos) ?: return
        var y = parent.paddingTop.toFloat()
        val day = dayKey(ts)
        for (i in 1 until parent.childCount) {
            val child = parent.getChildAt(i)
            val pos = parent.getChildAdapterPosition(child)
            val t = if (pos == RecyclerView.NO_POSITION) null else tsAt(pos)
            if (t != null && dayKey(t) != day) {
                val nextHeaderTop = child.top + child.translationY - h
                if (nextHeaderTop < y + h) y = nextHeaderTop - h
                break
            }
        }
        drawAt(c, parent, Fmt.day(ts), y)
    }

    private fun drawAt(c: Canvas, parent: RecyclerView, text: String, y: Float) {
        prepare(parent, text)
        c.save()
        c.translate(0f, y)
        header.draw(c)
        c.restore()
    }
}
