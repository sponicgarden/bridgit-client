package cc.bridgit.app.ui.interactions

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import cc.bridgit.app.ui.common.dp
import kotlin.math.abs

/**
 * #0005: a Tinder-style stack of card slots, one per interaction id. The current card is on top with the next one
 * peeking out behind it (smaller, lower). Every slot stays attached once made, so a card's page keeps its state when
 * the owner swipes away and back; slots not in view are INVISIBLE, never removed.
 *
 * Gestures (only with 2+ cards, #0008): a clearly sideways drag moves the top card. Drag left = next, drag right =
 * previous (it slides back in on top). When [answersBySwipe] says the current card answers by swiping, both directions
 * fly it off, call [onSwipeAnswer] and move on. Vertical drags stay with the page inside the card.
 */
@SuppressLint("ViewConstructor")
class CardStackView(context: Context) : FrameLayout(context) {
    /** Called with the new position after every move (gesture or [go]). */
    var onMoved: (Int) -> Unit = {}
    /** Whether the card at a position answers by swiping right now (a waiting card with swipe_answer). */
    var answersBySwipe: (Int) -> Boolean = { false }
    /** A swipe answered the card at the position: "left" or "right". */
    var onSwipeAnswer: (Int, String) -> Unit = { _, _ -> }

    private var ids: List<Long> = emptyList()
    private val slots = LinkedHashMap<Long, FrameLayout>()
    var position = 0
        private set
    val size get() = ids.size

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val flingVelocity = context.dp(700).toFloat()
    private val peekDrop = context.dp(14).toFloat()
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var animating = false
    private var velocity: VelocityTracker? = null

    init {
        clipChildren = false
        clipToPadding = false
    }

    /** The slot for [id] (made on first ask); the caller puts the card's view in it. */
    fun slot(id: Long): FrameLayout = slots.getOrPut(id) {
        FrameLayout(context).also { s ->
            s.visibility = INVISIBLE
            addView(s, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
    }

    fun hasSlot(id: Long) = slots.containsKey(id)

    /** Sets the cards in order; keeps the current card by id when it's still there. Returns the removed ids. */
    fun setIds(newIds: List<Long>, focus: Long? = null): List<Long> {
        val keep = focus ?: ids.getOrNull(position)
        ids = newIds
        // Room for the peeking card under the top one, only when there is one.
        setPadding(0, 0, 0, if (newIds.size > 1) context.dp(16) else 0)
        val gone = slots.keys.filter { it !in newIds }
        gone.forEach { slots.remove(it)?.let(::removeView) }
        position = (keep?.let { newIds.indexOf(it) }?.takeIf { it >= 0 } ?: position).coerceIn(0, (newIds.size - 1).coerceAtLeast(0))
        layoutCards()
        return gone
    }

    val currentId: Long? get() = ids.getOrNull(position)
    fun idAt(pos: Int): Long? = ids.getOrNull(pos)

    /** Moves to [to] with the fly animation (Back/Next buttons, auto-advance, TalkBack). */
    fun go(to: Int) {
        if (to !in ids.indices || to == position || animating) return
        if (to > position) flyOut(slotAt(position), -1, to) else flyIn(to)
    }

    private fun slotAt(pos: Int): View? = ids.getOrNull(pos)?.let { slots[it] }

    /** Puts every slot where it belongs for [position]: top card, one peeking card, the rest hidden. */
    private fun layoutCards() {
        // Never mid-flight: cancelling the animation would skip its end action and leave the stack stuck.
        if (animating) return
        val h = (height - paddingBottom).coerceAtLeast(1).toFloat()
        ids.forEachIndexed { n, id ->
            val s = slots[id] ?: return@forEachIndexed
            s.animate().cancel()
            s.rotation = 0f
            s.translationX = 0f
            s.alpha = 1f
            when (n) {
                position -> { s.visibility = VISIBLE; s.scaleX = 1f; s.scaleY = 1f; s.translationY = 0f; s.translationZ = context.dp(3).toFloat(); s.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_AUTO }
                position + 1 -> {
                    // Scaled to 94% around its centre, then dropped so its bottom edge shows [peekDrop] under the top card.
                    s.visibility = VISIBLE; s.scaleX = PEEK_SCALE; s.scaleY = PEEK_SCALE
                    s.translationY = h * (1 - PEEK_SCALE) / 2 + peekDrop; s.translationZ = context.dp(1).toFloat()
                    s.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                }
                else -> { s.visibility = INVISIBLE; s.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!animating) layoutCards()
    }

    private val swipeable get() = ids.size > 1

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        if (!swipeable) return false
        when (e.actionMasked) {
            // Always start a fresh gesture, even mid-flight, so no move is measured from an old finger.
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; dragging = false; velocity?.recycle(); velocity = VelocityTracker.obtain(); velocity?.addMovement(e) }
            MotionEvent.ACTION_MOVE -> {
                if (animating) return false
                velocity?.addMovement(e)
                val dx = e.x - downX; val dy = e.y - downY
                // Only clearly sideways drags; the page keeps vertical scrolling and taps.
                if (abs(dx) > slop * 1.5f && abs(dx) > abs(dy) * 1.5f) {
                    dragging = true
                    downX = e.x; downY = e.y
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!dragging) return swipeable && e.actionMasked == MotionEvent.ACTION_DOWN
        velocity?.addMovement(e)
        val dx = e.x - downX
        val w = width.coerceAtLeast(1).toFloat()
        val byAnswer = answersBySwipe(position)
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> drag(dx, w, byAnswer)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                velocity?.computeCurrentVelocity(1000)
                val vx = velocity?.xVelocity ?: 0f
                velocity?.recycle(); velocity = null
                dragging = false
                val far = abs(dx) > w / 3 || (abs(vx) > flingVelocity && vx * dx > 0)
                val left = dx < 0
                when {
                    e.actionMasked == MotionEvent.ACTION_CANCEL || !far -> settle()
                    byAnswer -> answerSwipe(if (left) "left" else "right")
                    left && position < ids.size - 1 -> flyOut(slotAt(position), -1, position + 1)
                    !left && position > 0 -> flyIn(position - 1, fromDrag = true)
                    else -> settle()
                }
            }
        }
        return true
    }

    /** The finger moved [dx] sideways: the top card follows (tilting a little), or the previous card slides in over it. */
    private fun drag(dx: Float, w: Float, byAnswer: Boolean) {
        val top = slotAt(position) ?: return
        when {
            byAnswer -> { top.translationX = dx; top.rotation = dx / w * TILT }
            dx < 0 && position < ids.size - 1 -> { top.translationX = dx; top.rotation = dx / w * TILT }
            dx > 0 && position > 0 -> slotAt(position - 1)?.let { p ->
                p.visibility = VISIBLE; p.scaleX = 1f; p.scaleY = 1f; p.translationY = 0f; p.translationZ = context.dp(5).toFloat()
                p.translationX = -w * 1.1f + dx; p.rotation = (-w + dx) / w * TILT
            }
            else -> { top.translationX = dx * 0.25f; top.rotation = 0f } // the end of the stack: a little give, then back
        }
    }

    /** Not far enough: everything back in place. */
    private fun settle() {
        val w = width.toFloat()
        slotAt(position)?.animate()?.translationX(0f)?.rotation(0f)?.setDuration(SHORT)?.start()
        slotAt(position - 1)?.takeIf { it.visibility == VISIBLE }?.animate()?.translationX(-w * 1.1f)?.rotation(-TILT)?.setDuration(SHORT)
            ?.withEndAction { layoutCards() }?.start()
    }

    private fun answerSwipe(dir: String) {
        val at = position
        val hasNext = at < ids.size - 1
        if (hasNext) flyOut(slotAt(at), if (dir == "left") -1 else 1, at + 1) { onSwipeAnswer(at, dir) }
        else {
            // The last card: it goes and comes back answered.
            onSwipeAnswer(at, dir)
            settle()
        }
    }

    /** The top card flies off sideways ([dir] -1 left, 1 right); [to] becomes the top. */
    private fun flyOut(top: View?, dir: Int, to: Int, then: () -> Unit = {}) {
        if (top == null) { position = to; layoutCards(); onMoved(to); then(); return }
        animating = true
        val w = width.toFloat()
        // The card behind grows into place while the top one goes.
        slotAt(to)?.let { n -> n.visibility = VISIBLE; n.animate().scaleX(1f).scaleY(1f).translationY(0f).setDuration(MEDIUM).start() }
        top.animate().translationX(dir * w * 1.3f).rotation(dir * TILT * 1.5f).setDuration(MEDIUM).setInterpolator(DecelerateInterpolator())
            .withEndAction {
                animating = false
                position = to
                layoutCards()
                onMoved(to)
                then()
            }.start()
    }

    /** Card [to] (before the current one) slides back in from the left on top. */
    private fun flyIn(to: Int, fromDrag: Boolean = false) {
        val p = slotAt(to) ?: return
        animating = true
        val w = width.toFloat()
        if (!fromDrag || p.visibility != VISIBLE) {
            p.visibility = VISIBLE; p.scaleX = 1f; p.scaleY = 1f; p.translationY = 0f
            p.translationX = -w * 1.1f; p.rotation = -TILT
        }
        p.translationZ = context.dp(5).toFloat()
        p.animate().translationX(0f).rotation(0f).setDuration(MEDIUM).setInterpolator(DecelerateInterpolator()).withEndAction {
            animating = false
            position = to
            layoutCards()
            onMoved(to)
        }.start()
    }

    private companion object {
        const val PEEK_SCALE = 0.94f
        const val TILT = 8f
        const val SHORT = 180L
        const val MEDIUM = 260L
    }
}
