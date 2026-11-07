package cc.bridgit.app.ui.interactions

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import cc.bridgit.app.ui.common.dp
import cc.bridgit.app.ui.common.themeColor
import com.google.android.material.R as M

/**
 * Spacing and dividers for the Interactions list (design pass, Sol 6.1 spec, 4 Nov 2026): flat rows on the screen
 * surface, a 1dp divider between rows of the same section inset to the text start (16dp + 40dp icon + 12dp),
 * and 16dp between sections. No panels or per-row cards.
 */
class SectionPanelDecoration(ctx: Context, private val adapter: InteractionsAdapter) : RecyclerView.ItemDecoration() {

    private val gap = ctx.dp(16)
    private val inset = ctx.dp(68).toFloat()
    private val divider = Paint().apply {
        color = ctx.themeColor(M.attr.colorOutlineVariant)
        strokeWidth = ctx.dp(1).toFloat()
    }

    private fun isSection(pos: Int) = adapter.currentList.getOrNull(pos) is InteractionRow.Section

    override fun getItemOffsets(out: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        val pos = parent.getChildAdapterPosition(view)
        if (pos != RecyclerView.NO_POSITION && pos > 0 && isSection(pos)) out.top = gap
    }

    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        for (i in 0 until parent.childCount) {
            val v = parent.getChildAt(i)
            val pos = parent.getChildAdapterPosition(v)
            if (pos == RecyclerView.NO_POSITION || isSection(pos)) continue
            if (pos + 1 >= adapter.itemCount || isSection(pos + 1)) continue // no line under a section's last row
            val y = v.bottom + v.translationY
            c.drawLine(inset + v.translationX, y, parent.width.toFloat(), y, divider)
        }
    }
}
