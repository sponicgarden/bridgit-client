package cc.bridgit.app.ui.tour

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.R as M
import cc.bridgit.app.R
import cc.bridgit.app.databinding.TourRowSaidBinding
import cc.bridgit.app.tour.Said
import cc.bridgit.app.ui.common.Fmt
import cc.bridgit.app.ui.common.themeColor

/** A scroll view that never takes more than [FRACTION] of the screen, so the history keeps room at 200 % font. */
class TourMaxHeightScrollView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : NestedScrollView(ctx, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val max = (resources.displayMetrics.heightPixels * FRACTION).toInt()
        val size = MeasureSpec.getSize(heightMeasureSpec)
        val limited = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) max else minOf(size, max)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(limited, MeasureSpec.AT_MOST))
    }

    companion object { private const val FRACTION = 0.45f }
}

/** The tour's history: what you asked (end, secondary colour, "You") and what the agent said (start, surface, its name). */
class TourSaidAdapter : ListAdapter<Said, TourSaidAdapter.Holder>(DIFF) {
    class Holder(val b: TourRowSaidBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(TourRowSaidBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(h: Holder, position: Int) {
        val s = getItem(position)
        val ctx = h.b.root.context
        val mine = s.who == YOU
        val who = if (mine) ctx.getString(R.string.tour_you) else s.who
        val time = Fmt.time(s.at)
        h.b.who.text = "$who · $time"
        h.b.text.text = s.text
        val bg = ctx.themeColor(if (mine) M.attr.colorSecondaryContainer else M.attr.colorSurfaceContainerHigh)
        val fg = ctx.themeColor(if (mine) M.attr.colorOnSecondaryContainer else M.attr.colorOnSurface)
        h.b.bubble.setCardBackgroundColor(bg)
        h.b.text.setTextColor(fg)
        h.b.who.setTextColor(ctx.themeColor(if (mine) M.attr.colorOnSecondaryContainer else M.attr.colorPrimary))
        (h.b.bubble.layoutParams as FrameLayout.LayoutParams).apply {
            gravity = if (mine) Gravity.END else Gravity.START
            val inset = (48 * ctx.resources.displayMetrics.density).toInt()
            marginStart = if (mine) inset else 0
            marginEnd = if (mine) 0 else inset
        }.also { h.b.bubble.layoutParams = it }
        h.b.bubble.contentDescription = ctx.getString(R.string.tour_cd_said, who, time, s.text)
    }

    companion object {
        /** Tour.kt records the owner's questions with who = "You". */
        const val YOU = "You"
        val DIFF = object : DiffUtil.ItemCallback<Said>() {
            override fun areItemsTheSame(a: Said, b: Said) = a.at == b.at && a.who == b.who
            override fun areContentsTheSame(a: Said, b: Said) = a == b
        }
    }
}
