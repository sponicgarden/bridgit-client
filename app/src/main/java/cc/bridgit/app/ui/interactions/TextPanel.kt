package cc.bridgit.app.ui.interactions

import android.view.View
import androidx.core.view.doOnPreDraw
import androidx.core.view.isVisible
import cc.bridgit.app.R
import cc.bridgit.app.databinding.ViewTextPanelBinding
import cc.bridgit.app.interact.Interaction
import cc.bridgit.app.ui.common.dp

/**
 * D26 live text panel, drawn in MainActivity's lower half (in-app only, no overlay). Shows one text
 * interaction: header with the agent and title, Pause scrolling / Resume, Close; monospace text that
 * follows new lines unless paused or the owner scrolled up to read. [onClose] dismisses it (the agent is told)
 * or, for one that is already closed, just hides it.
 */
class TextPanel(private val b: ViewTextPanelBinding, private val onClose: (Interaction) -> Unit) {
    private val ctx get() = b.root.context
    private var shownId: Long? = null
    private var shownText: String = ""
    private var paused = false

    val visibleId: Long? get() = shownId

    init {
        b.panelPause.setOnClickListener {
            paused = !paused
            renderPause()
            if (!paused) scrollToEnd()
        }
        renderPause()
    }

    /** Shows [i] (a text interaction) or hides the panel when null. Cheap when nothing changed. */
    fun show(i: Interaction?) {
        if (i == null) {
            b.root.isVisible = false
            shownId = null; shownText = ""
            return
        }
        val fresh = i.id != shownId
        if (fresh) { shownId = i.id; shownText = ""; paused = false; renderPause(); b.panelText.text = "" }
        b.root.isVisible = true

        val agent = InteractionUi.agentName(i)
        b.panelAvatar.setAgent(agent)
        b.panelTitle.text = i.title
        b.panelState.text = ctx.getString(when {
            !i.status.open -> R.string.text_panel_closed
            i.streamDone -> R.string.text_panel_finished
            else -> R.string.text_panel_live
        }, agent)
        b.panelTitles.contentDescription = i.title + ". " + b.panelState.text
        b.panelClose.contentDescription = if (i.status.open) ctx.getString(R.string.text_panel_close_desc, agent) else ctx.getString(R.string.text_panel_hide_desc)
        b.panelClose.setOnClickListener { onClose(i) }
        b.panelText.contentDescription = null

        val text = i.streamText
        if (text == shownText && !fresh) return
        // Follow new text only while the owner is at the bottom (they haven't scrolled up to read) and not paused.
        val follow = fresh || isAtBottom()
        if (text.isEmpty()) {
            b.panelText.text = ctx.getString(R.string.text_panel_empty)
            b.panelText.setTextColor(b.panelState.currentTextColor)
        } else {
            b.panelText.setTextColor(b.panelTitle.currentTextColor)
            if (shownText.isNotEmpty() && text.length > shownText.length && text.startsWith(shownText)) b.panelText.append(text.substring(shownText.length))
            else b.panelText.setText(text, android.widget.TextView.BufferType.EDITABLE)
        }
        shownText = text
        if (follow && !paused) scrollToEnd()
    }

    private fun isAtBottom(): Boolean {
        val s = b.panelScroll
        val child = s.getChildAt(0) ?: return true
        return !s.canScrollVertically(1) || s.scrollY + s.height >= child.height - ctx.dp(48)
    }

    private fun scrollToEnd() {
        b.panelText.doOnPreDraw { b.panelScroll.scrollTo(0, b.panelText.height) }
        b.panelText.invalidate()
    }

    private fun renderPause() {
        b.panelPause.setText(if (paused) R.string.text_panel_resume else R.string.text_panel_pause)
        b.panelPause.contentDescription = ctx.getString(if (paused) R.string.text_panel_resume_desc else R.string.text_panel_pause_desc)
    }

    /** Bottom padding for the system navigation bar when the bottom tabs are hidden. */
    fun setBottomInset(px: Int) { b.panelScroll.setPadding(0, 0, 0, px); b.root.visibility.let { if (it == View.VISIBLE) b.root.requestLayout() } }
}
