package cc.bridgit.app.ui.interactions

import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import cc.bridgit.app.databinding.FragmentInteractionDetailBinding
import cc.bridgit.app.interact.IKind
import cc.bridgit.app.interact.IStatus
import cc.bridgit.app.interact.NotifRules
import cc.bridgit.app.interact.Interaction
import cc.bridgit.app.interact.InteractionNotifier
import cc.bridgit.app.ui.common.Fmt
import cc.bridgit.app.ui.common.dp
import cc.bridgit.app.ui.feedback.decodeFileThumb

/**
 * Interaction detail (UI-16): the header is drawn by Bridgit (agent, time, status), then the agent's title,
 * image, text and controls. Toolbar: Play aloud and Close. Opening a card marks it seen and clears its notification.
 *
 * HTML cards (CAP-34) are shown in a [CardStackView]. A card posted with a `stack` id brings its siblings (#0005): the
 * owner swipes through them, sees "n of m" and the next card peeking, and every card's page stays alive while this
 * screen is open. A single card is the same view with one slot: no dots, no counter, no drag (#0008).
 */
class InteractionDetailFragment : Fragment() {
    private var _b: FragmentInteractionDetailBinding? = null
    private val b get() = _b!!
    private val app get() = requireActivity().application as BridgeApp
    private val id get() = requireArguments().getLong("interactionId")
    /** The interaction on screen: the argument, or the stack card the owner moved to. */
    private var focusId = 0L
    /** CAP-56: this screen took over the owner's screen; once they're done it shows the next waiting card or steps aside. */
    private val arrival get() = requireArguments().getBoolean("arrival", false)
    private val automatic get() = arrival || requireArguments().getBoolean("automatic", false)

    private val drafts = Drafts()
    private var current: Interaction? = null
    private var shownKey: List<Any?>? = null
    /** CAP-34: the cards' pages by interaction id, each created on first view and destroyed with this view. */
    private val cards = HashMap<Long, CardWebView>()
    private val crashed = HashSet<Long>()
    /** Cards reloaded for resubmission (Back to questions), and cards whose answer is being saved. */
    private val cardEditing = HashSet<Long>()
    private val cardInFlight = HashSet<Long>()
    private var stackView: CardStackView? = null
    /** Phase 2: the native view of a pieces card, made once per card so its drafts and live sources survive redraws. */
    private var piecesView: PiecesCardView? = null
    /** #0005: the cards in the focused card's stack, in order (just the one card when it isn't stacked). */
    private var stack: List<Interaction> = emptyList()
    private var historyOnly: Boolean? = null
    private var leaving = false
    private var editingAnswer = false
    private var localAnswerInFlight = false
    private var closeAfterSend: kotlinx.coroutines.Job? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentInteractionDetailBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        ViewCompat.setOnApplyWindowInsetsListener(b.toolbar) { v, insets ->
            v.updatePadding(top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top); insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(b.scroll) { v, insets ->
            v.updatePadding(bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.ime()).bottom); insets
        }
        // The card's border runs to the bottom edge: the inset is a margin, so nothing sits outside the border.
        val cardMargin = (b.cardBox.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin
        b.cardBox.clipToOutline = true // the page is clipped to the rounded border
        ViewCompat.setOnApplyWindowInsetsListener(b.cardBox) { v, insets ->
            v.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = cardMargin + insets.getInsets(WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.ime()).bottom
            }; insets
        }
        historyOnly = savedInstanceState?.takeIf { it.containsKey("historyOnly") }?.getBoolean("historyOnly")
        leaving = false
        editingAnswer = false
        cardEditing.clear()
        focusId = savedInstanceState?.getLong("focusId", id)?.takeIf { it != 0L } ?: id
        b.stackPrev.setOnClickListener { stackView?.let { it.go(it.position - 1) } }
        b.stackNext.setOnClickListener { stackView?.let { it.go(it.position + 1) } }

        b.toolbar.setNavigationOnClickListener { navigateBack() }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = navigateBack()
        })
        b.toolbar.setOnMenuItemClickListener { item ->
            val i = current ?: return@setOnMenuItemClickListener false
            when (item.itemId) {
                R.id.interactions_play -> { SpeakHook.speak?.invoke(requireContext().applicationContext, InteractionUi.agentName(i), i.title, i.text); true }
                R.id.interactions_close -> { close(i); true }
                else -> false
            }
        }
        shownKey = null
        renderMenu()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                var first = true
                app.interactions.changed.collectLatest {
                    if (!first) delay(300)
                    first = false
                    val store = app.interactions
                    val (i, siblings) = withContext(Dispatchers.IO) {
                        val i = store.get(focusId) ?: store.get(id)
                        i to (if (i?.kind == IKind.HTML) store.stackOf(i) else emptyList())
                    }
                    if (historyOnly == null) historyOnly = !automatic && i != null && !InteractionUi.waiting(i)
                    if (i?.kind == IKind.HTML) renderStack(siblings, i) else render(i)
                    // Only the focused card was viewed, not its peeking or offscreen siblings.
                    val viewed = if (i?.kind == IKind.HTML) stack.firstOrNull { it.id == focusId } else i
                    viewed?.let { markViewed(it) }
                }
            }
        }
    }

    /** Viewing clears the alert without answering or closing the card. */
    private fun markViewed(i: Interaction) {
        InteractionNotifier.cancel(requireContext(), i.id)
        if (i.status != IStatus.PENDING) return
        val store = app.interactions
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) { runCatching { store.markSeen(i.id) } }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        historyOnly?.let { outState.putBoolean("historyOnly", it) }
        outState.putBoolean("editingAnswer", editingAnswer)
        outState.putLongArray("cardEditing", cardEditing.toLongArray())
        outState.putLong("focusId", focusId)
        super.onSaveInstanceState(outState)
    }

    // Back always leaves the card; a saved answer is never an instruction to ask again.
    private fun navigateBack() { findNavController().navigateUp() }

    private fun renderMenu() {
        val bb = _b ?: return
        val i = current
        bb.toolbar.menu.findItem(R.id.interactions_play)?.isVisible = i != null && SpeakHook.speak != null
        // An HTML card has Dismiss inside its border, so the toolbar keeps only app controls.
        bb.toolbar.menu.findItem(R.id.interactions_close)?.isVisible = i != null && InteractionUi.waiting(i) && i.kind != IKind.HTML
    }

    private fun render(i: Interaction?) {
        val bb = _b ?: return
        current = i
        // The agent took it back while it was on screen: show why for a moment, then move on.
        if (arrival && i?.status == IStatus.CANCELLED && closeAfterSend == null) finishSoon(NATIVE_CLOSE_DELAY_MS)
        if (historyOnly == false && i != null && !InteractionUi.waiting(i) &&
            InteractionUi.isQuestion(i) && closeAfterSend == null) finishSoon(NATIVE_CLOSE_DELAY_MS)
        bb.backToQuestions.isVisible = false
        bb.cardBackToQuestions.isVisible = false
        renderMenu()
        if (i == null) {
            bb.header.isVisible = false
            bb.banner.isVisible = false
            bb.image.isVisible = false
            bb.text.isVisible = false
            bb.controls.removeAllViews()
            bb.answer.isVisible = false
            bb.title.setText(R.string.interactions_not_found)
            shownKey = null
            return
        }
        // Rebuild only when something visible changed, so typing is never interrupted by other agents' updates.
        val key = InteractionUi.contentKey(i)
        if (key == shownKey) return
        val firstRender = shownKey == null
        shownKey = key
        val ctx = requireContext()
        val agent = InteractionUi.agentName(i)
        if (i.kind == IKind.HTML) { renderStack(stack.map { if (it.id == i.id) i else it }.ifEmpty { listOf(i) }, i); return }

        bb.header.isVisible = true
        bb.avatar.setAgent(agent)
        bb.from.text = getString(R.string.interactions_from, agent)
        bb.time.text = Fmt.ago(i.createdAt) + " · " + Fmt.dateTime(i.createdAt)
        InteractionUi.styleStatus(bb.status, i.status)
        bb.header.contentDescription = bb.from.text.toString() + ", " + bb.time.text + ", " + InteractionUi.statusLabel(ctx, i.status)

        val banner = when (i.status) {
            IStatus.EXPIRED -> getString(if (i.kind == IKind.QUESTION) R.string.interactions_late_banner else R.string.interactions_expired_banner, agent)
            IStatus.CANCELLED -> getString(R.string.interactions_cancelled_banner, agent)
            IStatus.DISMISSED -> getString(R.string.interactions_closed_banner, agent)
            else -> null
        }
        bb.banner.isVisible = banner != null
        bb.banner.text = banner

        bb.title.text = i.title
        if (i.kind == IKind.PIECES) {
            // The phone draws the card itself (docs/applet-cards.md); its header piece carries the title.
            bb.title.isVisible = runCatching { org.json.JSONObject(i.html ?: "{}").optJSONArray("pieces")?.optJSONObject(0)?.optString("type") != "header" }.getOrDefault(true)
            bb.text.isVisible = false
            bb.image.isVisible = false
            if (piecesView?.tag != i.id) {
                bb.controls.removeAllViews()
                piecesView = PiecesCardView(ctx, app, i).also { it.tag = i.id; bb.controls.addView(it) }
            }
            bb.answer.isVisible = false
            return
        }
        bb.title.isVisible = true
        // Text from an agent is plain: never auto-linked (links only through link controls, CAP-41).
        bb.text.isVisible = !i.text.isNullOrBlank()
        bb.text.text = i.text

        if (firstRender) loadImage(i)

        InteractionUi.buildControls(bb.controls, i, drafts, { button, values -> answer(i, button, values) }, resubmission = editingAnswer) { c, anchor ->
            InteractionUi.openLink(anchor, c)
        }

        val summary = InteractionUi.answerSummary(ctx, i)
        bb.answer.isVisible = summary != null
        bb.answer.text = summary
    }

    /**
     * CAP-34 and #0005: the toolbar says who sent the card on top ("From <Agent> · custom card", title); one bordered
     * box fills the rest of the screen with the position bar (2+ cards), the cards in a [CardStackView] and Bridgit's
     * strip (swipe hints, status, answer note, Back to questions, Dismiss) inside the border. Each page is loaded once;
     * later refreshes only update Bridgit's parts. [focus] is the card to show first.
     */
    private fun renderStack(list: List<Interaction>, focus: Interaction) {
        val bb = _b ?: return
        val ctx = requireContext()
        // Quiet siblings must not become visible when a takeover card is cancelled or the stack refreshes.
        if (leaving) return
        // History is read-only. A live stack keeps only waiting cards plus the focused
        // card's brief receipt; completed siblings must never be resurrected by refresh.
        val cardsInOrder = list.ifEmpty { listOf(focus) }.filter {
            (!automatic || !it.noTakeover) &&
                cc.bridgit.app.interact.CardLifecycle.includeInStack(InteractionUi.waiting(it), it.id == focus.id, historyOnly == true)
        }
        if (cardsInOrder.isEmpty()) {
            if (arrival) finishTakeover() else findNavController().navigateUp()
            return
        }
        bb.scroll.isVisible = false
        bb.cardBox.isVisible = true
        val sv = stackView ?: CardStackView(ctx).also { v ->
            bb.cardFrame.addView(v, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            v.onMoved = { pos -> onStackMoved(pos) }
            v.answersBySwipe = { pos -> stack.getOrNull(pos)?.let { swipeAnswers(it) } == true }
            v.onSwipeAnswer = { pos, dir -> stack.getOrNull(pos)?.let { swipeAnswer(it, dir) } }
            stackView = v
        }
        stack = cardsInOrder
        // Inside the border, a stack gives each card its own edge (so the peeking card shows) with a little room around it.
        val stacked = cardsInOrder.size > 1
        (if (stacked) ctx.dp(8) else 0).let { p -> bb.cardFrame.setPadding(p, p, p, p) }
        sv.setIds(cardsInOrder.map { it.id }, focus = focus.id).forEach { gone ->
            cards.remove(gone)?.let { runCatching { it.release() } }
        }
        cardsInOrder.getOrNull(sv.position)?.let { focusId = it.id }
        ensureVisibleCards()
        cardsInOrder.forEach(::updateCard)
        renderChrome()
        if (historyOnly == false && !InteractionUi.waiting(focus) && closeAfterSend == null)
            afterAnswer(focus.id)
    }

    /** Pages for the top card and the one peeking behind it; the rest are made when the owner gets there. */
    private fun ensureVisibleCards() {
        val sv = stackView ?: return
        listOfNotNull(stack.getOrNull(sv.position), stack.getOrNull(sv.position + 1)).forEach(::ensureCard)
    }

    private fun ensureCard(i: Interaction) {
        val sv = stackView ?: return
        val html = i.html ?: return
        if (cards.containsKey(i.id) || i.id in crashed) return
        val ctx = requireContext()
        val slot = sv.slot(i.id)
        if (stack.size > 1) {
            slot.setBackgroundResource(R.drawable.interactions_bg_card_frame)
            ctx.dp(1).let { p -> slot.setPadding(p, p, p, p) }
            slot.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            slot.clipToOutline = true
        }
        val id = i.id
        val v = CardWebView(ctx, onRespond = { data -> answerCard(id, data) }, onCrash = { cardCrashed(id) }, interactionId = id)
        v.contentDescription = getString(R.string.interactions_card_desc, InteractionUi.agentName(i))
        slot.addView(v, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        v.load(html)
        cards[id] = v
        updateCard(i)
    }

    /** Confirmation on answered cards (replayed after load), no more answers on closed ones. */
    private fun updateCard(i: Interaction) {
        val card = cards[i.id] ?: return
        val editing = i.id in cardEditing
        if (i.status == IStatus.ANSWERED && !editing && i.id !in cardInFlight) card.showConfirmation(i.answer,
            getString(R.string.interactions_confirmation_title), getString(R.string.interactions_confirmation_history, InteractionUi.agentName(i)))
        else if (!i.status.acceptsAnswer(editing)) card.stopAccepting()
    }

    private fun onStackMoved(pos: Int) {
        val i = stack.getOrNull(pos) ?: return
        focusId = i.id
        markViewed(i)
        // The keyboard belonged to the card that just left.
        stackView?.let { v -> (requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .hideSoftInputFromWindow(v.windowToken, 0) }
        closeAfterSend?.cancel(); closeAfterSend = null
        renderStack(stack, i)
    }

    /** Bridgit's parts around the top card: header, position bar, swipe hints, note, Dismiss, toolbar. */
    private fun renderChrome() {
        val bb = _b ?: return
        val sv = stackView ?: return
        val i = stack.getOrNull(sv.position) ?: return
        val ctx = requireContext()
        current = i
        // CAP-56: the agent took the card back while it was on screen: show why for a moment, then move on.
        if (arrival && i.status == IStatus.CANCELLED && stack.none { InteractionUi.waiting(it) } && closeAfterSend == null) finishSoon(NATIVE_CLOSE_DELAY_MS)
        val agent = InteractionUi.agentName(i)
        bb.backToQuestions.isVisible = false
        bb.cardBackToQuestions.isVisible = false
        renderMenu()
        bb.toolbar.title = getString(R.string.interactions_card_from, agent)
        bb.toolbar.subtitle = i.title
        InteractionUi.styleStatus(bb.cardStatus, i.status)

        val n = stack.size
        bb.stackBar.isVisible = n > 1
        if (n > 1) {
            bb.stackPosition.text = getString(R.string.interactions_stack_position, sv.position + 1, n)
            bb.stackPosition.contentDescription = getString(R.string.interactions_stack_position_a11y, sv.position + 1, n)
            bb.stackPrev.isEnabled = sv.position > 0
            bb.stackPrev.alpha = if (sv.position > 0) 1f else 0.3f
            bb.stackNext.isEnabled = sv.position < n - 1
            bb.stackNext.alpha = if (sv.position < n - 1) 1f else 0.3f
            InteractionUi.buildDots(bb.stackDots, stack, sv.position)
        }
        val swipe = i.swipe?.takeIf { n > 1 && swipeAnswers(i) }
        bb.swipeHints.isVisible = swipe != null
        swipe?.let {
            bb.swipeLeft.text = getString(R.string.interactions_swipe_left_hint, it.left)
            bb.swipeRight.text = getString(R.string.interactions_swipe_right_hint, it.right)
        }

        val note: CharSequence? = when {
            i.id in crashed -> getString(R.string.interactions_card_crashed, agent)
            i.status == IStatus.ANSWERED -> InteractionUi.answerSummary(ctx, i)
            i.status == IStatus.EXPIRED -> getString(R.string.interactions_late_banner, agent)
            i.status == IStatus.CANCELLED -> getString(R.string.interactions_cancelled_banner, agent)
            i.status == IStatus.DISMISSED -> getString(R.string.interactions_closed_banner, agent)
            else -> null
        }
        bb.cardNote.isVisible = note != null
        bb.cardNote.text = note
        bb.cardDismiss.isVisible = InteractionUi.waiting(i)
        bb.cardDismiss.contentDescription = getString(R.string.interactions_card_dismiss_desc, agent)
        bb.cardDismiss.setOnClickListener { close(i) }
    }

    /** A waiting card the agent made swipe-answerable, in a real stack (a single card is never swiped). */
    private fun swipeAnswers(i: Interaction) = i.swipe != null && stack.size > 1 && i.status.acceptsAnswer() && i.id !in cardInFlight

    /** A card's renderer died: drop its WebView (Bridgit keeps running) and say so. */
    private fun cardCrashed(cardId: Long) {
        crashed += cardId
        cards.remove(cardId)?.let { runCatching { it.release() } }
        renderChrome()
    }

    private fun replace(i: Interaction) { stack = stack.map { if (it.id == i.id) i else it } }

    /** The card's page called Bridgit.respond(json): stored as `answer.data`, like a button answer. */
    private fun answerCard(cardId: Long, data: Any) {
        if (_b == null || !isAdded || cardId in cardInFlight) return // the screen is going away; the card is released with it
        cardInFlight += cardId
        val resubmission = cardId in cardEditing
        val store = app.interactions
        val owner = viewLifecycleOwner
        owner.lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { store.answerCard(cardId, data, resubmission = resubmission) } }
            val bb = _b ?: return@launch
            r.onSuccess {
                cardEditing -= cardId
                replace(it)
                val moveOn = nextWaiting(cardId) != null
                cards[cardId]?.showConfirmation(it.answer, getString(R.string.interactions_confirmation_title),
                    getString(when {
                        moveOn -> R.string.interactions_confirmation_next
                        holds(it) -> R.string.interactions_confirmation_follow_up
                        stack.size > 1 && stack.any { c -> c.id != cardId && InteractionUi.waiting(c) } -> R.string.interactions_confirmation_stay
                        else -> R.string.interactions_confirmation_detail
                    }, InteractionUi.agentName(it)), cc.bridgit.app.interact.CardConfirmation.CLOSE_DELAY_MS)
                cardInFlight -= cardId
                renderChrome()
                afterAnswer(cardId)
            }.onFailure { e ->
                cardInFlight -= cardId
                cards[cardId]?.retryAnswer()
                Snackbar.make(bb.root, getString(R.string.interactions_answer_failed, e.message ?: e.javaClass.simpleName), Snackbar.LENGTH_LONG).show()
            }
        }
    }

    /** The next card after [cardId] that still waits, wrapping round to earlier ones; null when none is left. */
    private fun nextWaiting(cardId: Long): Int? {
        val at = stack.indexOfFirst { it.id == cardId }
        if (at < 0 || stack.size < 2) return null
        return (1 until stack.size).map { (at + it) % stack.size }.firstOrNull { InteractionUi.waiting(stack[it]) && (!automatic || !stack[it].noTakeover) && stack[it].id !in cardInFlight }
    }

    /** The agent said another card follows this one (follow_up): the screen waits for it instead of closing. */
    private fun holds(i: Interaction) = NotifRules.holdsForFollowUp(i.followUp, i.noTakeover)

    /**
     * After the confirmation delay: the next waiting card comes up (if the owner is still on this one), or, with
     * nothing left waiting, the screen closes as for a single card. A follow_up card first waits up to
     * [NotifRules.FOLLOW_UP_HOLD_MS] for the agent's next card: one in the same stack comes up here, any other
     * replaces this screen (MainActivity), so the owner never drops out of the flow between cards.
     */
    private fun afterAnswer(cardId: Long, firstDelayMs: Long = cc.bridgit.app.interact.CardConfirmation.CLOSE_DELAY_MS) {
        val owner = viewLifecycleOwner
        closeAfterSend?.cancel()
        val hold = stack.firstOrNull { it.id == cardId }?.let { holds(it) } == true
        closeAfterSend = owner.lifecycleScope.launch autoClose@ {
            delay(firstDelayMs)
            if (hold) {
                var waited = firstDelayMs
                while (waited < NotifRules.FOLLOW_UP_HOLD_MS && nextWaiting(cardId) == null) { delay(HOLD_POLL_MS); waited += HOLD_POLL_MS }
            }
            owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val sv = stackView
                if (_b != null && sv != null && sv.currentId == cardId) {
                    val next = nextWaiting(cardId)
                    when {
                        next != null -> {
                            val nextCard = stack[next]
                            closeAfterSend = null
                            renderStack(stack.filter { InteractionUi.waiting(it) }, nextCard)
                            markViewed(nextCard)
                        }
                        stack.none { InteractionUi.waiting(it) && (!automatic || !it.noTakeover) } && findNavController().currentDestination?.id == R.id.interactionDetail ->
                            if (arrival) finishTakeover() else findNavController().navigateUp()
                    }
                }
                this@autoClose.cancel()
            }
        }
    }

    /** #0005: a swipe answered a swipe_answer card; the stack has already moved on. */
    private fun swipeAnswer(i: Interaction, dir: String) {
        if (_b == null || i.id in cardInFlight) return
        cardInFlight += i.id
        val store = app.interactions
        viewLifecycleOwner.lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { store.answerSwipe(i.id, dir) } }
            cardInFlight -= i.id
            val bb = _b ?: return@launch
            r.onSuccess {
                replace(it)
                val label = i.swipe?.label(dir) ?: dir
                cards[i.id]?.showConfirmation(it.answer, getString(R.string.interactions_confirmation_title),
                    getString(R.string.interactions_swipe_sent, label))
                renderChrome()
                Snackbar.make(bb.root, getString(R.string.interactions_swipe_sent, label), Snackbar.LENGTH_SHORT).show()
                if (stackView?.currentId == i.id) afterAnswer(i.id) // the last card stays on top
            }.onFailure { e ->
                stack.indexOfFirst { it.id == i.id }.takeIf { it >= 0 }?.let { stackView?.go(it) }
                renderChrome()
                Snackbar.make(bb.root, getString(R.string.interactions_answer_failed, e.message ?: e.javaClass.simpleName), Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun loadImage(i: Interaction) {
        val name = i.image
        val bb = _b ?: return
        if (name == null) { bb.image.isVisible = false; return }
        val store = app.interactions
        val edge = resources.displayMetrics.widthPixels
        viewLifecycleOwner.lifecycleScope.launch {
            val bmp: Bitmap? = withContext(Dispatchers.IO) { store.imageFile(name)?.let { runCatching { decodeFileThumb(it.path, edge) }.getOrNull() } }
            val v = _b ?: return@launch
            v.image.isVisible = bmp != null
            if (bmp == null) return@launch
            v.image.setImageBitmap(bmp)
            v.image.contentDescription = getString(R.string.interactions_image, InteractionUi.agentName(i)) + ". " + getString(R.string.interactions_image_hint)
            v.image.setOnClickListener { showBig(name, InteractionUi.agentName(i)) }
        }
    }

    /** Tap the picture to see it bigger. */
    private fun showBig(name: String, agent: String) {
        val store = app.interactions
        val edge = maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        viewLifecycleOwner.lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) { store.imageFile(name)?.let { runCatching { decodeFileThumb(it.path, edge) }.getOrNull() } } ?: return@launch
            if (_b == null) return@launch
            val iv = ImageView(requireContext()).apply {
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageBitmap(bmp)
                contentDescription = getString(R.string.interactions_image, agent)
            }
            MaterialAlertDialogBuilder(requireContext()).setView(iv).setPositiveButton(R.string.interactions_ok, null).show()
        }
    }

    private fun answer(i: Interaction, button: String?, values: Map<String, String>) {
        if (localAnswerInFlight) return
        localAnswerInFlight = true
        val resubmission = editingAnswer
        val store = app.interactions
        viewLifecycleOwner.lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { store.answer(i.id, button, values, resubmission = resubmission) } }
            localAnswerInFlight = false
            val bb = _b ?: return@launch
            r.onSuccess {
                drafts.clear(i.id)
                editingAnswer = false
                render(it)
                Snackbar.make(bb.root, getString(if (holds(it)) R.string.interactions_confirmation_follow_up else R.string.interactions_sent_snack,
                    InteractionUi.agentName(i)), if (holds(it)) Snackbar.LENGTH_LONG else Snackbar.LENGTH_SHORT).show()
                finishSoon(NATIVE_CLOSE_DELAY_MS, holds(it))
            }.onFailure { e ->
                Snackbar.make(bb.root, getString(R.string.interactions_answer_failed, e.message ?: e.javaClass.simpleName), Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun close(i: Interaction) {
        val store = app.interactions
        viewLifecycleOwner.lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { store.dismiss(i.id) } }
            val bb = _b ?: return@launch
            val closed = r.getOrNull() ?: return@launch
            val agent = InteractionUi.agentName(i)
            if (closed.kind == IKind.HTML) {
                // Like an answer: the card says it was closed (no bridgit:answered event), then the flow moves on.
                replace(closed); renderChrome()
                cards[closed.id]?.showConfirmation(null, getString(R.string.interactions_closed_title),
                    getString(if (holds(closed)) R.string.interactions_closed_follow_up else R.string.interactions_closed_detail, agent), answered = false)
                afterAnswer(closed.id, DISMISS_DELAY_MS)
            } else {
                render(closed)
                Snackbar.make(bb.root, getString(if (holds(closed)) R.string.interactions_closed_follow_up else R.string.interactions_closed_snack, agent),
                    Snackbar.LENGTH_LONG).show()
                finishSoon(NATIVE_CLOSE_DELAY_MS, holds(closed))
            }
        }
    }

    /** After [delayMs] (long enough to read "You answered" or the banner), [finishTakeover] unless the owner went back to edit. */
    private fun finishSoon(delayMs: Long, hold: Boolean = false) {
        if (_b == null) return
        closeAfterSend?.cancel()
        val owner = viewLifecycleOwner
        closeAfterSend = owner.lifecycleScope.launch finish@ {
            // follow_up: the agent's next card replaces this screen when it comes; this only runs if it doesn't.
            delay(if (hold) maxOf(delayMs, NotifRules.FOLLOW_UP_HOLD_MS) else delayMs)
            owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                if (_b != null && !editingAnswer && findNavController().currentDestination?.id == R.id.interactionDetail) {
                    if (arrival) finishTakeover() else findNavController().navigateUp()
                }
                this@finish.cancel()
            }
        }
    }

    /**
     * CAP-56: the owner is done with a card that took over their screen. Show the oldest other waiting
     * question next, or leave Bridgit so they're back where they were (the card is popped first, so
     * opening Bridgit later doesn't show it again).
     */
    private fun finishTakeover() {
        if (leaving) return
        leaving = true
        val store = app.interactions
        val nav = findNavController()
        val activity = activity ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val next = withContext(Dispatchers.IO) {
                runCatching { store.waitingQuestions().filter { it.id != id && !it.noTakeover }.minByOrNull { it.id }?.id }.getOrNull()
            }
            when (val a = NotifRules.afterCard(arrival, next)) {
                is NotifRules.AfterCard.OpenNext -> nav.navigate(R.id.interactionDetail,
                    Bundle().apply { putLong("interactionId", a.id); putBoolean("arrival", true); putBoolean("automatic", true) },
                    androidx.navigation.NavOptions.Builder().setPopUpTo(R.id.interactionDetail, true).build())
                NotifRules.AfterCard.StepAside -> { nav.popBackStack(); activity.moveTaskToBack(true) }
                NotifRules.AfterCard.Stay -> {}
            }
        }
    }

    private companion object {
        /** How long "You answered" (or a closed/cancelled banner) stays before a takeover card moves on. */
        const val NATIVE_CLOSE_DELAY_MS = 1500L
        /** A closed card's "Closed" panel stays this long before the flow moves on (shorter than an answer's). */
        const val DISMISS_DELAY_MS = 2500L
        const val HOLD_POLL_MS = 500L
    }

    override fun onDestroyView() {
        localAnswerInFlight = false
        closeAfterSend?.cancel(); closeAfterSend = null
        cards.values.forEach { runCatching { it.release() } }
        cards.clear(); cardInFlight.clear(); crashed.clear()
        stackView = null
        piecesView = null
        super.onDestroyView(); _b = null; shownKey = null
    }
}
